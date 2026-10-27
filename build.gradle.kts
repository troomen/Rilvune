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

distributions {
    main {
        contents {
            from("LICENSE") { into("licenses") }
            from("licenses") { into("licenses") }
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
}

tasks.register<Test>("uiTest") {
    description = "Runs real Swing integration tests under the caller's Xvfb display."
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

val releaseJava = javaToolchains.launcherFor(java.toolchain)
val releaseRuntime = layout.buildDirectory.dir("release-runtime")
val releaseModules = "java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs"

val runtimeImage = tasks.register<Exec>("runtimeImage") {
    description = "Builds the Java runtime bundled in Linux releases."
    group = "distribution"
    inputs.property("javaRuntime", releaseJava.map { it.metadata.javaRuntimeVersion })
    inputs.property("platform", "${System.getProperty("os.name")}-${System.getProperty("os.arch")}")
    inputs.property("modules", releaseModules)
    outputs.dir(releaseRuntime)
    doFirst {
        check(System.getProperty("os.name") == "Linux") { "Standalone releases currently support Linux only." }
        delete(releaseRuntime)
        commandLine(
            releaseJava.get().metadata.installationPath.file("bin/jlink").asFile,
            "--add-modules", releaseModules,
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
            "--output", releaseRuntime.get().asFile,
        )
    }
}

tasks.register<Exec>("release") {
    description = "Tests and packages one self-extracting Linux executable with Java included."
    group = "distribution"
    dependsOn(tasks.test, tasks.installDist, runtimeImage)
    commandLine(
        "bash", layout.projectDirectory.file("scripts/package-release.sh").asFile,
        layout.buildDirectory.dir("install/rilvune").get().asFile,
        releaseRuntime.get().asFile,
        layout.buildDirectory.dir("release").get().asFile,
        providers.exec {
            commandLine("bash", layout.projectDirectory.file("scripts/release-version.sh").asFile)
        }.standardOutput.asText.get().trim(),
    )
}
