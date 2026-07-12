package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 意圖編排:解析 → 驗證 → 執行。
 *
 * 可靠度鐵律:LLM 任何失敗(沒設金鑰、逾時、輸出格式爛)都不能吞掉使用者的話——
 * 一律 fallback 把原文存成一般任務,寧可少聰明,不可丟資料。
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final ObjectProvider<IntentInterpreter> interpreterProvider;
    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final PlaceRepository placeRepository;
    private final Clock clock;

    public IntentService(ObjectProvider<IntentInterpreter> interpreterProvider,
                         TaskService taskService,
                         ScheduleService scheduleService,
                         PlaceRepository placeRepository,
                         Clock clock) {
        this.interpreterProvider = interpreterProvider;
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.placeRepository = placeRepository;
        this.clock = clock;
    }

    /** 處理使用者的一句話,回傳做了什麼。 */
    public IntentResult handle(String text) {
        IntentCommand command;
        IntentInterpreter interpreter = interpreterProvider.getIfAvailable();
        if (interpreter == null) {
            return fallbackTask(text, "意圖解析未啟用");
        }
        try {
            command = interpreter.interpret(text, Instant.now(clock));
        } catch (Exception e) {
            log.warn("Intent interpretation failed, falling back to plain task", e);
            return fallbackTask(text, "AI 暫時無法使用");
        }

        try {
            return execute(text, command);
        } catch (IllegalArgumentException e) {
            // LLM 輸出未通過驗證(時間格式爛、缺欄位)→ 同樣不丟資料
            log.warn("Intent command invalid ({}), falling back to plain task", e.getMessage());
            return fallbackTask(text, "解析結果不完整");
        }
    }

    /** 依驗證後的 command 執行;LLM 輸出一律先驗證再信。 */
    private IntentResult execute(String text, IntentCommand command) {
        if (command == null || command.type() == null) {
            throw new IllegalArgumentException("missing type");
        }
        return switch (command.type()) {
            case CREATE_TASK -> {
                requireText(command.title(), "title");
                Task task = taskService.createTask(
                        command.title(), null, parsePriority(command.priority()),
                        parseTime(command.dueAt()));
                yield IntentResult.taskCreated(task);
            }
            case CREATE_SCHEDULE -> {
                requireText(command.title(), "title");
                Instant startAt = parseTime(command.startAt());
                Instant endAt = parseTime(command.endAt());
                if (startAt == null || endAt == null) {
                    throw new IllegalArgumentException("schedule missing startAt/endAt");
                }
                Long placeId = resolvePlace(command.placeName()).map(Place::getId).orElse(null);
                ScheduleDecision decision = scheduleService.createSchedule(
                        command.title(), startAt, endAt, placeId);
                yield IntentResult.scheduleDecided(decision);
            }
            case UNKNOWN -> IntentResult.clarificationNeeded(
                    command.reason() == null || command.reason().isBlank()
                            ? "我沒聽懂,可以換個說法嗎?" : command.reason());
        };
    }

    /** LLM 失敗時的保底:原文直接存成任務(仍會觸發品項自動綁定)。 */
    private IntentResult fallbackTask(String text, String why) {
        Task task = taskService.createTask(text, null, TaskPriority.NORMAL, null);
        return IntentResult.fallbackTaskCreated(task, why);
    }

    /** 地點名稱解析:先精確比對,再包含比對(規則式;不讓 LLM 決定 id)。 */
    private Optional<Place> resolvePlace(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return Optional.empty();
        }
        var places = placeRepository.findAll();
        Optional<Place> exact = places.stream()
                .filter(p -> p.getName().equals(placeName)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return places.stream()
                .filter(p -> p.getName().contains(placeName) || placeName.contains(p.getName()))
                .findFirst();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static Instant parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException("bad time: " + iso);
        }
    }

    private static TaskPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return TaskPriority.NORMAL;
        }
        try {
            return TaskPriority.valueOf(priority.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TaskPriority.NORMAL;
        }
    }
}
