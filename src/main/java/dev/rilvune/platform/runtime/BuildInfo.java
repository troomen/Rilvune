package dev.rilvune.platform.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Shared build identity for the UI, agent protocol and release artifact. */
public final class BuildInfo {
    private BuildInfo() { }

    public static String version() {
        try (InputStream stream = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (stream == null) return "development";
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version", "development");
        } catch (IOException unavailable) { return "development"; }
    }
}
