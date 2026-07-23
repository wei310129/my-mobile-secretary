package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.Objects;
import org.springframework.stereotype.Component;

/** Fixed public wording for every typed transition; no model may omit or rewrite these facts. */
@Component
public final class FocusTransitionNoticeRenderer {

    public String render(FocusTransitionNotice notice) {
        Objects.requireNonNull(notice, "notice");
        return switch (notice.type()) {
            case ENTER -> "目前先處理「%s」。".formatted(notice.currentSafeLabel());
            case CHANGE_SUBFOCUS -> "目前仍處理「%s」，先聚焦「%s」。".formatted(
                    notice.currentSafeLabel(), notice.activitySafeLabel());
            case SWITCH -> "先暫離「%s」，改處理「%s」。".formatted(
                    notice.previousSafeLabel(), notice.currentSafeLabel());
            case RESUME -> notice.previousSafeLabel() == null
                    ? "繼續處理「%s」。".formatted(notice.currentSafeLabel())
                    : "先暫離「%s」，回到「%s」。".formatted(
                            notice.previousSafeLabel(), notice.currentSafeLabel());
            case EXIT -> "已離開「%s」，目前沒有正在處理的事項。".formatted(
                    notice.currentSafeLabel());
            case CLOSE -> "已結束這段對話處理：「%s」。".formatted(notice.currentSafeLabel());
            case INVALIDATE -> notice.previousSafeLabel() == null
                    ? "「%s」目前無法再繼續處理。".formatted(notice.currentSafeLabel())
                    : "「%s」目前無法再繼續處理；目前仍在處理「%s」。".formatted(
                            notice.currentSafeLabel(), notice.previousSafeLabel());
        };
    }
}
