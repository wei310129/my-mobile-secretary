package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelTimeEstimator;
import com.aproject.aidriven.mymobilesecretary.planner.application.WeatherAdvisoryService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 到期前仍須檢查天氣／交通的條件提醒。 */
@Service
@Transactional(readOnly = true)
public class ReminderConditionService {
    private final TaskRepository taskRepository;
    private final PlaceRepository placeRepository;
    private final WeatherAdvisoryService weatherService;
    private final TravelTimeEstimator travelTimeEstimator;
    private final Clock clock;

    public ReminderConditionService(TaskRepository taskRepository, PlaceRepository placeRepository,
                                    WeatherAdvisoryService weatherService,
                                    TravelTimeEstimator travelTimeEstimator, Clock clock) {
        this.taskRepository = taskRepository;
        this.placeRepository = placeRepository;
        this.weatherService = weatherService;
        this.travelTimeEstimator = travelTimeEstimator;
        this.clock = clock;
    }

    public Decision evaluate(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Task", taskId));
        return switch (task.getConditionType()) {
            case NONE -> Decision.TRIGGER;
            case RAIN -> weatherService.isRainy()
                    .map(rainy -> rainy ? Decision.TRIGGER : Decision.SKIP)
                    .orElse(Decision.RETRY);
            case TRAFFIC -> evaluateTraffic(task.getConditionPayload());
        };
    }

    /** payload:fromLat,fromLon,toPlaceId,baselineMinutes。 */
    private Decision evaluateTraffic(String payload) {
        try {
            String[] p = payload.split(",");
            double fromLat = Double.parseDouble(p[0]);
            double fromLon = Double.parseDouble(p[1]);
            Long placeId = Long.valueOf(p[2]);
            long baseline = Long.parseLong(p[3]);
            Place to = placeRepository.findById(placeId).orElse(null);
            if (to == null) {
                return Decision.SKIP;
            }
            long current = travelTimeEstimator.estimate(fromLat, fromLon,
                    to.getLatitude(), to.getLongitude(), Instant.now(clock)).toMinutes();
            return current >= baseline + 10 ? Decision.TRIGGER : Decision.SKIP;
        } catch (Exception e) {
            return Decision.RETRY;
        }
    }

    public enum Decision { TRIGGER, SKIP, RETRY }
}
