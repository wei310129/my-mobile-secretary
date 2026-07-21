package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.domain.SchoolTransportDraft;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.SchoolTransportDraftRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以可持久草稿承接「孩子固定上課 + 本人送去／接回」。
 *
 * <p>課程時段不會誤算成使用者整段忙碌；接送必須有明確起訖才建立，
 * 不用一小時等假設補值。每輪只問還缺的欄位，不把已回答的資訊丟回給 LLM。</p>
 */
@Service
@Transactional
public class SchoolTransportConversationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Pattern WEEKDAY = Pattern.compile("(?:每個?)?(?:週|周|星期|禮拜)([一二三四五六日天])");
    private static final Pattern RANGE = Pattern.compile(
            "(?<sh>\\d{1,2})(?::(?<sm>\\d{2}))?(?:點)?\\s*(?:-|~|～|到|至)\\s*"
                    + "(?<eh>\\d{1,2})(?::(?<em>\\d{2}))?點?");
    private static final Pattern NUMERIC_TIME = Pattern.compile("(\\d{1,2})(?::([0-5]\\d)|點(半|[0-5]?\\d分?))?");

    private final SchoolTransportDraftRepository repository;
    private final ScheduleService scheduleService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private ConversationContextService conversationContext;

    public SchoolTransportConversationService(SchoolTransportDraftRepository repository,
                                              ScheduleService scheduleService,
                                              ObjectMapper objectMapper,
                                              Clock clock) {
        this.repository = repository;
        this.scheduleService = scheduleService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConversationContext(ConversationContextService conversationContext) {
        this.conversationContext = conversationContext;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String original = text == null ? "" : text.strip();
        if (original.isBlank() || looksLikeMeta(original)) return Optional.empty();
        Instant now = Instant.now(clock);
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        SchoolTransportDraft draft = repository
                .findFirstByWorkspaceIdAndCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        scope.workspaceId(), scope.actorId(), SchoolTransportDraft.Status.PENDING, now)
                .orElse(null);

        Payload payload;
        if (draft == null) {
            payload = initialPayload(original);
            if (payload == null) payload = existingSchedulePayload(original);
            if (payload == null) return Optional.empty();
            beforeMutation.run();
            draft = repository.save(SchoolTransportDraft.create(
                    payload.child() + "上" + payload.course(), write(payload), now.plus(RETENTION), now));
        } else {
            if (!looksLikeFollowUp(original)) return Optional.empty();
            payload = update(read(draft), original);
            beforeMutation.run();
            draft.replace(payload.child() + "上" + payload.course(), write(payload), now);
        }

        List<String> missing = missing(payload);
        if (!missing.isEmpty()) {
            return Optional.of(IntentResult.clarificationNeeded(pendingMessage(payload, missing)));
        }

        if (existingFlow(payload)) {
            draft.complete(now);
            return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    "相同的固定上課與接送流程已存在；我沒有重複建立。"));
        }
        IntentResult result = createSchedules(payload);
        draft.complete(now);
        return Optional.of(result);
    }

    private Payload initialPayload(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (!containsAny(compact, "女兒", "兒子", "孩子", "小孩")
                || !containsAny(compact, "上課", "英語", "補習", "安親", "才藝")
                || !compact.contains("每")) return null;
        Matcher weekday = WEEKDAY.matcher(compact);
        Matcher range = RANGE.matcher(compact);
        if (!weekday.find() || !range.find()) return null;

        String child = firstContained(compact, "大女兒", "小女兒", "女兒", "大兒子", "小兒子", "兒子", "孩子", "小孩");
        String course = course(text);
        if (course == null) return null;
        LocalTime start = time(range.group("sh"), range.group("sm"));
        LocalTime end = time(range.group("eh"), range.group("em"));
        LocalDate until = recurrenceUntil(compact);
        String dropPerson = mentionsSelfDropOff(compact) ? "我" : null;
        String pickupPerson = mentionsSelfPickup(compact) ? "我" : null;
        String dropOrigin = match(compact, Pattern.compile("從([^，,。；;]{1,24})出發"), 1);
        LocalTime dropStart = timeNear(text, "出發");
        String pickupLocation = pickupLocation(text);
        LocalTime pickupEnd = endingTime(text, end);
        return new Payload(child, course, weekday(weekday.group(1)), start, end, until,
                dropPerson, dropOrigin, dropStart, pickupPerson, pickupLocation, pickupEnd,
                null, null);
    }

    private Payload existingSchedulePayload(String text) {
        if (conversationContext == null || !looksLikeTransportContinuation(text)) return null;
        Long scheduleId = conversationContext.snapshot().lastScheduleId();
        if (scheduleId == null) return null;
        ScheduleItem source = scheduleService.getSchedule(scheduleId);
        if (source.getStatus() == com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.CANCELED
                || source.getStatus() == com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.REJECTED
                || source.getStatus() == com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.COMPLETED) {
            return null;
        }
        String child = firstContained(source.getTitle(),
                "大女兒", "小女兒", "女兒", "大兒子", "小兒子", "兒子", "孩子", "小孩");
        String course = courseFromSchedule(source.getTitle(), child);
        if (course == null) return null;
        var start = source.getStartAt().atZone(TAIPEI);
        var end = source.getEndAt().atZone(TAIPEI);
        String compact = text.replaceAll("\\s+", "");
        return new Payload(child, course, start.getDayOfWeek(), start.toLocalTime(),
                end.toLocalTime(), source.getRecurrenceUntil(),
                mentionsSelfDropOff(compact) ? "我" : null, null, timeNear(text, "出發"),
                mentionsSelfPickup(compact) ? "我" : null, pickupLocation(text),
                endingTime(text, end.toLocalTime()), source.getId(), source.getTitle());
    }

    private Payload update(Payload current, String text) {
        String compact = text.replaceAll("\\s+", "");
        String dropPerson = current.dropPerson();
        String dropOrigin = current.dropOrigin();
        LocalTime dropStart = current.dropStart();
        String pickupPerson = current.pickupPerson();
        String pickupLocation = current.pickupLocation();
        LocalTime pickupEnd = current.pickupEnd();

        if (mentionsSelfDropOff(compact)) dropPerson = "我";
        if (mentionsSelfPickup(compact)) pickupPerson = "我";
        String foundOrigin = match(compact, Pattern.compile("從([^，,。；;]{1,24})出發"), 1);
        if (foundOrigin != null) dropOrigin = foundOrigin;
        LocalTime foundStart = timeNear(text, "出發");
        if (foundStart != null) dropStart = foundStart;
        String foundPickup = pickupLocation(text);
        if (foundPickup != null) pickupLocation = foundPickup;
        LocalTime foundEnd = endingTime(text, current.end());
        if (foundEnd != null) pickupEnd = foundEnd;
        return new Payload(current.child(), current.course(), current.weekday(),
                current.start(), current.end(), current.until(), dropPerson, dropOrigin,
                dropStart, pickupPerson, pickupLocation, pickupEnd,
                current.sourceScheduleId(), current.sourceScheduleTitle());
    }

    private IntentResult createSchedules(Payload payload) {
        LocalDate firstDate = nextOrSame(payload.weekday());
        Instant courseStart = at(firstDate, payload.start());
        Instant courseEnd = at(firstDate, payload.end());
        ScheduleDecision course = payload.sourceScheduleId() == null
                ? scheduleService.createFamilySchedule(
                        payload.child() + "上" + payload.course(), courseStart, courseEnd, null,
                        payload.child(), ScheduleItem.Recurrence.WEEKLY, payload.until())
                : new ScheduleDecision(scheduleService.getSchedule(payload.sourceScheduleId()), null);
        ScheduleDecision drop = scheduleService.createSchedule(
                "送" + payload.child() + "到" + payload.course(), at(firstDate, payload.dropStart()),
                courseStart, null, ScheduleItem.Recurrence.WEEKLY, payload.until(),
                ScheduleItem.Category.FAMILY);
        ScheduleDecision pickup = relatedPickup(payload, courseEnd).map(
                        item -> new ScheduleDecision(item, null))
                .orElseGet(() -> scheduleService.createSchedule(
                        "從" + payload.pickupLocation() + "接" + payload.child(), courseEnd,
                        at(firstDate, payload.pickupEnd()), null, ScheduleItem.Recurrence.WEEKLY,
                        payload.until(), ScheduleItem.Category.FAMILY));

        String message = "已建立固定上課與接送流程：\n"
                + "1. %s %s–%s｜負責人：%s｜%s\n\n"
                + "2. %s %s–%s｜從%s出發｜%s\n\n"
                + "3. %s %s–%s｜負責人：%s｜%s\n\n"
                + "三筆都是每週%s，截止日 %s；課程時段不會算成你整段忙碌。";
        return IntentResult.message(IntentResult.Action.BATCH_EXECUTED, message.formatted(
                course.item().getTitle(), payload.start(), payload.end(), payload.child(), status(course),
                drop.item().getTitle(), payload.dropStart(), payload.start(), payload.dropOrigin(), status(drop),
                pickup.item().getTitle(), payload.end(), payload.pickupEnd(), payload.pickupPerson(), status(pickup),
                chineseWeekday(payload.weekday()), payload.until()));
    }

    private boolean existingFlow(Payload payload) {
        LocalDate date = nextOrSame(payload.weekday());
        boolean courseExists = payload.sourceScheduleId() != null
                || hasSchedule(payload.child() + "上" + payload.course(), date,
                        payload.start(), payload.end(), payload.until());
        return courseExists
                && hasSchedule("送" + payload.child() + "到" + payload.course(), date,
                        payload.dropStart(), payload.start(), payload.until())
                && relatedPickup(payload, at(date, payload.end())).isPresent();
    }

    private Optional<ScheduleItem> relatedPickup(Payload payload, Instant pickupStart) {
        String child = normalize(payload.child());
        return scheduleService.listSchedules(null).stream()
                .filter(item -> item.getStatus()
                        != com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.CANCELED)
                .filter(item -> item.getStatus()
                        != com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.REJECTED)
                .filter(item -> item.getStartAt().equals(pickupStart))
                .filter(item -> normalize(item.getTitle()).contains("接" + child))
                .findFirst();
    }

    private boolean hasSchedule(String title, LocalDate date, LocalTime start, LocalTime end,
                                LocalDate until) {
        String wanted = normalize(title);
        return scheduleService.listSchedules(null).stream()
                .filter(item -> item.getRecurrence() == ScheduleItem.Recurrence.WEEKLY)
                .filter(item -> normalize(item.getTitle()).equals(wanted))
                .filter(item -> item.getStartAt().atZone(TAIPEI).toLocalDate().equals(date))
                .filter(item -> item.getStartAt().atZone(TAIPEI).toLocalTime().equals(start))
                .filter(item -> item.getEndAt().atZone(TAIPEI).toLocalTime().equals(end))
                .anyMatch(item -> java.util.Objects.equals(item.getRecurrenceUntil(), until));
    }

    private static String pendingMessage(Payload payload, List<String> missing) {
        String prefix = payload.sourceScheduleTitle() == null
                ? "我已記住這筆固定接送：\n"
                : "我指的是行程「%s」。以下是目前已知的接送設定：\n"
                        .formatted(payload.sourceScheduleTitle());
        StringBuilder message = new StringBuilder(prefix)
                .append("- 課程：").append(payload.child()).append("上").append(payload.course())
                .append("｜每週").append(chineseWeekday(payload.weekday()))
                .append(" ").append(payload.start()).append("–").append(payload.end())
                .append("｜至 ").append(payload.until()).append("\n")
                .append("- 送去：").append(value(payload.dropPerson()))
                .append("｜從").append(value(payload.dropOrigin())).append("出發｜")
                .append(value(payload.dropStart())).append("–").append(payload.start()).append("\n")
                .append("- 接回：").append(value(payload.pickupPerson()))
                .append("｜").append(payload.end()).append("從")
                .append(value(payload.pickupLocation())).append("接｜至").append(value(payload.pickupEnd()))
                .append("\n\n還缺：");
        for (int i = 0; i < missing.size(); i++) {
            message.append("\n").append(i + 1).append(". ").append(missing.get(i));
        }
        message.append("\n\n可以一次回覆，例如：「9:30 從我家出發，我在夏恩英語接，12:30 結束」。");
        return message.toString();
    }

    private static List<String> missing(Payload payload) {
        List<String> missing = new ArrayList<>();
        if (payload.dropPerson() == null) missing.add("誰負責送去？");
        if (payload.dropOrigin() == null) missing.add("送去從哪裡出發？");
        if (payload.dropStart() == null) missing.add("送去預計幾點出發？");
        if (payload.pickupPerson() == null) missing.add("誰負責接回？");
        if (payload.pickupLocation() == null) missing.add(payload.end() + " 從哪裡接？");
        if (payload.pickupEnd() == null) missing.add("接回行程預計幾點結束？");
        return List.copyOf(missing);
    }

    private LocalDate nextOrSame(DayOfWeek weekday) {
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        return today.with(TemporalAdjusters.nextOrSame(weekday));
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TAIPEI).toInstant();
    }

    private static String status(ScheduleDecision decision) {
        return switch (decision.item().getStatus()) {
            case CONFIRMED -> "已確認";
            case PROPOSED, PENDING -> "待確認";
            default -> decision.item().getStatus().name();
        };
    }

    private static boolean looksLikeFollowUp(String text) {
        String compact = text.replaceAll("\\s+", "");
        return containsAny(compact, "出發", "我送", "由我送", "我接", "我去接", "由我接",
                "有我去接", "結束", "忙到", "接回", "在夏恩", "夏恩英語接",
                "接送", "我會送", "也會去接", "送跟接", "送和接", "送、接",
                "誰送", "誰接", "哪一個行程", "哪個行程", "指哪一個");
    }

    private static boolean looksLikeTransportContinuation(String text) {
        String compact = text.replaceAll("\\s+", "");
        return containsAny(compact, "接送", "我送", "由我送", "我接", "由我接", "誰送", "誰接",
                "我會送", "也會去接", "送跟接", "送和接", "送、接", "接回", "接的地點",
                "接人的地方", "哪一個行程", "哪個行程", "指哪一個");
    }

    private static boolean looksLikeMeta(String text) {
        return containsAny(text, "功能改善", "開發需求", "格式不對", "你把你的邏輯", "使用者說");
    }

    private static boolean mentionsSelfDropOff(String text) {
        return containsAny(text, "我送", "由我送", "我負責送")
                || containsAny(text, "我負責來回接送", "我來回接送", "送跟接都是我", "送和接都是我",
                        "送、接都是我")
                || Pattern.compile("我[^，,。；;]{0,30}送(?:她|他|女兒|兒子|孩子|小孩)")
                        .matcher(text).find()
                || (text.contains("我會負責") && text.contains("出發") && text.contains("到"));
    }

    private static boolean mentionsSelfPickup(String text) {
        return containsAny(text, "我接", "我去接", "由我接", "有我去接", "我負責接", "也負責接")
                || containsAny(text, "我負責來回接送", "我來回接送", "送跟接都是我", "送和接都是我",
                        "送、接都是我", "也會去接")
                || Pattern.compile("我(?:去|到|在)[^，,。；;]{1,30}接").matcher(text).find()
                || (text.contains("也要負責去接"));
    }

    private static String course(String text) {
        String value = match(text, Pattern.compile("(?:去上|要上)([^，,。；;]{2,60})"), 1);
        if (value == null) value = match(text, Pattern.compile("在([^，,。；;]{2,60})上課"), 1);
        return clean(value);
    }

    private static String pickupLocation(String text) {
        String compact = text.replaceAll("\\s+", "");
        String value = match(compact, Pattern.compile("(?:就)?在([^，,。；;]{2,40}?)(?:去)?接(?:回)?(?:孩子|小孩|女兒|兒子)?(?:$|[，,。；;])"), 1);
        if (value == null) value = match(compact,
                Pattern.compile("接(?:回)?的?地點(?:也)?是(?:在)?([^，,。；;]{2,40})(?:$|[，,。；;])"), 1);
        if (value == null) value = match(compact,
                Pattern.compile("接(?:人|她|他)?的?(?:地點|地方)(?:也)?是?(?:在)?([^，,。；;]{2,40})(?:$|[，,。；;])"), 1);
        if (value == null) value = match(compact, Pattern.compile("從([^，,。；;]{2,40})接回"), 1);
        if (value == null) value = match(compact, Pattern.compile("(?:我)?(?:去|到)([^，,。；;]{2,40}?)(?:去)?接(?:回)?"), 1);
        if (value == null) value = match(compact,
                Pattern.compile("(夏恩英語(?:[（(][^）)]{1,30}[）)])?)接"), 1);
        return clean(value);
    }

    private static LocalTime timeNear(String text, String marker) {
        String compact = text.replaceAll("\\s+", "");
        String between = "出發".equals(marker) ? "(?:從[^，,。；;]{1,24})?" : "";
        Matcher matcher = Pattern.compile("(\\d{1,2}(?::[0-5]\\d|點(?:半|[0-5]?\\d分?)?)?)" + between + marker)
                .matcher(compact);
        return matcher.find() ? parseTime(matcher.group(1)) : null;
    }

    private static LocalTime endingTime(String text, LocalTime pickupStart) {
        String compact = text.replaceAll("\\s+", "");
        Matcher matcher = Pattern.compile("(?:至|到|忙到)(\\d{1,2}(?::[0-5]\\d|點(?:半|[0-5]?\\d分?)?)?)(?:結束)?")
                .matcher(compact);
        LocalTime candidate = null;
        while (matcher.find()) candidate = parseTime(matcher.group(1));
        LocalTime explicitEnd = timeNear(text, "結束");
        if (explicitEnd != null) candidate = explicitEnd;
        LocalTime arrivesHome = timeNear(text, "到家");
        if (arrivesHome != null) candidate = arrivesHome;
        return candidate != null && candidate.isAfter(pickupStart) ? candidate : null;
    }

    private static LocalTime parseTime(String raw) {
        Matcher matcher = NUMERIC_TIME.matcher(raw);
        if (!matcher.matches()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) != null ? Integer.parseInt(matcher.group(2))
                : "半".equals(matcher.group(3)) ? 30
                : matcher.group(3) == null ? 0
                : Integer.parseInt(matcher.group(3).replace("分", ""));
        return hour <= 23 ? LocalTime.of(hour, minute) : null;
    }

    private LocalDate recurrenceUntil(String text) {
        Matcher date = Pattern.compile("(?:到|至)?(\\d{1,2})月底(?:以前|之前|前)?").matcher(text);
        if (!date.find()) return null;
        int month = Integer.parseInt(date.group(1));
        int year = LocalDate.now(clock.withZone(TAIPEI)).getYear();
        LocalDate end = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth());
        return end.isBefore(LocalDate.now(clock.withZone(TAIPEI)))
                ? end.plusYears(1).with(TemporalAdjusters.lastDayOfMonth()) : end;
    }

    private static LocalTime time(String hour, String minute) {
        return LocalTime.of(Integer.parseInt(hour), minute == null ? 0 : Integer.parseInt(minute));
    }

    private static DayOfWeek weekday(String value) {
        return switch (value) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private static String chineseWeekday(DayOfWeek value) {
        return "一二三四五六日".substring(value.getValue() - 1, value.getValue());
    }

    private String write(Payload payload) {
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("cannot write school transport draft", failure); }
    }

    private Payload read(SchoolTransportDraft draft) {
        try { return objectMapper.readValue(draft.getPayload(), Payload.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("cannot read school transport draft", failure); }
    }

    private static String match(String text, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static String clean(String value) {
        return value == null ? null : value.strip().replaceAll("[（(]?\u65b0\u5e97\u4e03\u5f35\u5206\u6821[）)]?$", "（新店七張分校）");
    }

    private static String courseFromSchedule(String title, String child) {
        String compact = title == null ? "" : title.replaceAll("\\s+", "");
        String value = match(compact, Pattern.compile("到(.+?)(?:上課)?$"), 1);
        if (value == null && child != null) {
            value = match(compact, Pattern.compile(Pattern.quote(child) + "上(.+)$"), 1);
        }
        return clean(value);
    }

    private static String firstContained(String text, String... values) {
        for (String value : values) if (text.contains(value)) return value;
        return "孩子";
    }

    private static String value(Object value) { return value == null ? "待補" : value.toString(); }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s「」『』:：，,。！？?]", "").toLowerCase();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    record Payload(String child, String course, DayOfWeek weekday,
                   LocalTime start, LocalTime end, LocalDate until,
                   String dropPerson, String dropOrigin, LocalTime dropStart,
                   String pickupPerson, String pickupLocation, LocalTime pickupEnd,
                   Long sourceScheduleId, String sourceScheduleTitle) { }
}
