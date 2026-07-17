package com.aproject.internal.aidispatcher.codex.cli;

import java.io.InputStream;
import java.io.OutputStream;

interface CodexManagedProcess {

    long pid();

    OutputStream standardInput();

    InputStream standardOutput();

    InputStream standardError();

    boolean isAlive();

    int waitFor() throws InterruptedException;

    void destroyBeforeDispatch();
}
