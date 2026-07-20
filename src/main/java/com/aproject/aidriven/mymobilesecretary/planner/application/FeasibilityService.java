package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.BufferRuleService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.LifestyleWindowService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PlanningPreferenceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.LifestyleWindow;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可行性引擎 v1(簡化版):時間重疊 + 直線距離粗估交通的「要可行才放行」驗算。
 *
 * 確定性 Java 規則,LLM 不參與(architecture.md §8 鐵律)。
 * 已知簡化(TDX 接上後與 v2 一併改善):
 * - 交通時間 = 直線距離 ÷ 假設速度 + 固定緩衝(保守參數見 FeasibilityProperties)
 * - 前後行程只認「有地點的最近一筆 CONFIRMED」,不處理中間夾無地點行程的情況
 * - 最後已知位置可能過時(使用者離開後沒回報),以「現在→開始」的時間差衡量,
 *   行程越遠期越不會誤報
 */
@Service
@Transactional(readOnly = true)
public class FeasibilityService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final ScheduleItemRepository scheduleItemRepository;
    private final PlaceRepository placeRepository;
    private final LocationEventRepository locationEventRepository;
    private final TravelTimeEstimator travelTimeEstimator;
    private final BufferRuleService bufferRuleService;
    private final PlanningPreferenceService planningPreferenceService;
    private final TaskRepository taskRepository;
    private final Clock clock;
    private LifestyleWindowService lifestyleWindowService;

    public FeasibilityService(ScheduleItemRepository scheduleItemRepository,
                              PlaceRepository placeRepository,
                              LocationEventRepository locationEventRepository,
                              TravelTimeEstimator travelTimeEstimator,
                              BufferRuleService bufferRuleService,
                              PlanningPreferenceService planningPreferenceService,
                              TaskRepository taskRepository,
                              Clock clock) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.placeRepository = placeRepository;
        this.locationEventRepository = locationEventRepository;
        this.travelTimeEstimator = travelTimeEstimator;
        this.bufferRuleService = bufferRuleService;
        this.planningPreferenceService = planningPreferenceService;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLifestyleWindowService(LifestyleWindowService lifestyleWindowService) {
        this.lifestyleWindowService = lifestyleWindowService;
    }

    /**
     * 驗算候選行程的可行性。基準只有 CONFIRMED 行程(它們才是真承諾)。
     *
     * @param candidate 候選行程(可能尚未存檔或改時間中;以其欄位值驗算,排除自身 id)
     */
    public FeasibilityResult check(ScheduleItem candidate) {
        List<ScheduleItem> confirmed = scheduleItemRepository
                .findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED).stream()
                .filter(other -> !Objects.equals(other.getId(), candidate.getId()))
                .filter(ScheduleItem::isCountsForActorBusy)
                .toList();

        List<FeasibilityIssue> issues = new ArrayList<>();
        if (!candidate.isCountsForActorBusy()) {
            return FeasibilityResult.withIssues(issues);
        }
        checkTimeOverlap(candidate, confirmed, issues);
        // 定時待辦在共同語意上屬於「行程提醒」：顯示於日曆，但像鬧鐘一樣不占用時段，
        // 因此不參與行程撞期。只有真正的 ScheduleItem busy interval 才能阻擋候選行程。
        checkLifestyleWindows(candidate, issues);
        checkTravel(candidate, confirmed, issues);
        return FeasibilityResult.withIssues(issues);
    }

    /**
     * 唯讀分析尚未建立的行程時間窗。準備、主行程、後續交通分段檢查，
     * 讓回覆能指出是哪一段撞期；本方法不建立或修改任何 ScheduleItem。
     */
    public HypotheticalWindowAnalysis analyzeHypotheticalWindow(
            String title, Instant startAt, Instant endAt,
            Duration preparation, Duration afterTravel) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("hypothetical schedule time range is invalid");
        }
        Duration before = validatedWindowBuffer(preparation, "preparation");
        Duration after = validatedWindowBuffer(afterTravel, "after travel");
        List<HypotheticalSegmentWindow> segments = List.of(
                new HypotheticalSegmentWindow(
                        HypotheticalSegment.PREPARATION, startAt.minus(before), startAt),
                new HypotheticalSegmentWindow(HypotheticalSegment.MAIN, startAt, endAt),
                new HypotheticalSegmentWindow(
                        HypotheticalSegment.AFTER_TRAVEL, endAt, endAt.plus(after)));
        List<ScheduleItem> confirmed = scheduleItemRepository
                .findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED).stream()
                .filter(ScheduleItem::isCountsForActorBusy)
                .toList();
        List<HypotheticalConflict> conflicts = new ArrayList<>();
        for (HypotheticalSegmentWindow segment : segments) {
            if (!segment.endAt().isAfter(segment.startAt())) {
                continue;
            }
            for (ScheduleItem other : confirmed) {
                Instant overlapStart = segment.startAt().isAfter(other.getStartAt())
                        ? segment.startAt() : other.getStartAt();
                Instant overlapEnd = segment.endAt().isBefore(other.getEndAt())
                        ? segment.endAt() : other.getEndAt();
                if (overlapEnd.isAfter(overlapStart)) {
                    conflicts.add(new HypotheticalConflict(
                            segment.segment(), other.getTitle(), overlapStart, overlapEnd));
                }
            }
        }
        return new HypotheticalWindowAnalysis(
                title, startAt, endAt, startAt.minus(before), endAt.plus(after),
                before, after, conflicts);
    }

    private static Duration validatedWindowBuffer(Duration value, String field) {
        Duration buffer = value == null ? Duration.ZERO : value;
        if (buffer.isNegative() || buffer.compareTo(Duration.ofHours(4)) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 240 minutes");
        }
        return buffer;
    }

    /**
     * 生活時間窗是規劃限制而非固定行程：有交集就提醒並等待使用者決定，
     * 不會自行拒絕、挪動行程或建立假的餐飲／睡眠行程。
     */
    private void checkLifestyleWindows(ScheduleItem candidate, List<FeasibilityIssue> issues) {
        if (lifestyleWindowService == null) {
            return;
        }
        ZonedDateTime candidateStart = candidate.getStartAt().atZone(TAIPEI);
        ZonedDateTime candidateEnd = candidate.getEndAt().atZone(TAIPEI);
        LocalDate firstDate = candidateStart.toLocalDate().minusDays(1);
        LocalDate lastDate = candidateEnd.toLocalDate();
        Set<String> reported = new HashSet<>();

        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            LifestyleWindow.DayType dayType = isWeekend(date)
                    ? LifestyleWindow.DayType.HOLIDAY : LifestyleWindow.DayType.WEEKDAY;
            for (LifestyleWindow window : lifestyleWindowService.list(dayType)) {
                ZonedDateTime windowStart = date.atTime(window.getStartTime()).atZone(TAIPEI);
                ZonedDateTime windowEnd = date.atTime(window.getEndTime()).atZone(TAIPEI);
                if (!windowEnd.isAfter(windowStart)) {
                    windowEnd = windowEnd.plusDays(1);
                }
                if (!candidateStart.isBefore(windowEnd) || !windowStart.isBefore(candidateEnd)) {
                    continue;
                }
                String key = date + ":" + window.getKind();
                if (!reported.add(key)) {
                    continue;
                }
                issues.add(new FeasibilityIssue(
                        FeasibilityIssue.Type.LIFESTYLE_WINDOW_COMPRESSED,
                        "行程「%s」會壓縮%s%s %s–%s；這是生活需求提示，不會自動建立、拒絕或移動行程，請確認是否仍照排。"
                                .formatted(candidate.getTitle(), dayTypeLabel(dayType),
                                        lifestyleKindLabel(window.getKind()),
                                        window.getStartTime(), window.getEndTime()),
                        null));
            }
        }
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
    }

    private static String dayTypeLabel(LifestyleWindow.DayType dayType) {
        return dayType == LifestyleWindow.DayType.WEEKDAY ? "平日" : "假日／週末";
    }

    private static String lifestyleKindLabel(LifestyleWindow.Kind kind) {
        return switch (kind) {
            case BREAKFAST -> "早餐";
            case LUNCH -> "午餐";
            case DINNER -> "晚餐";
            case SLEEP -> "睡眠";
        };
    }

    /** 時間重疊:候選 [start,end) 與任一 CONFIRMED [start,end) 相交即衝突。 */
    private void checkTimeOverlap(ScheduleItem candidate, List<ScheduleItem> confirmed,
                                  List<FeasibilityIssue> issues) {
        for (ScheduleItem other : confirmed) {
            boolean overlaps = candidate.getStartAt().isBefore(other.getEndAt())
                    && other.getStartAt().isBefore(candidate.getEndAt());
            if (overlaps) {
                Instant overlapStart = candidate.getStartAt().isAfter(other.getStartAt())
                        ? candidate.getStartAt() : other.getStartAt();
                Instant overlapEnd = candidate.getEndAt().isBefore(other.getEndAt())
                        ? candidate.getEndAt() : other.getEndAt();
                boolean candidateContainsOther = !other.getStartAt().isBefore(candidate.getStartAt())
                        && !other.getEndAt().isAfter(candidate.getEndAt());
                boolean otherContainsCandidate = !candidate.getStartAt().isBefore(other.getStartAt())
                        && !candidate.getEndAt().isAfter(other.getEndAt());
                boolean nestedWithRecurring = (candidateContainsOther || otherContainsCandidate)
                        && (candidate.getRecurrence() != ScheduleItem.Recurrence.NONE
                        || other.getRecurrence() != ScheduleItem.Recurrence.NONE);
                if (nestedWithRecurring) {
                    ScheduleItem recurring = candidate.getRecurrence() != ScheduleItem.Recurrence.NONE
                            ? candidate : other;
                    ScheduleItem nested = recurring == candidate ? other : candidate;
                    issues.add(new FeasibilityIssue(
                            FeasibilityIssue.Type.NESTED_IN_RECURRING_SCHEDULE,
                            "「%s」%s–%s 位於固定行程「%s」%s–%s 內；它可能是固定行程的當日子項目。"
                                    .formatted(nested.getTitle(), format(nested.getStartAt()),
                                            formatTime(nested.getEndAt()), recurring.getTitle(),
                                            format(recurring.getStartAt()), formatTime(recurring.getEndAt())),
                            other.getId()));
                    continue;
                }
                issues.add(new FeasibilityIssue(
                        FeasibilityIssue.Type.TIME_OVERLAP,
                        "「%s」%s–%s 與「%s」%s–%s 衝突；重疊區間 %s–%s（%d 分鐘）。"
                                .formatted(candidate.getTitle(), format(candidate.getStartAt()),
                                        formatTime(candidate.getEndAt()), other.getTitle(),
                                        format(other.getStartAt()), formatTime(other.getEndAt()),
                                        format(overlapStart), formatTime(overlapEnd),
                                        Duration.between(overlapStart, overlapEnd).toMinutes()),
                        other.getId()));
            }
        }
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(TIME);
    }

    private static String formatTime(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public enum HypotheticalSegment {
        PREPARATION,
        MAIN,
        AFTER_TRAVEL
    }

    public record HypotheticalSegmentWindow(
            HypotheticalSegment segment, Instant startAt, Instant endAt) {
    }

    public record HypotheticalConflict(
            HypotheticalSegment segment, String existingTitle,
            Instant overlapStart, Instant overlapEnd) {
    }

    public record HypotheticalWindowAnalysis(
            String title, Instant startAt, Instant endAt,
            Instant windowStart, Instant windowEnd,
            Duration preparation, Duration afterTravel,
            List<HypotheticalConflict> conflicts) {

        public HypotheticalWindowAnalysis {
            conflicts = List.copyOf(conflicts);
        }

        public boolean feasible() {
            return conflicts.isEmpty();
        }
    }

    /**
     * 交通可行性(候選行程有地點才檢查):
     * 1. 前一個有地點的 CONFIRMED 行程 → 趕得到嗎
     * 2. 沒有前行程 → 用最後已知位置,從「現在」出發趕得到嗎(人在高雄、預約台北就擋在這)
     * 3. 下一個有地點的 CONFIRMED 行程 → 結束後趕得上嗎
     *
     * 去程與回程兩段的交通估算(可能各打一次 TDX,1-2 秒/次)以虛擬執行緒平行執行:
     * 資料(地點、空檔)在主執行緒先備齊,平行區只做外部估算與規則判斷。
     */
    private void checkTravel(ScheduleItem candidate, List<ScheduleItem> confirmed,
                             List<FeasibilityIssue> issues) {
        if (candidate.getPlaceId() == null) {
            return;
        }
        Place candidatePlace = placeRepository.findById(candidate.getPlaceId()).orElse(null);
        if (candidatePlace == null) {
            return;
        }

        Callable<Optional<FeasibilityIssue>> arrivalLeg = arrivalLeg(candidate, candidatePlace, confirmed);
        Callable<Optional<FeasibilityIssue>> departureLeg = departureLeg(candidate, candidatePlace, confirmed);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<FeasibilityIssue>> arrival = executor.submit(arrivalLeg);
            Future<Optional<FeasibilityIssue>> departure = executor.submit(departureLeg);
            arrival.get().ifPresent(issues::add);
            departure.get().ifPresent(issues::add);
        } catch (ExecutionException e) {
            // 估算層自帶 fallback,理論上不會到這;真到了寧可保守擋下也不放行
            throw new IllegalStateException("Travel feasibility evaluation failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Travel feasibility evaluation interrupted", e);
        }
    }

    /** 去程檢查:資料在主執行緒備齊,回傳可平行執行的估算工作。 */
    private Callable<Optional<FeasibilityIssue>> arrivalLeg(ScheduleItem candidate, Place candidatePlace,
                                                            List<ScheduleItem> confirmed) {
        // 1. 前一個有地點的行程
        Optional<ScheduleItem> previous = confirmed.stream()
                .filter(other -> other.getPlaceId() != null)
                .filter(other -> !other.getEndAt().isAfter(candidate.getStartAt()))
                .max(Comparator.comparing(ScheduleItem::getEndAt));

        if (previous.isPresent()) {
            Place prevPlace = placeRepository.findById(previous.get().getPlaceId()).orElse(null);
            if (prevPlace == null) {
                return Optional::empty;
            }
            ScheduleItem prev = previous.get();
            // 緩衝規則:前一行程的地點若有超時習慣,實際能動身的時間比表定 endAt 晚
            Duration overrunBuffer = bufferRuleService.recommendedBuffer(prev.getPlaceId());
            Duration preferenceBuffer = planningPreferenceService.extraTransferBuffer();
            Duration gap = Duration.between(prev.getEndAt(), candidate.getStartAt())
                    .minus(overrunBuffer).minus(preferenceBuffer);
            return () -> {
                Duration need = travelTimeEstimator.estimate(
                        prevPlace.getLatitude(), prevPlace.getLongitude(),
                        candidatePlace.getLatitude(), candidatePlace.getLongitude(), prev.getEndAt());
                if (need.compareTo(gap) <= 0) {
                    return Optional.empty();
                }
                String bufferNote = overrunBuffer.isZero()
                        ? "" : "(已預留「%s」常見超時 %d 分鐘)".formatted(prevPlace.getName(), overrunBuffer.toMinutes());
                return Optional.of(new FeasibilityIssue(
                        FeasibilityIssue.Type.TRAVEL_FROM_PREVIOUS,
                        "從「%s」(%s)趕到「%s」約需 %d 分鐘,但只有 %d 分鐘空檔%s。可改時間或調整前一行程。"
                                .formatted(prev.getTitle(), prevPlace.getName(),
                                        candidatePlace.getName(), need.toMinutes(),
                                        Math.max(gap.toMinutes(), 0), bufferNote),
                        prev.getId()));
            };
        }

        // 2. 沒有前行程 → 從最後已知位置、以「現在」為出發時間驗算
        var lastLocation = locationEventRepository.findTopByOrderByOccurredAtDesc().orElse(null);
        if (lastLocation == null) {
            return Optional::empty;
        }
        Instant now = Instant.now(clock);
        Duration gap = Duration.between(now, candidate.getStartAt());
        return () -> {
            Duration need = travelTimeEstimator.estimate(
                    lastLocation.getLatitude(), lastLocation.getLongitude(),
                    candidatePlace.getLatitude(), candidatePlace.getLongitude(), now);
            if (!gap.isNegative() && need.compareTo(gap) <= 0) {
                return Optional.empty();
            }
            return Optional.of(new FeasibilityIssue(
                    FeasibilityIssue.Type.TRAVEL_FROM_CURRENT_LOCATION,
                    "從目前位置趕到「%s」約需 %d 分鐘,距開始只剩 %d 分鐘。可改預約時間,或安排回程交通後強制確認。"
                            .formatted(candidatePlace.getName(), need.toMinutes(),
                                    Math.max(gap.toMinutes(), 0)),
                    null));
        };
    }

    /** 回程檢查:結束後趕不趕得上下一個有地點的行程。 */
    private Callable<Optional<FeasibilityIssue>> departureLeg(ScheduleItem candidate, Place candidatePlace,
                                                              List<ScheduleItem> confirmed) {
        Optional<ScheduleItem> nextOpt = confirmed.stream()
                .filter(other -> other.getPlaceId() != null)
                .filter(other -> !other.getStartAt().isBefore(candidate.getEndAt()))
                .min(Comparator.comparing(ScheduleItem::getStartAt));
        if (nextOpt.isEmpty()) {
            return Optional::empty;
        }
        ScheduleItem next = nextOpt.get();
        Place nextPlace = placeRepository.findById(next.getPlaceId()).orElse(null);
        if (nextPlace == null) {
            return Optional::empty;
        }
        // 緩衝規則:候選行程自己的地點若有超時習慣,實際結束比表定 endAt 晚
        Duration overrunBuffer = bufferRuleService.recommendedBuffer(candidate.getPlaceId());
        Duration preferenceBuffer = planningPreferenceService.extraTransferBuffer();
        Duration gap = Duration.between(candidate.getEndAt(), next.getStartAt())
                .minus(overrunBuffer).minus(preferenceBuffer);
        return () -> {
            Duration need = travelTimeEstimator.estimate(
                    candidatePlace.getLatitude(), candidatePlace.getLongitude(),
                    nextPlace.getLatitude(), nextPlace.getLongitude(), candidate.getEndAt());
            if (need.compareTo(gap) <= 0) {
                return Optional.empty();
            }
            String bufferNote = overrunBuffer.isZero()
                    ? "" : "(已預留「%s」常見超時 %d 分鐘)".formatted(candidatePlace.getName(), overrunBuffer.toMinutes());
            return Optional.of(new FeasibilityIssue(
                    FeasibilityIssue.Type.TRAVEL_TO_NEXT,
                    "結束後趕往「%s」(%s)約需 %d 分鐘,但只有 %d 分鐘空檔%s。可提早結束或調整下一行程。"
                            .formatted(next.getTitle(), nextPlace.getName(),
                                    need.toMinutes(), Math.max(gap.toMinutes(), 0), bufferNote),
                    next.getId()));
        };
    }

}
