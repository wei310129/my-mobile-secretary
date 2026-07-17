package com.aproject.aidriven.mymobilesecretary.intent.application;

/** 將 Java 驗證例外轉成使用者能理解、又足以除錯的安全摘要。 */
final class IntentValidationDiagnostic {

    private IntentValidationDiagnostic() {
    }

    static String code(IllegalArgumentException exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank()) return "INVALID_COMMAND";
        if (message.equals("missing type")) return "MISSING_COMMAND_TYPE";
        if (message.equals("missing title")) return "MISSING_TITLE";
        if (message.equals("schedule missing startAt/endAt")) return "MISSING_SCHEDULE_TIME_RANGE";
        if (message.equals("schedule missing startAt")) return "MISSING_SCHEDULE_START_AT";
        if (message.equals("reschedule missing dueAt")) return "MISSING_TASK_DUE_AT";
        if (message.equals("schedule reschedule missing startAt")) return "MISSING_SCHEDULE_START_AT";
        if (message.equals("daily schedule query missing startAt")) return "MISSING_QUERY_START_AT";
        if (message.equals("recurrenceUntil before first occurrence")) return "INVALID_RECURRENCE_CUTOFF";
        if (message.startsWith("bad time: ")) return "INVALID_TIME_FORMAT";
        if (message.startsWith("missing ")) {
            String field = message.substring("missing ".length())
                    .replaceAll("[^A-Za-z0-9]+", "_")
                    .toUpperCase(java.util.Locale.ROOT);
            return field.isBlank() ? "MISSING_REQUIRED_FIELD" : "MISSING_" + field;
        }
        return "INVALID_COMMAND";
    }

    static String explain(IllegalArgumentException exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Java 拒絕了不完整的 AI 結構化資料";
        }
        if (message.equals("missing type")) {
            return "AI 沒有提供操作種類 type";
        }
        if (message.equals("missing title")) {
            return "AI 沒有提供任務或行程標題 title";
        }
        if (message.startsWith("missing ")) {
            return "AI 沒有提供必填欄位 " + message.substring("missing ".length());
        }
        if (message.startsWith("bad time: ")) {
            return "時間不是含時區的 ISO-8601 格式：" + message.substring("bad time: ".length());
        }
        return switch (message) {
            case "schedule missing startAt/endAt" -> "建立行程必須同時有 startAt 與 endAt";
            case "schedule missing startAt" -> "建立行程必須提供 startAt；只有 endAt 無法判斷開始時間";
            case "reschedule missing dueAt" -> "待辦改期必須提供新的 dueAt";
            case "schedule reschedule missing startAt" -> "行程改期必須提供新的 startAt";
            case "daily schedule query missing startAt" -> "指定日期行程查詢必須提供 startAt";
            case "recurrenceUntil before first occurrence" -> "固定行程截止日不能早於第一次行程";
            default -> "Java 驗證拒絕：" + message;
        };
    }

    static String summarize(IntentCommand command) {
        if (command == null) {
            return "command=null";
        }
        return "type=%s; title=%s; dueAt=%s; startAt=%s; endAt=%s; placeName=%s"
                .formatted(value(command.type()), value(command.title()), value(command.dueAt()),
                        value(command.startAt()), value(command.endAt()), value(command.placeName()));
    }

    private static String value(Object value) {
        if (value == null || value.toString().isBlank()) {
            return "(空)";
        }
        return value.toString().replace('\n', ' ').replace('\r', ' ').strip();
    }
}
