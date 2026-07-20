package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandler;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Handles conversational replies that do not mutate task, schedule or knowledge state. */
@Component
@RequiredArgsConstructor
public final class ConversationIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.ASK_ACTIVITY_COUNT,
            IntentCommand.Type.ASK_LAST_ACTIVITY,
            IntentCommand.Type.ASK_STORED_MEDIA,
            IntentCommand.Type.EXPLAIN_LAST_FAILURE,
            IntentCommand.Type.SOCIAL);

    private final ConversationContextService conversationContextService;
    private final ActivityCountAnswerService activityCountAnswerService;
    private final LastActivityAnswerService lastActivityAnswerService;
    private final MediaStorageService mediaStorageService;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case ASK_ACTIVITY_COUNT -> {
                requireText(command.title(), "title");
                yield activityCountAnswerService.answerTopic(
                        command.title(), command.safeOptions().filter());
            }
            case ASK_LAST_ACTIVITY -> {
                requireText(command.title(), "title");
                yield lastActivityAnswerService.answerTopic(
                        command.title(), command.placeName(), command.safeOptions().filter());
            }
            case ASK_STORED_MEDIA -> listStoredMedia(text, command);
            case EXPLAIN_LAST_FAILURE -> FailureExplanationService.answer(
                            text, conversationContextService.snapshot())
                    .orElseGet(() -> IntentResult.message(IntentResult.Action.FAILURE_EXPLAINED,
                            "目前沒有可追查的上一筆解析失敗紀錄。"));
            case SOCIAL -> IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    command.reason() == null || command.reason().isBlank()
                            ? "不客氣,有需要再叫我。"
                            : command.reason());
            default -> throw new IllegalArgumentException(
                    "unsupported conversation intent type " + command.type());
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " missing");
        }
    }

    private IntentResult listStoredMedia(String text, IntentCommand command) {
        if (text != null && (text.contains("刪除") || text.contains("刪掉")
                || text.contains("移除") || text.contains("清空"))) {
            return IntentResult.message(IntentResult.Action.MEDIA_FILES_LISTED,
                    "聊天視窗不支援刪除原始檔案。請到 App 的檔案管理頁確認後刪除；這裡沒有異動任何檔案。");
        }
        var files = mediaStorageService.searchRecent(
                command.title(), command.safeOptions().filter());
        if (files.isEmpty()) {
            return IntentResult.message(IntentResult.Action.MEDIA_FILES_LISTED,
                    "找不到符合條件的已保存原始檔案。");
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
                .withZone(ZoneId.of("Asia/Taipei"));
        String rows = files.stream()
                .map(file -> "- #%d %s｜%s｜/api/media/%d/content".formatted(
                        file.getId(), file.getDisplayName(), formatter.format(file.getCreatedAt()),
                        file.getId()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.MEDIA_FILES_LISTED,
                "找到以下本人私有原始檔案；連結需用 App 登入權限開啟：\n" + rows);
    }
}
