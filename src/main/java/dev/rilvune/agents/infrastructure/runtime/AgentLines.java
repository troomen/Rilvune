package dev.rilvune.agents.infrastructure.runtime;

import java.io.*;

/** Bounded protocol lines; BufferedReader.readLine alone permits unbounded child-process output. */
public final class AgentLines {
    private AgentLines() { }
    public static String read(Reader reader, int limit) throws IOException {
        StringBuilder line = new StringBuilder(); int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') return line.toString();
            if (line.length() >= limit) throw new IOException("Agent output exceeded its line limit");
            if (value != '\r') line.append((char)value);
        }
        return line.isEmpty() ? null : line.toString();
    }
}
