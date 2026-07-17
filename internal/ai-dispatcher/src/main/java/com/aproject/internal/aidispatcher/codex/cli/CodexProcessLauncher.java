package com.aproject.internal.aidispatcher.codex.cli;

import java.io.IOException;

interface CodexProcessLauncher {

    CodexManagedProcess start(CodexCliProcessSpec processSpec) throws IOException;
}
