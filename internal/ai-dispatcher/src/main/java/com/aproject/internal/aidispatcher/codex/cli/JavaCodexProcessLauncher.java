package com.aproject.internal.aidispatcher.codex.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class JavaCodexProcessLauncher implements CodexProcessLauncher {

    private static final Set<String> ALLOWED_ENVIRONMENT_NAMES = Set.of(
            "path", "pathext", "systemroot", "windir", "comspec",
            "userprofile", "home", "homedrive", "homepath",
            "appdata", "localappdata", "programdata", "temp", "tmp",
            "codex_home", "codex_api_key", "codex_access_token", "openai_api_key",
            "ssl_cert_file", "http_proxy", "https_proxy", "no_proxy");

    @Override
    public CodexManagedProcess start(CodexCliProcessSpec processSpec) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(processSpec.command());
        processBuilder.directory(processSpec.workingDirectory().toFile());
        processBuilder.redirectErrorStream(false);
        sanitizeEnvironment(processBuilder.environment());
        return new JavaManagedProcess(processBuilder.start());
    }

    private static void sanitizeEnvironment(Map<String, String> environment) {
        environment.entrySet().removeIf(entry -> !ALLOWED_ENVIRONMENT_NAMES.contains(
                entry.getKey().toLowerCase(Locale.ROOT)));
    }

    private record JavaManagedProcess(Process delegate) implements CodexManagedProcess {

        @Override
        public long pid() {
            return delegate.pid();
        }

        @Override
        public OutputStream standardInput() {
            return delegate.getOutputStream();
        }

        @Override
        public InputStream standardOutput() {
            return delegate.getInputStream();
        }

        @Override
        public InputStream standardError() {
            return delegate.getErrorStream();
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }

        @Override
        public void destroyBeforeDispatch() {
            delegate.destroy();
        }
    }
}
