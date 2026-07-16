package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * 「你自己看著辦」的委任處理(使用者 2026-07-16 裁決 #48):
 * 使用者把決定權交給系統時,只能用**低風險方式**安排——照原時間確認待決提案,
 * 不改時間、不刪資料、不動其他行程——而且**一定要回報**做了什麼、怎麼反悔。
 */
@Service
public class DelegatedDecisionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final ConversationContextService contextService;
    private final ScheduleService scheduleService;

    public DelegatedDecisionService(ConversationContextService contextService,
                                    ScheduleService scheduleService) {
        this.contextService = contextService;
        this.scheduleService = scheduleService;
    }

    /** 對懸而未決的提案採最低風險選項並回報;沒有待決事項就說明沒動任何東西。 */
    public IntentResult decide() {
        Long scheduleId = contextService.scheduleIdAt(null);
        if (scheduleId != null) {
            ScheduleItem item = scheduleService.getSchedule(scheduleId);
            if (item != null && item.getStatus() == ScheduleStatus.PROPOSED) {
                // 低風險=照原時間確認:可隨時再改或取消,不會產生不可回復的變更
                scheduleService.confirmSchedule(scheduleId);
                contextService.rememberSchedule(item);
                return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                        ("交給我 😊 我用最低風險的方式處理:照原時間 %s 確認了「%s」;"
                                + "沒有動其他行程,也沒刪任何東西。\n之後想改時間或取消,一句話就行。")
                                .formatted(format(item), item.getTitle()));
            }
        }
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "目前沒有等待決定的提案,所以我什麼都沒動。\n"
                        + "之後有待確認的行程時跟我說「你決定」,我會用最低風險的方式處理並回報。");
    }

    private static String format(ScheduleItem item) {
        return ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).format(TIME);
    }
}
