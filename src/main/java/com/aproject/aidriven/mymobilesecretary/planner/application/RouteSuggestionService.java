package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeoDistance;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.GeofenceRuleRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用「額外繞路距離」近似回家／目的地方向上的順路待辦。 */
@Service
@Transactional(readOnly = true)
public class RouteSuggestionService {
    private final TaskService taskService;
    private final GeofenceRuleRepository ruleRepository;
    private final PlaceRepository placeRepository;
    private final LocationEventRepository locationRepository;

    public RouteSuggestionService(TaskService taskService, GeofenceRuleRepository ruleRepository,
                                  PlaceRepository placeRepository,
                                  LocationEventRepository locationRepository) {
        this.taskService = taskService;
        this.ruleRepository = ruleRepository;
        this.placeRepository = placeRepository;
        this.locationRepository = locationRepository;
    }

    public String suggest(Place destination) {
        var start = locationRepository.findTopByOrderByOccurredAtDesc().orElse(null);
        if (start == null) return "我還不知道你目前的位置,先回報位置才能算順路。";
        double direct = GeoDistance.metersBetween(start.getLatitude(), start.getLongitude(),
                destination.getLatitude(), destination.getLongitude());
        List<Candidate> candidates = taskService.listOpenTasks().stream().flatMap(task ->
                ruleRepository.findByTaskId(task.getId()).stream()
                        .map(rule -> placeRepository.findById(rule.getPlaceId()).orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .map(place -> {
                            double detour = GeoDistance.metersBetween(start.getLatitude(), start.getLongitude(),
                                    place.getLatitude(), place.getLongitude())
                                    + GeoDistance.metersBetween(place.getLatitude(), place.getLongitude(),
                                    destination.getLatitude(), destination.getLongitude()) - direct;
                            return new Candidate(task.getTitle(), place.getName(), Math.max(0, detour));
                        })).filter(c -> c.detourMeters() <= 2000)
                .sorted(Comparator.comparingDouble(Candidate::detourMeters)).limit(5).toList();
        if (candidates.isEmpty()) return "往「%s」的方向目前沒有適合順便做的待辦。".formatted(destination.getName());
        String lines = java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(i -> "%d.「%s」— %s,約多繞 %d 公尺".formatted(i + 1,
                        candidates.get(i).title(), candidates.get(i).place(),
                        Math.round(candidates.get(i).detourMeters())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return "往「%s」順路可以做:\n%s".formatted(destination.getName(), lines);
    }

    private record Candidate(String title, String place, double detourMeters) {}
}
