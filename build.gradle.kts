import java.io.DataOutputStream

plugins {
    java
    application
}

group = "dev.rilvune"
version = "2026.4.66"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

application {
    mainClass.set("dev.rilvune.app.RilvuneApplication")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.java2d.uiScale.enabled=true",
    )
}

val macosIcon = tasks.register("macosIcon") {
    description = "Builds the macOS icon from the supplied PNGs without resampling them."
    val small = layout.projectDirectory.file("images/editor-logo-64.png")
    val large = layout.projectDirectory.file("images/editor-logo-256.png")
    val output = layout.buildDirectory.file("generated/branding/Rilvune.icns")
    inputs.files(small, large)
    outputs.file(output)
    doLast {
        // Modern ICNS stores PNG representations with four-byte type/length headers.
        // Keep the original resolutions and transparency; do not invent upscaled artwork.
        val representations = listOf("icp6" to small.asFile.readBytes(), "ic08" to large.asFile.readBytes())
        val destination = output.get().asFile
        destination.parentFile.mkdirs()
        DataOutputStream(destination.outputStream()).use { stream ->
            stream.writeBytes("icns")
            stream.writeInt(8 + representations.sumOf { 8 + it.second.size })
            for ((type, png) in representations) {
                stream.writeBytes(type)
                stream.writeInt(8 + png.size)
                stream.write(png)
            }
        }
    }
}

tasks.processResources {
    from("images") {
        include("editor-logo-64.png", "editor-logo-256.png", "editor-logo.svg")
        into("branding")
    }
    from(macosIcon) { into("branding") }
}

distributions {
    main {
        contents {
            from("LICENSE") { into("licenses") }
            from("licenses") { into("licenses") }
            from("src/main/resources/fonts/HACK-LICENSE.md") { into("licenses") }
            from("src/main/resources/agents/opencode/LICENSE.txt") {
                into("licenses")
                rename { "OpenCode-LICENSE.txt" }
            }
            from(macosIcon) { into("branding") }
        }
    }
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.fifesoft:rsyntaxtextarea:4.0.1")
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("ui") }
    inputs.property("agentSmoke", providers.environmentVariable("RILVUNE_AGENT_SMOKE").orElse("false"))
    inputs.property("agentAuthSmoke", providers.environmentVariable("RILVUNE_AGENT_AUTH_SMOKE").orElse("false"))
    inputs.property("mockAgentProvider", providers.environmentVariable("OPENAI_API_KEY")
        .map { it == "rilvune-smoke-placeholder" }.orElse(false))
}

tasks.register<Test>("uiTest") {
    description = "Runs real Swing integration tests on a desktop or Xvfb display."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("ui") }
    systemProperty("java.awt.headless", "false")
    shouldRunAfter(tasks.test)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val releaseVersion = providers.exec {
    commandLine("bash", layout.projectDirectory.file("scripts/release-version.sh").asFile)
}.standardOutput.asText.map { it.trim() }

val buildInfo = tasks.register("buildInfo") {
    val output = layout.buildDirectory.file("generated/version/build-info.properties")
    inputs.property("version", releaseVersion)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=${releaseVersion.get()}\n")
        }
    }
}
tasks.processResources { from(buildInfo) }

val releaseRuntimes = layout.buildDirectory.dir("release-runtimes")
val prepareRuntimes = tasks.register<Exec>("prepareRuntimes") {
    description = "Verifies and stages pinned Java runtimes for the single universal release."
    group = "distribution"
    // Always verify cached archives, including when Gradle considers its outputs current.
    commandLine("bash", layout.projectDirectory.file("scripts/prepare-runtimes.sh").asFile,
        layout.projectDirectory.dir(".cache/java-runtimes").asFile, releaseRuntimes.get().asFile)
}

val testReleasePackaging = tasks.register<Exec>("testReleasePackaging") {
    description = "Checks universal runtime routing, packaging and safe release staging."
    group = "verification"
    commandLine("bash", layout.projectDirectory.file("scripts/test-packaging.sh").asFile)
}

val releaseScriptChecks = listOf("version", "workflow", "publish", "runtimes").map { name ->
    tasks.register<Exec>("testRelease${name.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        commandLine("bash", layout.projectDirectory.file("scripts/test-$name.sh").asFile)
    }
}
tasks.check { dependsOn(testReleasePackaging, releaseScriptChecks) }

tasks.register<Exec>("release") {
    description = "Tests and packages one release for Linux and macOS with Java included."
    group = "distribution"
    dependsOn(tasks.check, tasks.installDist, prepareRuntimes)
    commandLine(
        "bash", layout.projectDirectory.file("scripts/package-release.sh").asFile,
        layout.buildDirectory.dir("install/rilvune").get().asFile,
        releaseRuntimes.get().asFile,
        layout.buildDirectory.dir("release").get().asFile,
        releaseVersion.get(),
    )
}

tasks.register("promote") {
    description = "Runs headless, Swing and release checks, then stages the single universal release."
    group = "distribution"
    dependsOn("release", "uiTest")
}
tasks.named("release") { mustRunAfter("uiTest") }
