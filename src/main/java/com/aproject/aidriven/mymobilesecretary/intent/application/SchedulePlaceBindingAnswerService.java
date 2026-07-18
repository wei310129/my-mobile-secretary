package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 直接查證行程是否真的綁到地點，避免把更正問句誤記成一般回饋。 */
@Service
public class SchedulePlaceBindingAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ScheduleService scheduleService;
    private final PlaceService placeService;
    private final ConversationContextService contextService;

    public SchedulePlaceBindingAnswerService(ScheduleService scheduleService,
                                             PlaceService placeService,
                                             ConversationContextService contextService) {
        this.scheduleService = scheduleService;
        this.placeService = placeService;
        this.contextService = contextService;
    }

    public Optional<IntentResult> answer(String text) {
        if (!isBindingQuestion(text)) return Optional.empty();

        List<ScheduleItem> schedules = scheduleService.listSchedules(null).stream()
                .sorted(Comparator.comparing(ScheduleItem::getStartAt))
                .toList();
        ScheduleItem target = explicitlyMentioned(text, schedules).orElse(null);
        if (target == null) {
            Long contextId = contextService.scheduleIdAt(null);
            if (contextId != null) {
                target = scheduleService.getSchedule(contextId);
            }
        }
        if (target == null && schedules.size() == 1) {
            target = schedules.getFirst();
        }
        if (target == null) {
            return Optional.of(clarifyTarget(schedules));
        }
        return Optional.of(bindingStatus(target));
    }

    private IntentResult bindingStatus(ScheduleItem item) {
        String schedule = "行程「%s」%s".formatted(item.getTitle(), interval(item));
        if (item.getPlaceId() == null) {
            return IntentResult.message(IntentResult.Action.SCHEDULE_INFO,
                    "查過實際儲存資料：%s目前尚未綁定任何地點。請告訴我地點名稱；確認前我不會自行猜測或綁定。"
                            .formatted(schedule));
        }
        Place place = placeService.getPlace(item.getPlaceId());
        String address = place.getAddress() == null || place.getAddress().isBlank()
                ? "尚無可讀地址" : place.getAddress();
        return IntentResult.message(IntentResult.Action.SCHEDULE_INFO,
                "查過實際儲存資料：%s已綁定地點「%s」。\n- 地址｜%s\n- 地點 ID｜%d"
                        .formatted(schedule, place.getName(), address, place.getId()));
    }

    private IntentResult clarifyTarget(List<ScheduleItem> schedules) {
        if (schedules.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "目前找不到可查證的近期行程。請告訴我行程名稱，我再檢查是否正確綁定地點。");
        }
        StringBuilder message = new StringBuilder("你是要查哪一個行程是否綁定地點？請回覆編號或名稱：");
        for (int i = 0; i < Math.min(5, schedules.size()); i++) {
            ScheduleItem item = schedules.get(i);
            message.append("\n").append(i + 1).append(".「").append(item.getTitle())
                    .append("」").append(interval(item));
        }
        message.append("\n確認前不會新增或修改地點綁定。");
        return IntentResult.clarificationNeeded(message.toString());
    }

    private static Optional<ScheduleItem> explicitlyMentioned(
            String text, List<ScheduleItem> schedules) {
        String compact = compact(text);
        return schedules.stream()
                .filter(item -> compact.contains(compact(item.getTitle())))
                .max(Comparator.comparingInt(item -> compact(item.getTitle()).length()));
    }

    private static boolean isBindingQuestion(String text) {
        String compact = compact(text);
        if (!compact.contains("行程") && !compact.contains("活動")) return false;
        boolean place = compact.contains("地點") || compact.contains("位置");
        boolean binding = compact.contains("綁") || compact.contains("連結")
                || compact.contains("設定") || compact.contains("正確");
        boolean question = compact.contains("有沒有") || compact.contains("是否")
                || compact.contains("嗎") || compact.contains("哪裡")
                || compact.contains("什麼") || compact.contains("查");
        return place && binding && question;
    }

    private static String interval(ScheduleItem item) {
        ZonedDateTime start = ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI);
        ZonedDateTime end = ZonedDateTime.ofInstant(item.getEndAt(), TAIPEI);
        return "（%s %s–%s）".formatted(CalendarDatePolicy.format(start.toLocalDate()),
                start.format(TIME), end.format(TIME));
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s，。！？!?：:；;]", "");
    }
}
