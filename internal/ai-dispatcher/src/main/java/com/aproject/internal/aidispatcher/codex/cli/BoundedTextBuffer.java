package com.aproject.internal.aidispatcher.codex.cli;

final class BoundedTextBuffer {

    private final int maximumCharacters;
    private final StringBuilder value = new StringBuilder();

    BoundedTextBuffer(int maximumCharacters) {
        this.maximumCharacters = maximumCharacters;
    }

    synchronized void append(char[] characters, int offset, int length) {
        value.append(characters, offset, length);
        int overflow = value.length() - maximumCharacters;
        if (overflow > 0) {
            value.delete(0, overflow);
        }
    }

    synchronized String snapshot() {
        return value.toString();
    }
}
