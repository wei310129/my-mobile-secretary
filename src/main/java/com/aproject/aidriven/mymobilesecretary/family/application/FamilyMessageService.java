package com.aproject.aidriven.mymobilesecretary.family.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyNoticeDraft;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyNoticeDraft.Status;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyNoticeDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic intake for teacher/family messages and explicit facts taught by a user.
 * It runs before the general LLM interpreter so long forwarded notices remain reliable and cheap.
 */
@Service
@Transactional
public class FamilyMessageService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern NUMBERED_LINE = Pattern.compile(
            "(?m)^\\s*\\d+[.、)]\\s*(.+?)\\s*$");
    private static final Pattern REPORT_TIME = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])(?::([0-5]\\d))?\\s*(?:報到|集合)");
    private static final Pattern END_TIME = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])(?::([0-5]\\d))?\\s*(?:結束|散場)");
    private static final Pattern SPOKEN_END_TIME = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])(?:[:：]([0-5]\\d))?\\s*點?\\s*(?:結束|散場)");
    private static final Pattern EVENT_TITLE = Pattern.compile(
            "(?:明天|明日)是(.{2,100}?)(?:[，,。\\n]|以下)");
    private static final Pattern NOTICE_TITLE_CORRECTION = Pattern.compile(
            "活動名稱(?:應該)?(?:是)?(?:改成|改為)([^，,。！？!?\\n]{2,100}?活動)");
    private static final Pattern SCHOOL_NAME = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9]{2,40}?(?:幼兒園|學校))");
    private static final Pattern RAINBOW_GATE = Pattern.compile(
            "(?s)([^，。\\n]{1,40}?)(?:的)?彩虹門.*?(?:位於|在)「?([^」，。\\n]{2,100})」?(?:的)?後門");

    private final FamilyNoticeDraftRepository repository;
    private final UserKnowledgeService knowledgeService;
    private final PlaceAliasService placeAliasService;
    private final ScheduleService scheduleService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration retention;
    private FamilyPersonService familyPersonService;

    public FamilyMessageService(
            FamilyNoticeDraftRepository repository,
            UserKnowledgeService knowledgeService,
            PlaceAliasService placeAliasService,
            ScheduleService scheduleService,
            TaskService taskService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.family.notice-draft-retention:30d}") Duration retention) {
        this.repository = repository;
        this.knowledgeService = knowledgeService;
        this.placeAliasService = placeAliasService;
        this.scheduleService = scheduleService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.retention = retention;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFamilyPersonService(FamilyPersonService familyPersonService) {
        this.familyPersonService = familyPersonService;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.replaceAll("\\s+", "");

        if (isDiscard(normalized)) {
            return Optional.of(discard(beforeMutation));
        }
        if (isConfirmation(normalized)) {
            return Optional.of(confirm(beforeMutation));
        }
        Optional<String> correctedTitle = correctedNoticeTitle(text);
        if (correctedTitle.isPresent() && latestPendingEntity().isPresent()) {
            beforeMutation.run();
            return Optional.of(updateTitle(text, correctedTitle.get(), beforeMutation));
        }
        Optional<LocalTime> suppliedEnd = spokenEndTime(text);
        if (suppliedEnd.isPresent() && latestPendingEntity().isPresent()) {
            beforeMutation.run();
            return Optional.of(updateEndTime(suppliedEnd.get()));
        }
        if (asksWhetherNoticeWasAdded(normalized)) {
            return Optional.of(noticeStatus());
        }

        List<String> learned = new ArrayList<>();
        boolean learnsRelationship = looksLikeRelationshipTeaching(normalized);
        Matcher gate = RAINBOW_GATE.matcher(text);
        boolean learnsGate = gate.find();
        boolean learnsPreference = normalized.contains("聽不懂老師")
                && normalized.contains("問我");
        boolean notice = looksLikeTeacherNotice(text);
        if (!learnsRelationship && !learnsGate && !learnsPreference && !notice) {
            return Optional.empty();
        }

        beforeMutation.run();
        if (learnsRelationship) {
            String subject = relationshipSubject(normalized);
            String detail = firstSentence(text);
            knowledgeService.remember(Category.RELATIONSHIP, subject, detail);
            learned.add("我已記住：%s".formatted(detail));
        }
        if (learnsGate) {
            String placeHint = cleanPlaceHint(gate.group(1));
            String canonicalPlace = placeAliasService.resolve(placeHint)
                    .map(place -> place.getName()).orElse(placeHint);
            String detail = firstParagraph(text);
            knowledgeService.remember(Category.PLACE_GUIDANCE, canonicalPlace, detail);
            learned.add("我已記住「%s」的接送入口說明".formatted(canonicalPlace));
        }
        if (learnsPreference) {
            String detail = "老師訊息有不確定的用語時先詢問我，不可自行猜測。";
            knowledgeService.remember(Category.INTERPRETATION_PREFERENCE,
                    "老師通知理解方式", detail);
            learned.add("我會在看不懂老師訊息時先問你，不會自行猜測");
        }

        if (notice) {
            DraftView draft = createDraft(text);
            String prefix = learned.isEmpty() ? "" : knowledgeBlock(learned) + "\n\n";
            return Optional.of(IntentResult.message(
                    IntentResult.Action.FAMILY_NOTICE_DRAFTED, prefix + preview(draft)));
        }
        return Optional.of(IntentResult.message(IntentResult.Action.KNOWLEDGE_SAVED,
                knowledgeBlock(learned)));
    }

    @Transactional(readOnly = true)
    public Optional<String> placeGuidance(String placeName) {
        return knowledgeService.find(Category.PLACE_GUIDANCE, placeName)
                .map(fact -> fact.getDetail());
    }

    private DraftView createDraft(String text) {
        Payload payload = parse(text);
        Instant now = Instant.now(clock);
        latestPendingEntity().ifPresent(previous -> previous.discard(now));
        String title = payload.title();
        FamilyNoticeDraft saved = repository.save(FamilyNoticeDraft.create(
                title, serialize(payload), now.plus(retention), now));
        return view(saved, payload);
    }

    private IntentResult updateEndTime(LocalTime endTime) {
        FamilyNoticeDraft draft = latestPendingEntity().orElseThrow();
        Payload current = deserialize(draft.getPayload());
        Payload updated = new Payload(current.title(), current.eventDate(), current.reportTime(),
                endTime, current.preparation(), current.arrival(), current.notes(),
                current.followUps());
        draft.replacePayload(serialize(updated), Instant.now(clock));
        return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_DRAFTED,
                preview(view(draft, updated)));
    }

    private IntentResult updateTitle(String text, String title, Runnable beforeMutation) {
        FamilyNoticeDraft draft = latestPendingEntity().orElseThrow();
        Payload current = deserialize(draft.getPayload());
        Payload updated = new Payload(title, current.eventDate(), current.reportTime(),
                current.endTime(), current.preparation(), current.arrival(), current.notes(),
                current.followUps());
        Instant now = Instant.now(clock);
        draft.replaceTitleAndPayload(title, serialize(updated), now);

        String remembered = schoolName(title)
                .filter(school -> familyPersonService != null
                        && familyPersonService.rememberSchoolForMention(
                                text, school, beforeMutation))
                .map(school -> "👤 我也已記住這位家人的學校是「%s」。\n\n".formatted(school))
                .orElse("");
        return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_DRAFTED,
                "✏️ 已修正活動名稱為「%s」。\n\n%s%s".formatted(
                        title, remembered, preview(view(draft, updated))));
    }

    private IntentResult noticeStatus() {
        Optional<FamilyNoticeDraft> pending = latestPendingEntity();
        if (pending.isEmpty()) {
            Optional<FamilyNoticeDraft> confirmed = latestEntity(Status.CONFIRMED);
            if (confirmed.isPresent()) {
                return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_STATUS,
                        "最近一份老師通知已確認並處理。\n"
                                + "- 通知：%s\n- 確認時間：%s".formatted(
                                confirmed.get().getTitle(), confirmed.get().getUpdatedAt()));
            }
            return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_STATUS,
                    "目前沒有等待確認的老師通知草稿。\n\n"
                            + "❓ 請把老師原始訊息再貼一次，我會先整理重點並請你確認，不會直接亂建行程。");
        }
        DraftView draft = view(pending.get());
        return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_STATUS,
                "目前還沒有建立正式行程或待辦，因為老師通知仍在等待你確認。\n\n"
                        + preview(draft));
    }

    private IntentResult confirm(Runnable beforeMutation) {
        FamilyNoticeDraft draft = latestPendingEntity().orElse(null);
        if (draft == null) {
            return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_STATUS,
                    "目前沒有可確認的老師通知草稿。\n\n❓ 請先貼上老師的原始訊息。");
        }
        Payload payload = deserialize(draft.getPayload());
        List<String> missing = new ArrayList<>();
        if (payload.eventDate() == null) missing.add("活動日期");
        if (payload.reportTime() == null) missing.add("報到時間");
        if (payload.endTime() == null) missing.add("活動結束時間");
        if (!missing.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "老師通知還缺少%s，我不會自行猜時間。請補充後再確認。"
                            .formatted(String.join("、", missing)));
        }

        ZonedDateTime start = ZonedDateTime.of(
                payload.eventDate(), payload.reportTime(), TAIPEI);
        ZonedDateTime end = ZonedDateTime.of(payload.eventDate(), payload.endTime(), TAIPEI);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("family notice end time must be after report time");
        }
        beforeMutation.run();
        var schedule = scheduleService.createSchedule(
                payload.title(), start.toInstant(), end.toInstant(), null);

        Instant preparationDue = start.minusHours(2).toInstant();
        List<String> taskTitles = new ArrayList<>();
        for (String item : payload.preparation().stream().limit(10).toList()) {
            String title = bounded("準備：" + item, 160);
            taskService.createTask(title, "來自已確認的老師通知",
                    TaskPriority.NORMAL, preparationDue);
            taskTitles.add(title);
        }
        for (FollowUp followUp : payload.followUps().stream().limit(5).toList()) {
            Instant dueAt = followUp.dueDate() == null ? null
                    : ZonedDateTime.of(followUp.dueDate(), LocalTime.of(8, 0), TAIPEI)
                            .toInstant();
            String title = bounded(followUp.title(), 160);
            taskService.createTask(title, "來自已確認的老師通知",
                    TaskPriority.NORMAL, dueAt);
            taskTitles.add(title);
        }
        draft.confirm(Instant.now(clock));

        String tasks = taskTitles.isEmpty() ? "沒有另外建立準備待辦"
                : "已建立準備待辦：\n" + taskTitles.stream()
                        .map(title -> "- " + title).collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_CONFIRMED,
                "老師通知已確認並加入行程。\n"
                        + "- 行程：%s\n- 時間：%s–%s\n\n%s".formatted(
                        schedule.item().getTitle(), payload.reportTime(), payload.endTime(), tasks));
    }

    private IntentResult discard(Runnable beforeMutation) {
        FamilyNoticeDraft draft = latestPendingEntity().orElse(null);
        if (draft == null) {
            return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_STATUS,
                    "目前沒有等待放棄的老師通知草稿。");
        }
        beforeMutation.run();
        draft.discard(Instant.now(clock));
        return IntentResult.message(IntentResult.Action.FAMILY_NOTICE_DISCARDED,
                "已放棄老師通知草稿，沒有建立行程或待辦。");
    }

    private Payload parse(String text) {
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        LocalDate eventDate = containsTomorrow(text) ? today.plusDays(1) : null;
        LocalTime reportTime = firstTime(REPORT_TIME, text).orElse(null);
        LocalTime endTime = firstTime(END_TIME, text).orElse(null);
        String title = extractTitle(text).orElse("老師通知");
        List<String> lines = noticeLines(text);
        List<String> preparation = new ArrayList<>();
        List<String> arrival = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<FollowUp> followUps = new ArrayList<>();
        for (String raw : lines) {
            String line = cleanLine(raw);
            if (line.isBlank()) continue;
            if (containsAny(line, "星期一", "帶回來", "帶回幼兒園", "帶回學校")) {
                LocalDate dueDate = line.contains("星期一")
                        ? today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) : null;
                followUps.add(new FollowUp(line, dueDate));
            } else if (containsAny(line, "報到", "集合", "彩虹門", "入口", "後門",
                    "停車", "大眾運輸")) {
                arrival.add(line);
            } else if (containsAny(line, "穿", "帶", "換洗", "鞋", "服裝", "證件")) {
                preparation.add(line);
            } else {
                notes.add(line);
            }
        }
        return new Payload(title, eventDate, reportTime, endTime,
                distinct(preparation), distinct(arrival), distinct(notes),
                List.copyOf(followUps));
    }

    private String preview(DraftView draft) {
        Payload payload = draft.payload();
        List<String> blocks = new ArrayList<>();
        blocks.add("📅 我理解這是老師給家長的活動通知，不是名為『老師提醒』的舊待辦。\n"
                + "- 活動：%s\n- 日期：%s\n- 報到：%s\n- 結束：%s".formatted(
                payload.title(), value(payload.eventDate()), value(payload.reportTime()),
                value(payload.endTime())));
        if (!payload.preparation().isEmpty()) {
            blocks.add("🎒 要準備／穿著：\n" + bullets(payload.preparation()));
        }
        if (!payload.arrival().isEmpty()) {
            blocks.add("🚗 報到與交通：\n" + bullets(payload.arrival()));
        }
        List<String> other = new ArrayList<>(payload.notes());
        payload.followUps().forEach(item -> other.add(item.title()));
        if (!other.isEmpty()) {
            blocks.add("⚠️ 其他提醒：\n" + bullets(other));
        }
        List<String> missing = new ArrayList<>();
        if (payload.eventDate() == null) missing.add("活動日期");
        if (payload.reportTime() == null) missing.add("報到時間");
        if (payload.endTime() == null) missing.add("活動結束時間");
        if (missing.isEmpty()) {
            blocks.add("❓ 我還沒有建立正式行程或待辦。資料正確請回覆「確認老師通知」；"
                    + "有錯請直接更正，或回覆「放棄老師通知」。");
        } else {
            blocks.add("❓ 我還不確定%s，不會自行猜測。請補充後，我會再讓你確認。"
                    .formatted(String.join("、", missing)));
        }
        return String.join("\n\n", blocks);
    }

    private Optional<FamilyNoticeDraft> latestPendingEntity() {
        return latestEntity(Status.PENDING);
    }

    private Optional<FamilyNoticeDraft> latestEntity(Status status) {
        return repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                WorkspaceContextHolder.requireContext().actorId(), status,
                Instant.now(clock));
    }

    private DraftView view(FamilyNoticeDraft draft) {
        return view(draft, deserialize(draft.getPayload()));
    }

    private static DraftView view(FamilyNoticeDraft draft, Payload payload) {
        return new DraftView(draft.getId(), draft.getTitle(), draft.getStatus(), payload,
                draft.getExpiresAt());
    }

    private String serialize(Payload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 30000) throw new IllegalArgumentException("family notice too large");
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("family notice cannot be serialized", e);
        }
    }

    private Payload deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored family notice is invalid", e);
        }
    }

    private static boolean looksLikeTeacherNotice(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("老師") && (compact.contains("@All")
                || compact.contains("提醒訊息") || compact.contains("老師說"))
                && (NUMBERED_LINE.matcher(text).find()
                || containsAny(compact, "換洗衣物", "防水鞋", "報到", "集合"));
    }

    private static boolean asksWhetherNoticeWasAdded(String compact) {
        return compact.contains("老師") && compact.contains("提醒")
                && containsAny(compact, "加到行程", "加入行程", "有加", "建立了嗎");
    }

    private static boolean isConfirmation(String compact) {
        return compact.equals("確認老師通知") || compact.equals("確認加入老師通知")
                || compact.equals("老師通知確認");
    }

    private static boolean isDiscard(String compact) {
        return compact.equals("放棄老師通知") || compact.equals("取消老師通知草稿");
    }

    private static Optional<LocalTime> spokenEndTime(String text) {
        return firstTime(SPOKEN_END_TIME, text);
    }

    private static Optional<LocalTime> firstTime(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return Optional.empty();
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return Optional.of(LocalTime.of(hour, minute));
    }

    private static Optional<String> extractTitle(String text) {
        Matcher matcher = EVENT_TITLE.matcher(text);
        if (!matcher.find()) return Optional.empty();
        return Optional.of(bounded(matcher.group(1).strip(), 160));
    }

    private static Optional<String> correctedNoticeTitle(String text) {
        Matcher matcher = NOTICE_TITLE_CORRECTION.matcher(text);
        if (!matcher.find()) return Optional.empty();
        return Optional.of(bounded(matcher.group(1).strip(), 160));
    }

    private static Optional<String> schoolName(String title) {
        Matcher matcher = SCHOOL_NAME.matcher(title);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static List<String> noticeLines(String text) {
        List<String> lines = new ArrayList<>();
        Matcher numbered = NUMBERED_LINE.matcher(text);
        while (numbered.find()) lines.add(numbered.group(1));
        if (!lines.isEmpty()) return lines;
        int teacherSaid = text.indexOf("老師說");
        String body = teacherSaid >= 0 ? text.substring(teacherSaid + 3) : text;
        for (String value : body.split("(?:以及|[。；;\\n])")) {
            if (!value.isBlank()) lines.add(value.strip());
        }
        return lines;
    }

    private static boolean looksLikeRelationshipTeaching(String compact) {
        return compact.startsWith("我是我") && compact.contains("的家長")
                && (compact.contains("爸爸") || compact.contains("媽媽"));
    }

    private static String relationshipSubject(String compact) {
        if (compact.contains("大女兒")) return "我與大女兒的關係";
        if (compact.contains("小女兒")) return "我與小女兒的關係";
        if (compact.contains("女兒")) return "我與女兒的關係";
        if (compact.contains("兒子")) return "我與兒子的關係";
        return "家庭關係";
    }

    private static String knowledgeBlock(List<String> values) {
        return "🧠 我理解並記住了：\n" + bullets(values);
    }

    private static String cleanPlaceHint(String value) {
        return value.strip().replaceFirst("^(?:另外|還有|補充)", "")
                .replaceFirst("的$", "");
    }

    private static String firstSentence(String text) {
        return bounded(text.split("[。\\n]", 2)[0].strip(), 1200);
    }

    private static String firstParagraph(String text) {
        return bounded(text.split("\\R\\s*\\R", 2)[0].strip(), 1200);
    }

    private static String cleanLine(String value) {
        return value.replaceAll("^[：:，,。\\s]+|[\\s]+$", "").strip();
    }

    private static boolean containsTomorrow(String value) {
        return value.contains("明天") || value.contains("明日");
    }

    private static String value(Object value) {
        return value == null ? "尚未提供" : value.toString();
    }

    private static String bullets(List<String> values) {
        return values.stream().map(value -> "- " + value)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static <T> List<T> distinct(List<T> values) {
        return values.stream().distinct().toList();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String bounded(String value, int max) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    public record FollowUp(String title, LocalDate dueDate) {
    }

    public record Payload(
            String title,
            LocalDate eventDate,
            LocalTime reportTime,
            LocalTime endTime,
            List<String> preparation,
            List<String> arrival,
            List<String> notes,
            List<FollowUp> followUps) {
        public Payload {
            preparation = preparation == null ? List.of() : List.copyOf(preparation);
            arrival = arrival == null ? List.of() : List.copyOf(arrival);
            notes = notes == null ? List.of() : List.copyOf(notes);
            followUps = followUps == null ? List.of() : List.copyOf(followUps);
        }
    }

    public record DraftView(Long id, String title, Status status,
                            Payload payload, Instant expiresAt) {
    }
}
