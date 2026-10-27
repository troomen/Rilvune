package dev.editor.platform.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** Opens web links on a worker. The desktop portal also reaches the host browser from Docker. */
public final class SystemBrowser {
    private SystemBrowser() { }

    public static void browse(URI uri) throws IOException, InterruptedException {
        if (uri == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("A web address is required");

        if (System.getProperty("os.name", "").startsWith("Linux")
                && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null && openThroughPortal(uri)) return;
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
            return;
        }
        throw new IOException("No system browser is available; copy the sign-in link into your browser");
    }

    private static boolean openThroughPortal(URI uri) throws InterruptedException {
        Process process;
        try {
            // gio keeps its bus connection alive until the portal responds. A one-shot gdbus call
            // only returns a request handle and can disconnect before the browser has opened.
            ProcessBuilder command = new ProcessBuilder("gio", "open", uri.toASCIIString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Scoped to this helper: the container has no browser/desktop registrations of its own.
            command.environment().put("GIO_USE_PORTALS", "1");
            process = command.start();
        } catch (IOException unavailable) { return false; }
        try { return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0; }
        finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
}
