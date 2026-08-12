package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoice;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceQuestion;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Single source of truth for Calendar route action labels, effects and accepted answers. */
public final class RouteCalendarChoiceCatalog {

    public static final String CHANGE_TIME = "CHANGE_TIME";
    public static final String ADJUST_SAFE = "ADJUST_SAFE";
    public static final String KEEP_ORIGINAL = "KEEP_ORIGINAL";
    public static final String RETAIN = "RETAIN";
    public static final String DISCARD = "DISCARD";
    public static final String ACCEPT = "ACCEPT";
    public static final String DECLINE = "DECLINE";
    public static final String LOCKED = "LOCKED";
    public static final String WINDOWED = "WINDOWED";
    public static final String FLEXIBLE = "FLEXIBLE";

    private RouteCalendarChoiceCatalog() {}

    public static PublicConversationChoiceQuestion directOverlap() {
        return new PublicConversationChoiceQuestion(
                "route.direct-overlap",
                "請選擇本次行程的處理方式，或直接回覆新的出發時間：",
                List.of(
                        choice(
                                CHANGE_TIME,
                                "調整出發時間",
                                "改用您接著提供的新時間，確認前不建立本次行程",
                                "調整時間",
                                "改時間",
                                "新的出發時間"),
                        choice(
                                KEEP_ORIGINAL,
                                "照原安排保留",
                                "仍按原時間建立，直接重疊風險會保留",
                                "照原安排保留",
                                "保留原安排",
                                "維持原安排")));
    }

    public static PublicConversationChoiceQuestion scheduleConflict() {
        return new PublicConversationChoiceQuestion(
                "route.schedule-conflict",
                "本次行程要怎麼處理？",
                List.of(
                        choice(
                                ADJUST_SAFE,
                                "往後安排到安全時間",
                                "重新查詢並改到可銜接的時間",
                                "往後安排",
                                "安全時間",
                                "調整到安全"),
                        choice(
                                KEEP_ORIGINAL,
                                "照原安排保留",
                                "保留目前時間與已知銜接風險",
                                "照原安排保留",
                                "忽略衝突",
                                "保留原安排")));
    }

    public static PublicConversationChoiceQuestion keepOnlyConflict() {
        return new PublicConversationChoiceQuestion(
                "route.schedule-conflict-keep-only",
                "往後安排無法解決後方銜接限制。要忽略衝突並保留原安排嗎？若不保留，也可以選擇暫不處理。",
                List.of(
                        choice(
                                KEEP_ORIGINAL,
                                "忽略衝突並保留原安排",
                                "維持目前時間與已知銜接風險",
                                "忽略衝突",
                                "保留原安排",
                                "照原安排保留"),
                        choice(
                                DECLINE,
                                "保留目前進度，暫不處理",
                                "不修改已建立行程，稍後仍可繼續",
                                "暫不處理",
                                "先不要",
                                "稍後處理")));
    }

    public static PublicConversationChoiceQuestion providerUnavailable() {
        return new PublicConversationChoiceQuestion(
                "route.provider-unavailable",
                "要保留這份待確認安排，還是放棄？",
                List.of(
                        choice(
                                RETAIN,
                                "保留待確認安排",
                                "保存已確認條件，稍後可重新查路線；目前不建立行程",
                                "保留",
                                "保留安排",
                                "稍後再查"),
                        choice(
                                DISCARD,
                                "放棄待確認安排",
                                "關閉這次未完成規劃，不新增行事曆資料",
                                "不保留",
                                "放棄",
                                "取消")));
    }

    public static PublicConversationChoiceQuestion transportOffer() {
        return yesNo(
                "route.transport-offer",
                "是否要為這個活動規劃交通？",
                "規劃交通方式",
                "加入交通規劃，活動時間維持原樣",
                "不規劃交通",
                "保留活動，不另外加入交通規劃");
    }

    public static PublicConversationChoiceQuestion departureReminder(boolean rideHail) {
        return yesNo(
                "route.departure-reminder",
                rideHail ? "要依照等車時間設定提早叫車提醒嗎？" : "要依照行程長度設定出發提醒嗎？",
                rideHail ? "設定提早叫車提醒" : "設定出發提醒",
                rideHail ? "依等車時間建立提醒" : "依行程長度建立提醒",
                "不設定提醒",
                "行程保留，不新增提醒");
    }

    public static PublicConversationChoiceQuestion activityAdjustability() {
        return new PublicConversationChoiceQuestion(
                "route.activity-adjustability",
                "請選擇活動時間的調整方式：",
                List.of(
                        choice(
                                LOCKED,
                                "活動時間固定",
                                "交通安排不得修改活動時間",
                                "固定",
                                "不能改",
                                "活動時間固定"),
                        choice(
                                WINDOWED,
                                "可在指定時段內調整",
                                "只在已確認的時間範圍內調整，每次變更前仍會再次確認",
                                "時間範圍",
                                "時段內",
                                "範圍內調整"),
                        choice(
                                FLEXIBLE,
                                "可配合交通調整",
                                "每次調整活動時間前仍會再次確認",
                                "可以調整",
                                "可以配合",
                                "彈性")));
    }

    public static Optional<PublicConversationChoiceQuestion> find(
            String questionCode, boolean rideHail) {
        if (questionCode == null) return Optional.empty();
        return Optional.ofNullable(switch (questionCode) {
            case "route.direct-overlap" -> directOverlap();
            case "route.schedule-conflict" -> scheduleConflict();
            case "route.schedule-conflict-keep-only" -> keepOnlyConflict();
            case "route.provider-unavailable" -> providerUnavailable();
            case "route.transport-offer" -> transportOffer();
            case "route.activity-adjustability" -> activityAdjustability();
            case "route.departure-reminder" -> departureReminder(rideHail);
            default -> null;
        });
    }

    private static PublicConversationChoiceQuestion yesNo(
            String code,
            String prompt,
            String acceptLabel,
            String acceptEffect,
            String declineLabel,
            String declineEffect) {
        return new PublicConversationChoiceQuestion(
                code,
                prompt,
                List.of(
                        choice(
                                ACCEPT,
                                acceptLabel,
                                acceptEffect,
                                "要",
                                "好",
                                "可以",
                                "設定"),
                        choice(
                                DECLINE,
                                declineLabel,
                                declineEffect,
                                "不要",
                                "不用",
                                "不需要",
                                "先不用")));
    }

    private static PublicConversationChoice choice(
            String code, String label, String effect, String... acceptedAnswers) {
        return new PublicConversationChoice(code, label, effect, Set.of(acceptedAnswers));
    }
}
