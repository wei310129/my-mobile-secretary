package com.aproject.aidriven.mymobilesecretary.shared.security;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic boundary for text extracted from documents or returned by external tools.
 *
 * <p>This is defense in depth, not a claim that prompt injection can be detected perfectly.
 * Callers must still keep untrusted data separate from system instructions and require normal
 * application authorization/confirmation before a model-selected action is executed.
 */
public final class PromptInjectionGuard {

    private static final List<String> OVERRIDE_MARKERS = List.of(
            "ignorepreviousinstructions", "ignorepriorinstructions",
            "ignoreaboveinstructions", "ignoreallinstructions", "ignoreallpreviousinstructions",
            "ignoreallpriorinstructions", "ignorethepreviousinstructions",
            "disregardpreviousinstructions", "forgetpreviousinstructions",
            "忽略之前的指令", "忽略先前的指令", "忽略上面的指令", "忽略以上規則",
            "忽略之前指令", "忽略先前指令", "忽略上面指令", "忽略所有指令",
            "無視之前的指令", "無視先前的規則", "無視之前指令", "忘記之前規則");
    private static final List<String> SECRET_TARGETS = List.of(
            "systemprompt", "developerprompt", "hiddenprompt", "apikey",
            "password", "accesskey", "secretkey", "系統提示詞", "開發者提示詞",
            "隱藏提示詞", "金鑰", "密碼");
    private static final List<String> DISCLOSURE_ACTIONS = List.of(
            "reveal", "show", "print", "output", "return", "leak", "expose",
            "顯示", "印出", "輸出", "回傳", "洩漏", "揭露");
    private static final List<String> PRIVILEGED_ACTIONS = List.of(
            "executetool", "calltool", "invoketool", "runcommand", "executeshell",
            "runshell", "executesql", "呼叫工具", "執行工具", "執行命令", "執行指令",
            "執行shell", "執行sql");
    private static final List<String> ROLE_OVERRIDE_MARKERS = List.of(
            "youarenowsystem", "youarenowdeveloper", "actassystem", "actasdeveloper",
            "pretendtobesystem", "現在你是系統", "你現在是系統", "扮演系統角色",
            "切換成開發者模式");

    private PromptInjectionGuard() {
    }

    /** Inspects model-extracted external content without retaining or logging the content. */
    public static Inspection inspectExternalContent(Iterable<String> values) {
        List<Signal> signals = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                inspect(value, signals);
            }
        }
        return new Inspection(List.copyOf(signals.stream().distinct().toList()));
    }

    public static Inspection inspectExternalContent(String value) {
        return inspectExternalContent(List.of(value == null ? "" : value));
    }

    /** Places untrusted text in an escaped, explicit data boundary for an LLM prompt. */
    public static String delimit(String label, Object value) {
        if (label == null || !label.matches("[a-z][a-z0-9_]{0,40}")) {
            throw new IllegalArgumentException("invalid prompt boundary label");
        }
        String escaped = String.valueOf(value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return "<" + label + " untrusted=\"true\">" + escaped + "</" + label + ">";
    }

    private static void inspect(String value, List<Signal> signals) {
        if (value == null || value.isBlank()) {
            return;
        }
        String compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\p{P}\\p{S}\\p{C}_]+", "");
        if (containsAny(compact, OVERRIDE_MARKERS)) {
            signals.add(Signal.INSTRUCTION_OVERRIDE);
        }
        if (containsAny(compact, SECRET_TARGETS)
                && containsAny(compact, DISCLOSURE_ACTIONS)) {
            signals.add(Signal.SECRET_DISCLOSURE);
        }
        if (containsAny(compact, PRIVILEGED_ACTIONS)) {
            signals.add(Signal.PRIVILEGED_ACTION_REQUEST);
        }
        if (containsAny(compact, ROLE_OVERRIDE_MARKERS)) {
            signals.add(Signal.ROLE_OVERRIDE);
        }
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }

    public enum Signal {
        INSTRUCTION_OVERRIDE,
        SECRET_DISCLOSURE,
        PRIVILEGED_ACTION_REQUEST,
        ROLE_OVERRIDE
    }

    public record Inspection(List<Signal> signals) {
        public boolean suspicious() {
            return !signals.isEmpty();
        }
    }
}
