package dev.rilvune.git.infrastructure;

import dev.rilvune.git.domain.GitBlameLine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** --line-porcelain has a complete metadata record for every source line. */
final class GitBlameParser {
    private GitBlameParser() { }
    static List<GitBlameLine> parse(byte[] bytes) throws IOException {
        List<GitBlameLine> result = new ArrayList<>();
        String[] lines = GitProtocol.utf8(bytes).split("\n", -1);
        int cursor = 0;
        try {
            while (cursor < lines.length && !lines[cursor].isEmpty()) {
                String[] header = lines[cursor++].split(" ");
                if (header.length < 3 || header.length > 4 || !header[0].matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Malformed blame header");
                int original = Integer.parseInt(header[1]) - 1, line = Integer.parseInt(header[2]) - 1;
                if (original < 0 || line != result.size() || result.size() >= 20000) throw new IOException("Invalid/oversized blame line sequence");
                Map<String, String> fields = new HashMap<>(); boolean content = false;
                while (cursor < lines.length) {
                    String value = lines[cursor++];
                    if (value.startsWith("\t")) { content = true; break; }
                    int space = value.indexOf(' ');
                    if (space > 0) {
                        if (value.length() > 16384 || fields.size() > 64) throw new IOException("Oversized blame metadata");
                        fields.put(value.substring(0, space), value.substring(space + 1));
                    } else if (!value.equals("boundary")) throw new IOException("Malformed blame metadata");
                }
                if (!content) throw new IOException("Truncated blame record");
                String author = required(fields, "author"), path = unquote(required(fields, "filename"));
                dev.rilvune.git.domain.RepositoryPaths.resolve(java.nio.file.Path.of("/"), path);
                if (author.length() > 4096 || path.length() > 8192) throw new IOException("Oversized blame author/path");
                String zone = required(fields, "author-tz");
                if (!zone.matches("[+-][0-9]{4}")) throw new IOException("Invalid blame timezone");
                result.add(new GitBlameLine(line, header[0], author, Instant.ofEpochSecond(Long.parseLong(required(fields, "author-time"))),
                        ZoneOffset.of(zone.substring(0, 3) + ":" + zone.substring(3)), required(fields, "summary"), path, original));
            }
            for (; cursor < lines.length; cursor++) if (!lines[cursor].isEmpty()) throw new IOException("Trailing blame data");
        } catch (IllegalArgumentException | DateTimeException failure) { throw new IOException("Malformed blame data", failure); }
        return List.copyOf(result);
    }
    private static String required(Map<String, String> fields, String name) throws IOException {
        String value = fields.get(name); if (value == null) throw new IOException("Missing blame " + name); return value;
    }
    private static String unquote(String value) throws IOException {
        if (!value.startsWith("\"")) return value;
        if (!value.endsWith("\"")) throw new IOException("Invalid quoted blame path");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 1; i < value.length() - 1;) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (cp != '\\') { bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)); continue; }
            if (i >= value.length() - 1) throw new IOException("Truncated quoted blame path");
            char escape = value.charAt(i++);
            if (escape >= '0' && escape <= '7') {
                if (i + 1 >= value.length() - 1) throw new IOException("Truncated octal path");
                String octal = "" + escape + value.charAt(i++) + value.charAt(i++);
                if (!octal.matches("[0-3][0-7]{2}")) throw new IOException("Invalid octal path");
                bytes.write(Integer.parseInt(octal, 8));
            } else {
                int decoded = switch (escape) { case '\\' -> '\\'; case '"' -> '"'; case 't' -> '\t'; case 'n' -> '\n'; case 'r' -> '\r'; case 'a' -> 7; case 'b' -> 8; case 'v' -> 11; case 'f' -> 12; default -> -1; };
                if (decoded < 0) throw new IOException("Invalid path escape"); bytes.write(decoded);
            }
        }
        return GitProtocol.utf8(bytes.toByteArray());
    }
}
