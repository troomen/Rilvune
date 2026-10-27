package dev.rilvune.platform.runtime;

import java.util.Locale;

/** The platform of the running JVM, with original values retained for diagnostics. */
public record HostPlatform(OperatingSystem operatingSystem, Architecture architecture,
                           String detectedOperatingSystem, String detectedArchitecture) {
    public enum OperatingSystem {
        LINUX("linux", "Linux"), MACOS("macos", "macOS"), WINDOWS("windows", "Windows"), UNKNOWN("unknown", "Unknown OS");
        private final String id, label;
        OperatingSystem(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        public String label() { return label; }
    }
    public enum Architecture {
        X86_64("x86_64"), ARM64("arm64"), UNKNOWN("unknown");
        private final String id;
        Architecture(String id) { this.id = id; }
        public String id() { return id; }
    }
    public static HostPlatform current() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }
    public static HostPlatform detect(String operatingSystem, String architecture) {
        String rawOs = display(operatingSystem), rawArch = display(architecture);
        String os = normalize(operatingSystem), arch = normalize(architecture);
        OperatingSystem system = switch (os) {
            case "linux" -> OperatingSystem.LINUX;
            case "mac os x", "macos", "mac os", "darwin" -> OperatingSystem.MACOS;
            default -> os.equals("windows") || os.startsWith("windows ") ? OperatingSystem.WINDOWS : OperatingSystem.UNKNOWN;
        };
        Architecture cpu = switch (arch) {
            case "amd64", "x86_64", "x86-64", "x64" -> Architecture.X86_64;
            case "aarch64", "arm64" -> Architecture.ARM64;
            default -> Architecture.UNKNOWN;
        };
        return new HostPlatform(system, cpu, rawOs, rawArch);
    }
    public boolean recognized() { return operatingSystem != OperatingSystem.UNKNOWN && architecture != Architecture.UNKNOWN; }
    public String id() { return operatingSystem.id() + "-" + architecture.id(); }
    public String description() {
        return (operatingSystem == OperatingSystem.UNKNOWN ? detectedOperatingSystem : operatingSystem.label())
                + " / " + (architecture == Architecture.UNKNOWN ? detectedArchitecture : architecture.id())
                + " (os.name=" + detectedOperatingSystem + ", os.arch=" + detectedArchitecture + ")";
    }
    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private static String display(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.strip().replaceAll("[\\p{Cntrl}]", "?");
    }
}
