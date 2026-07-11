package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeoDistance;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    private final ScheduleItemRepository scheduleItemRepository;
    private final PlaceRepository placeRepository;
    private final LocationEventRepository locationEventRepository;
    private final FeasibilityProperties properties;
    private final Clock clock;

    public FeasibilityService(ScheduleItemRepository scheduleItemRepository,
                              PlaceRepository placeRepository,
                              LocationEventRepository locationEventRepository,
                              FeasibilityProperties properties,
                              Clock clock) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.placeRepository = placeRepository;
        this.locationEventRepository = locationEventRepository;
        this.properties = properties;
        this.clock = clock;
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
                .toList();

        List<FeasibilityIssue> issues = new ArrayList<>();
        checkTimeOverlap(candidate, confirmed, issues);
        checkTravel(candidate, confirmed, issues);
        return FeasibilityResult.withIssues(issues);
    }

    /** 時間重疊:候選 [start,end) 與任一 CONFIRMED [start,end) 相交即衝突。 */
    private void checkTimeOverlap(ScheduleItem candidate, List<ScheduleItem> confirmed,
                                  List<FeasibilityIssue> issues) {
        for (ScheduleItem other : confirmed) {
            boolean overlaps = candidate.getStartAt().isBefore(other.getEndAt())
                    && other.getStartAt().isBefore(candidate.getEndAt());
            if (overlaps) {
                issues.add(new FeasibilityIssue(
                        FeasibilityIssue.Type.TIME_OVERLAP,
                        "與「%s」時間重疊。可改時間,或取消其一。".formatted(other.getTitle()),
                        other.getId()));
            }
        }
    }

    /**
     * 交通可行性(候選行程有地點才檢查):
     * 1. 前一個有地點的 CONFIRMED 行程 → 趕得到嗎
     * 2. 沒有前行程 → 用最後已知位置,從「現在」出發趕得到嗎(人在高雄、預約台北就擋在這)
     * 3. 下一個有地點的 CONFIRMED 行程 → 結束後趕得上嗎
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

        // 1. 前一個有地點的行程
        Optional<ScheduleItem> previous = confirmed.stream()
                .filter(other -> other.getPlaceId() != null)
                .filter(other -> !other.getEndAt().isAfter(candidate.getStartAt()))
                .max(Comparator.comparing(ScheduleItem::getEndAt));

        if (previous.isPresent()) {
            Place prevPlace = placeRepository.findById(previous.get().getPlaceId()).orElse(null);
            if (prevPlace != null) {
                Duration gap = Duration.between(previous.get().getEndAt(), candidate.getStartAt());
                Duration need = travelTime(prevPlace.getLatitude(), prevPlace.getLongitude(), candidatePlace);
                if (need.compareTo(gap) > 0) {
                    issues.add(new FeasibilityIssue(
                            FeasibilityIssue.Type.TRAVEL_FROM_PREVIOUS,
                            "從「%s」(%s)趕到「%s」約需 %d 分鐘,但只有 %d 分鐘空檔。可改時間或調整前一行程。"
                                    .formatted(previous.get().getTitle(), prevPlace.getName(),
                                            candidatePlace.getName(), need.toMinutes(), gap.toMinutes()),
                            previous.get().getId()));
                }
            }
        } else {
            // 2. 沒有前行程 → 從最後已知位置、以「現在」為出發時間驗算
            locationEventRepository.findTopByOrderByOccurredAtDesc().ifPresent(last -> {
                Duration gap = Duration.between(Instant.now(clock), candidate.getStartAt());
                Duration need = travelTime(last.getLatitude(), last.getLongitude(), candidatePlace);
                if (gap.isNegative() || need.compareTo(gap) > 0) {
                    issues.add(new FeasibilityIssue(
                            FeasibilityIssue.Type.TRAVEL_FROM_CURRENT_LOCATION,
                            "從目前位置趕到「%s」約需 %d 分鐘,距開始只剩 %d 分鐘。可改預約時間,或安排回程交通後強制確認。"
                                    .formatted(candidatePlace.getName(), need.toMinutes(),
                                            Math.max(gap.toMinutes(), 0)),
                            null));
                }
            });
        }

        // 3. 下一個有地點的行程
        confirmed.stream()
                .filter(other -> other.getPlaceId() != null)
                .filter(other -> !other.getStartAt().isBefore(candidate.getEndAt()))
                .min(Comparator.comparing(ScheduleItem::getStartAt))
                .ifPresent(next -> {
                    Place nextPlace = placeRepository.findById(next.getPlaceId()).orElse(null);
                    if (nextPlace == null) {
                        return;
                    }
                    Duration gap = Duration.between(candidate.getEndAt(), next.getStartAt());
                    Duration need = travelTime(candidatePlace.getLatitude(), candidatePlace.getLongitude(), nextPlace);
                    if (need.compareTo(gap) > 0) {
                        issues.add(new FeasibilityIssue(
                                FeasibilityIssue.Type.TRAVEL_TO_NEXT,
                                "結束後趕往「%s」(%s)約需 %d 分鐘,但只有 %d 分鐘空檔。可提早結束或調整下一行程。"
                                        .formatted(next.getTitle(), nextPlace.getName(),
                                                need.toMinutes(), gap.toMinutes()),
                                next.getId()));
                    }
                });
    }

    private Duration travelTime(double fromLat, double fromLon, Place to) {
        double meters = GeoDistance.metersBetween(fromLat, fromLon, to.getLatitude(), to.getLongitude());
        // 直線距離 ÷ 假設速度 + 固定轉場緩衝(保守估計,寧可誤報)
        long seconds = Math.round(meters / (properties.assumedSpeedKmh() * 1000.0 / 3600.0));
        return Duration.ofSeconds(seconds).plus(properties.transferBuffer());
    }
}
