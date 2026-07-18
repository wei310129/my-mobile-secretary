package com.aproject.aidriven.mymobilesecretary.event.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.event.domain.EventIntakeDraft;
import com.aproject.aidriven.mymobilesecretary.event.persistence.EventIntakeDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.CalendarDatePolicy;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 活動文案／圖片後續對話的持久草稿與逐欄澄清流程。 */
@Service
@Transactional
public class EventIntakeService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Duration RETENTION = Duration.ofDays(14);
    private static final Pattern DATE = Pattern.compile(
            "(?<year>20\\d{2})\\s*(?:年|[./-])\\s*(?<month>\\d{1,2})\\s*"
                    + "(?:月|[./-])\\s*(?<day>\\d{1,2})\\s*日?");
    private static final Pattern PLACE = Pattern.compile(
            "(?:活動地點|會議地點|地點)\\s*[：:]\\s*(?<place>[^\\n\\r，。]{2,100})");
    private static final Pattern JCCONF = Pattern.compile("(?i)(JCConf\\s*20\\d{2})");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?<startHour>\\d{1,2})(?::(?<startMinute>\\d{2}))?\\s*"
                    + "(?:[-–~～]|到|至)\\s*(?<endHour>\\d{1,2})(?::(?<endMinute>\\d{2}))?");
    private static final Pattern MINUTES = Pattern.compile("(?<minutes>\\d{1,3})\\s*分鐘");

    private final EventIntakeDraftRepository repository;
    private final ObjectMapper objectMapper;
    private final ScheduleService scheduleService;
    private final PlaceService placeService;
    private final TaskService taskService;
    private final Clock clock;

    public EventIntakeService(EventIntakeDraftRepository repository, ObjectMapper objectMapper,
                              ScheduleService scheduleService, PlaceService placeService,
                              TaskService taskService, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.scheduleService = scheduleService;
        this.placeService = placeService;
        this.taskService = taskService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (looksLikeAnnouncement(text)) {
            DraftPayload payload = parseAnnouncement(text);
            beforeMutation.run();
            repository.save(EventIntakeDraft.create(payload.title(), write(payload),
                    Instant.now(clock).plus(RETENTION), Instant.now(clock)));
            return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    preview(payload, false)
                            + "\n\n我先保存為活動草稿，尚未建立行程。若已訂票，請說「幫我加入行程」。"));
        }

        Optional<EventIntakeDraft> latest = latestPending();
        if (latest.isEmpty() || !isEventFollowUp(text, read(latest.get()))) {
            return Optional.empty();
        }
        EventIntakeDraft draft = latest.get();
        DraftPayload payload = read(draft);

        if (containsAny(compact(text), "自己去查", "自己查", "查JCConf", "查一下活動")) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "我會延續活動草稿「%s」，不會叫你重講日期與地點。請貼官方活動網頁；目前專案尚未接上可驗證來源的活動搜尋介面，"
                            .formatted(payload.title())
                            + "因此不會假裝已查到。接上低成本查詢模型後仍要附來源，且查到的時間只會先寫入草稿供你確認。"));
        }

        DraftPayload updated = updateFromFollowUp(text, payload);
        if (!updated.equals(payload)) {
            beforeMutation.run();
            draft.replace(updated.title(), write(updated), Instant.now(clock));
        }
        if (isConfirm(text)) {
            return Optional.of(confirm(draft, updated, beforeMutation));
        }
        return Optional.of(IntentResult.clarificationNeeded(nextQuestion(updated)));
    }

    public IntentResult ingestImageEvent(String title, String date, String startTime,
                                         String endTime, String placeName, String source,
                                         Runnable beforeMutation) {
        DraftPayload payload = new DraftPayload(cleanTitle(title), parseDate(date),
                parseTime(startTime), parseTime(endTime), clean(placeName), null,
                null, null, null, false, "IMAGE", source);
        beforeMutation.run();
        repository.save(EventIntakeDraft.create(payload.title(), write(payload),
                Instant.now(clock).plus(RETENTION), Instant.now(clock)));
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                preview(payload, false) + "\n\n圖片內容已保存為活動草稿，尚未建立行程。"
                        + "若要加入，請說「幫我加入行程」。");
    }

    private IntentResult confirm(EventIntakeDraft draft, DraftPayload payload,
                                 Runnable beforeMutation) {
        String missing = firstMissing(payload);
        if (missing != null) {
            return IntentResult.clarificationNeeded(nextQuestion(payload));
        }
        Instant start = ZonedDateTime.of(payload.date(), payload.startTime(), TAIPEI).toInstant();
        Instant end = ZonedDateTime.of(payload.date(), payload.endTime(), TAIPEI).toInstant();
        boolean duplicate = scheduleService.listSchedules(null).stream()
                .anyMatch(item -> item.getTitle().equalsIgnoreCase(payload.title())
                        && item.getStartAt().equals(start));
        if (duplicate) {
            beforeMutation.run();
            draft.confirm(Instant.now(clock));
            return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_STATUS,
                    "行程「%s」已存在於 %s %s–%s，不會重複建立。"
                            .formatted(payload.title(), CalendarDatePolicy.format(payload.date()),
                                    payload.startTime(), payload.endTime()));
        }

        beforeMutation.run();
        Long placeId = resolveOrCreatePlace(payload.placeName()).map(Place::getId).orElse(null);
        var decision = scheduleService.createSchedule(payload.title(), start, end, placeId,
                ScheduleItem.Recurrence.NONE);
        if (payload.reminderLeadMinutes() != null && payload.reminderLeadMinutes() > 0) {
            taskService.createTask("提醒:" + payload.title(), "活動開始前提醒",
                    TaskPriority.NORMAL, start.minusSeconds(payload.reminderLeadMinutes() * 60L));
        }
        if (payload.preparation() != null && !"無".equals(payload.preparation())) {
            Instant due = start.minusSeconds(Math.max(60, payload.arrivalLeadMinutes()) * 60L);
            taskService.createTask(payload.preparation(), "「%s」行前準備".formatted(payload.title()),
                    TaskPriority.NORMAL, due, Task.Category.OTHER,
                    Task.Recurrence.NONE, Task.ConditionType.NONE, null);
        }
        draft.confirm(Instant.now(clock));
        return IntentResult.message(IntentResult.Action.BATCH_EXECUTED,
                "已依確認資料建立活動「%s」：\n%s\n- 地點｜%s\n- 提早到場｜%d 分鐘\n- 提醒｜開始前 %d 分鐘\n- 行前準備｜%s\n- 行程狀態｜%s"
                        .formatted(payload.title(),
                                CalendarDatePolicy.format(payload.date()) + " "
                                        + payload.startTime() + "–" + payload.endTime(),
                                payload.placeName(), payload.arrivalLeadMinutes(),
                                payload.reminderLeadMinutes(), payload.preparation(),
                                decision.item().getStatus()));
    }

    private DraftPayload updateFromFollowUp(String text, DraftPayload payload) {
        String compact = compact(text);
        boolean addRequested = payload.addRequested()
                || containsAny(compact, "加入行程", "加到行程", "訂好票", "買好票");
        LocalTime start = payload.startTime();
        LocalTime end = payload.endTime();
        if (!compact.contains("售票")) {
            Matcher range = TIME_RANGE.matcher(text);
            if (range.find()) {
                start = time(range, "startHour", "startMinute");
                end = time(range, "endHour", "endMinute");
            }
        }
        Boolean placeConfirmed = payload.placeConfirmed();
        Integer arrival = payload.arrivalLeadMinutes();
        Integer reminder = payload.reminderLeadMinutes();
        String preparation = payload.preparation();
        String missing = firstMissing(payload);
        if ("PLACE".equals(missing) && isYes(compact)) placeConfirmed = true;
        if ("ARRIVAL".equals(missing)) {
            arrival = containsAny(compact, "不用提早", "不提早", "準時到")
                    ? 0 : mentionedMinutes(text).orElse(arrival);
        }
        if ("REMINDER".equals(missing)) {
            reminder = containsAny(compact, "不用提醒", "不提醒")
                    ? 0 : mentionedMinutes(text).orElse(reminder);
        }
        if ("PREPARATION".equals(missing)) {
            if (containsAny(compact, "沒有", "不用準備", "無")) preparation = "無";
            else if (text.length() <= 160) preparation = text.strip();
        }
        return new DraftPayload(payload.title(), payload.date(), start, end,
                payload.placeName(), placeConfirmed, arrival, reminder, preparation,
                addRequested, payload.sourceType(), payload.sourceText());
    }

    private String nextQuestion(DraftPayload payload) {
        String base = "活動草稿「%s」已保留：%s。".formatted(payload.title(), known(payload));
        return switch (firstMissing(payload)) {
            case "DATE" -> base + "\n請告訴我活動日期；也可以貼官方活動網頁供後續查證。";
            case "TIME" -> base + "\n活動日期已經有了，但缺少實際開始與結束時間。請直接告訴我時間、貼官方活動網頁，或明確說要我查；確認前不會建立行程。";
            case "PLACE" -> base + "\n活動地點是「%s」嗎？請回覆「地點正確」或提供更精確地址。"
                    .formatted(payload.placeName());
            case "ARRIVAL" -> base + "\n需要提早到場嗎？請告訴我提早幾分鐘，或回覆「不用提早」。";
            case "REMINDER" -> base + "\n需要在活動開始前多久提醒？請說分鐘數，或回覆「不用提醒」。";
            case "PREPARATION" -> base + "\n有沒有行前準備要一併建立待辦？請說明內容，沒有就回覆「沒有行前準備」。";
            default -> preview(payload, true) + "\n\n資料已補齊；請回覆「確認建立活動行程」。";
        };
    }

    private static String firstMissing(DraftPayload payload) {
        if (payload.date() == null) return "DATE";
        if (payload.startTime() == null || payload.endTime() == null) return "TIME";
        if (payload.placeName() != null && payload.placeConfirmed() == null) return "PLACE";
        if (payload.arrivalLeadMinutes() == null) return "ARRIVAL";
        if (payload.reminderLeadMinutes() == null) return "REMINDER";
        if (payload.preparation() == null) return "PREPARATION";
        return null;
    }

    private Optional<EventIntakeDraft> latestPending() {
        var scope = WorkspaceContextHolder.requireContext();
        return repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                scope.actorId(), EventIntakeDraft.Status.PENDING, Instant.now(clock));
    }

    private Optional<Place> resolveOrCreatePlace(String placeName) {
        if (placeName == null || placeName.isBlank()) return Optional.empty();
        Optional<Place> known = placeService.listPlaces().stream()
                .filter(place -> place.getName().equalsIgnoreCase(placeName.strip()))
                .findFirst();
        return known.isPresent() ? known
                : Optional.of(placeService.createPlace(placeName, null, null, null, null));
    }

    static DraftPayload parseAnnouncement(String text) {
        Matcher name = JCCONF.matcher(text == null ? "" : text);
        String title = name.find() ? name.group(1).replaceAll("\\s+", " ") : firstTitleLine(text);
        Matcher date = DATE.matcher(text == null ? "" : text);
        LocalDate eventDate = date.find() ? LocalDate.of(
                Integer.parseInt(date.group("year")), Integer.parseInt(date.group("month")),
                Integer.parseInt(date.group("day"))) : null;
        Matcher place = PLACE.matcher(text == null ? "" : text);
        String placeName = place.find() ? clean(place.group("place")) : null;
        LocalTime start = null;
        LocalTime end = null;
        for (String line : (text == null ? "" : text).split("\\R")) {
            String compact = compact(line);
            if (!containsAny(compact, "活動時間", "會議時間", "研討會時間")) continue;
            Matcher range = TIME_RANGE.matcher(line);
            if (range.find()) {
                start = time(range, "startHour", "startMinute");
                end = time(range, "endHour", "endMinute");
            }
        }
        return new DraftPayload(cleanTitle(title), eventDate, start, end, placeName, null,
                null, null, null, false, "TEXT", text);
    }

    private static boolean looksLikeAnnouncement(String text) {
        String compact = compact(text);
        return (compact.contains("活動日期") && compact.contains("活動地點"))
                || (compact.contains("門票") && DATE.matcher(text == null ? "" : text).find()
                        && compact.contains("地點"));
    }

    private static boolean isEventFollowUp(String text, DraftPayload payload) {
        String compact = compact(text);
        if (text != null && text.length() >= 180
                && containsAny(compact, "使用者", "底層", "例如")) {
            return false;
        }
        return payload.addRequested() || containsAny(compact,
                "活動", "行程", "訂好票", "買好票", "JCConf", "時間", "地點", "提醒",
                "提早", "準備", "確認建立", "網頁", "查JCConf", "查活動", "明明很清楚");
    }

    private static boolean isConfirm(String text) {
        String compact = compact(text);
        return containsAny(compact, "確認建立活動行程", "確認加入行程", "確認建立行程");
    }

    private static boolean isYes(String compact) {
        return containsAny(compact, "地點正確", "對", "正確", "沒錯", "是");
    }

    private static Optional<Integer> mentionedMinutes(String text) {
        Matcher matcher = MINUTES.matcher(text == null ? "" : text);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group("minutes")))
                : Optional.empty();
    }

    private static String known(DraftPayload p) {
        return "日期 %s、時間 %s、地點 %s".formatted(
                p.date() == null ? "待補" : CalendarDatePolicy.format(p.date()),
                p.startTime() == null || p.endTime() == null
                        ? "待補" : p.startTime() + "–" + p.endTime(),
                p.placeName() == null ? "待補" : p.placeName());
    }

    private static String preview(DraftPayload p, boolean complete) {
        return (complete ? "活動行程確認：" : "辨識到活動資料：")
                + "\n- 活動｜" + p.title()
                + "\n- 日期｜" + (p.date() == null ? "待補" : CalendarDatePolicy.format(p.date()))
                + "\n- 時間｜" + (p.startTime() == null || p.endTime() == null
                        ? "待補" : p.startTime() + "–" + p.endTime())
                + "\n- 地點｜" + (p.placeName() == null ? "待補" : p.placeName());
    }

    private DraftPayload read(EventIntakeDraft draft) {
        try {
            return objectMapper.readValue(draft.getPayload(), DraftPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored event draft is invalid", exception);
        }
    }

    private String write(DraftPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event draft cannot be serialized", exception);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return value == null ? null : LocalTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalTime time(Matcher matcher, String hour, String minute) {
        return LocalTime.of(Integer.parseInt(matcher.group(hour)),
                matcher.group(minute) == null ? 0 : Integer.parseInt(matcher.group(minute)));
    }

    private static String firstTitleLine(String text) {
        if (text == null) return "未命名活動";
        return java.util.Arrays.stream(text.split("\\R"))
                .map(EventIntakeService::clean).filter(line -> line != null && !line.isBlank())
                .findFirst().orElse("未命名活動");
    }

    private static String cleanTitle(String value) {
        String cleaned = clean(value);
        if (cleaned == null || cleaned.isBlank()) return "未命名活動";
        cleaned = cleaned.replaceAll("^[^\\p{L}\\p{N}]+", "")
                .replaceAll("[^\\p{L}\\p{N}）)]*$", "").strip();
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160);
    }

    private static String clean(String value) {
        return value == null ? null : value.strip().replaceAll("[，。；;]+$", "");
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s，。！？!?：:；;]", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    public record DraftPayload(
            String title,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            String placeName,
            Boolean placeConfirmed,
            Integer arrivalLeadMinutes,
            Integer reminderLeadMinutes,
            String preparation,
            boolean addRequested,
            String sourceType,
            String sourceText
    ) {
    }
}
