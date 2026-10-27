package dev.rilvune.agents.ui;

import javax.swing.*;
import java.awt.Image;
import java.awt.datatransfer.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** File drops and clipboard images share intake; ordinary text paste/drop keeps Swing's original behavior. */
final class AgentAttachmentTransferHandler extends TransferHandler {
    private final TransferHandler text;
    private final Consumer<List<Path>> files;
    private final Consumer<Image> images;
    private final Consumer<String> errors;
    private final java.util.function.BooleanSupplier enabled;
    AgentAttachmentTransferHandler(TransferHandler text, Consumer<List<Path>> files, Consumer<Image> images, Consumer<String> errors, java.util.function.BooleanSupplier enabled) {
        this.text = text; this.files = files; this.images = images; this.errors = errors; this.enabled = enabled;
    }
    @Override public boolean canImport(TransferSupport support) {
        return attachment(support.getDataFlavors()) ? enabled.getAsBoolean() : text != null && text.canImport(support);
    }
    @Override public boolean canImport(JComponent component, DataFlavor[] flavors) {
        return attachment(flavors) ? enabled.getAsBoolean() : text != null && text.canImport(component, flavors);
    }
    @Override public boolean importData(TransferSupport support) {
        return attachment(support.getDataFlavors()) ? receive(support.getTransferable()) : text != null && text.importData(support);
    }
    @Override public boolean importData(JComponent component, Transferable data) {
        return attachment(data.getTransferDataFlavors()) ? receive(data) : text != null && text.importData(component, data);
    }
    @Override public int getSourceActions(JComponent component) { return text == null ? NONE : text.getSourceActions(component); }
    @Override public void exportToClipboard(JComponent component, Clipboard clipboard, int action) throws IllegalStateException {
        if (text != null) text.exportToClipboard(component, clipboard, action);
    }
    @Override public void exportAsDrag(JComponent component, java.awt.event.InputEvent event, int action) {
        if (text != null) text.exportAsDrag(component, event, action);
    }
    private boolean receive(Transferable data) {
        if (!enabled.getAsBoolean()) return false;
        try {
            if (data.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object value = data.getTransferData(DataFlavor.javaFileListFlavor);
                if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Clipboard did not contain a file list");
                List<Path> paths = new ArrayList<>();
                for (Object item : list) { if (!(item instanceof File file)) throw new IllegalArgumentException("Invalid dropped file"); paths.add(file.toPath()); }
                files.accept(paths); return true;
            }
            if (data.isDataFlavorSupported(DataFlavor.imageFlavor)) { images.accept((Image)data.getTransferData(DataFlavor.imageFlavor)); return true; }
            for (DataFlavor flavor : data.getTransferDataFlavors()) if (uriList(flavor)) {
                String value = (String)data.getTransferData(flavor); List<Path> paths = new ArrayList<>();
                for (String line : value.split("\\R")) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    URI uri = URI.create(line.strip());
                    if (!"file".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Only local files can be attached");
                    paths.add(Path.of(uri));
                }
                if (paths.isEmpty()) return false;
                files.accept(paths); return true;
            }
        } catch (Exception failure) { errors.accept("Could not attach: " + failure.getMessage()); }
        return false;
    }
    private static boolean attachment(DataFlavor[] flavors) {
        for (DataFlavor flavor : flavors) if (flavor.equals(DataFlavor.javaFileListFlavor) || flavor.equals(DataFlavor.imageFlavor) || uriList(flavor)) return true;
        return false;
    }
    private static boolean uriList(DataFlavor flavor) { return flavor.isMimeTypeEqual("text/uri-list") && flavor.getRepresentationClass() == String.class; }
}
