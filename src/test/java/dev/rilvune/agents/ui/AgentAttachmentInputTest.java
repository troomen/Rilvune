package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.rilvune.agents.ui.AgentReasoningViewTest.*;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;

class AgentAttachmentInputTest {
    @Test void imagePasteFileDropAndUriListUseTheSameIntakeWithoutBreakingTextCopyPaste() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea input = new JTextArea(); AtomicReference<List<Path>> files = new AtomicReference<>(); AtomicReference<Image> image = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
            var handler = new AgentAttachmentTransferHandler(input.getTransferHandler(), files::set, image::set, error::set, () -> true); input.setTransferHandler(handler);
            assertTrue(handler.importData(input, new StringSelection("ordinary text"))); assertEquals("ordinary text", input.getText());
            Clipboard clipboard = new Clipboard("test"); input.selectAll(); handler.exportToClipboard(input, clipboard, TransferHandler.COPY);
            try { assertEquals("ordinary text", clipboard.getData(DataFlavor.stringFlavor)); } catch (Exception failure) { throw new AssertionError(failure); }
            BufferedImage screenshot = new BufferedImage(8, 5, BufferedImage.TYPE_INT_RGB);
            assertTrue(handler.importData(input, transferable(DataFlavor.imageFlavor, screenshot))); assertSame(screenshot, image.get());
            assertEquals("ordinary text", input.getText(), "Images must not replace selected prompt text");
            assertTrue(handler.importData(new TransferHandler.TransferSupport(input, transferable(DataFlavor.javaFileListFlavor, List.of(new java.io.File("/project/file.txt"))))));
            assertEquals(List.of(Path.of("/project/file.txt")), files.get());
            try {
                DataFlavor uri = new DataFlavor("text/uri-list;class=java.lang.String");
                assertTrue(handler.importData(input, transferable(uri, "# files\r\nfile:///project/file%20name.txt\r\n")));
                assertEquals(List.of(Path.of("/project/file name.txt")), files.get());
                assertFalse(handler.importData(input, transferable(uri, "https://example.com/file"))); assertNotNull(error.get());
            } catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
            assertEquals(TransferHandler.COPY_OR_MOVE, handler.getSourceActions(input));
        });
    }
    @Test void composerAllowsAttachmentOnlySendAndKeepsACompactRemovableShelf() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<String> sent = new AtomicReference<>(), removed = new AtomicReference<>();
            var composer = new AgentComposerView(EditorTheme.graphite(), sent::set, ignored -> { }, ignored -> { }, (id, value) -> { }, ignored -> 0, () -> { });
            composer.configureAttachments(() -> { }, ignored -> { }, ignored -> { }, removed::set, item -> Path.of("/private/attachments", item.id()), Runnable::run);
            var file = new AgentAttachment("a".repeat(64) + ".txt", "A very long source file name.java", "text/plain", 300);
            var base = snapshot(READY, List.of());
            composer.render(withAttachments(base, List.of(file), false));
            JButton send = named(composer, "agents.send", JButton.class); assertTrue(send.isEnabled());
            send.doClick(); assertEquals("", sent.get());
            named(composer, "agents.attachment.remove", JButton.class).doClick(); assertEquals(file.key(), removed.get());
            composer.render(withAttachments(base, List.of(file), true)); assertFalse(send.isEnabled()); assertFalse(named(composer, "agents.attach", JButton.class).isEnabled());
            composer.render(base); assertFalse(send.isEnabled());
        });
    }
    @Test void clipboardEncodingAndPreviewAreBoundedAndPreserveDimensions() throws Exception {
        BufferedImage image = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
        byte[] png = AgentAttachmentImages.png(image); var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(800, decoded.getWidth()); assertEquals(400, decoded.getHeight());
        assertThrows(java.io.IOException.class, () -> AgentAttachmentImages.png(new Image() {
            public int getWidth(java.awt.image.ImageObserver observer) { return 10000; }
            public int getHeight(java.awt.image.ImageObserver observer) { return 10000; }
            public java.awt.image.ImageProducer getSource() { return null; }
            public Graphics getGraphics() { return null; }
            public Object getProperty(String name, java.awt.image.ImageObserver observer) { return null; }
        }));
    }
    static Transferable transferable(DataFlavor flavor, Object value) {
        return new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {flavor}; }
            public boolean isDataFlavorSupported(DataFlavor candidate) { return flavor.equals(candidate); }
            public Object getTransferData(DataFlavor candidate) throws UnsupportedFlavorException { if (!isDataFlavorSupported(candidate)) throw new UnsupportedFlavorException(candidate); return value; }
        };
    }
    private static AgentSnapshot withAttachments(AgentSnapshot s, List<AgentAttachment> files, boolean importing) {
        return new AgentSnapshot(s.project(), s.phase(), s.status(), s.engine(), s.provider(), s.session(), s.login(), s.cancelling(), s.messages(), s.permissions(), s.history(), s.chatTitle(), s.draft(), s.draftRevision(), s.persistenceError(), s.historyTruncated(), s.details(), files, importing);
    }
}
