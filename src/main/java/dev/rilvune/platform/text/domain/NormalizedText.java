package dev.rilvune.platform.text.domain;

import java.io.IOException;

/** Common source representation; final newline is retained, physical line endings and BOM are recorded. */
public record NormalizedText(String text, boolean byteOrderMark, String lineEnding) {
    public static NormalizedText from(String decoded) throws IOException {
        boolean bom = decoded.startsWith("\uFEFF");
        return content(bom ? decoded.substring(1) : decoded, bom);
    }
    /** Content after an adapter has already removed its encoding marker. */
    public static NormalizedText content(String raw, boolean bom) throws IOException {
        boolean lf = false, crlf = false, cr = false;
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') { crlf = true; i++; }
                else cr = true;
                normalized.append('\n');
            } else {
                if (c == '\n') lf = true;
                else if (Character.isISOControl(c) && c != '\t' && c != '\f') throw new IOException("Binary content cannot be inspected as text");
                normalized.append(c);
            }
        }
        int kinds = (lf ? 1 : 0) + (crlf ? 1 : 0) + (cr ? 1 : 0);
        return new NormalizedText(normalized.toString(), bom, kinds > 1 ? "MIXED" : crlf ? "CRLF" : cr ? "CR" : "LF");
    }
}
