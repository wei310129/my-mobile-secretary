package com.aproject.internal.aidispatcher.codex.cli;

import java.io.IOException;
import java.io.Reader;

final class BoundedLineReader {

    private final Reader reader;
    private final int maximumLength;

    BoundedLineReader(Reader reader, int maximumLength) {
        this.reader = reader;
        this.maximumLength = maximumLength;
    }

    String readLine() throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maximumLength, 4096));
        while (true) {
            int value = reader.read();
            if (value == -1) {
                return line.isEmpty() ? null : line.toString();
            }
            if (value == '\n') {
                return line.toString();
            }
            if (value != '\r') {
                if (line.length() >= maximumLength) {
                    throw new CodexCliProtocolException(
                            "Codex JSONL line exceeds configured limit");
                }
                line.append((char) value);
            }
        }
    }
}
