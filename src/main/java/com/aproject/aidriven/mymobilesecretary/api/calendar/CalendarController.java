package com.aproject.aidriven.mymobilesecretary.api.calendar;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarActivityDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeRevisionView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/calendar")
public class CalendarController {

    private final CalendarApplicationService service;

    public CalendarController(CalendarApplicationService service) {
        this.service = service;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarPlanView createPlan(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreatePlanRequest request) {
        return service.createPlan(request.toCommand(idempotencyKey));
    }

    @GetMapping("/plans/current")
    public CalendarPlanView getPlan(@RequestHeader("Plan-Key") String planKey) {
        return service.getPlan(planKey);
    }

    @PatchMapping("/plans/current/category")
    public CalendarPlanView changeCategory(
            @RequestHeader("Plan-Key") String planKey,
            @RequestBody CategoryRevisionRequest request) {
        return service.changePlanCategory(planKey, request.category(), request.expectedRevision());
    }

    @PatchMapping("/plans/current/online-link")
    public CalendarPlanView setOnlineLink(
            @RequestHeader("Plan-Key") String planKey,
            @RequestBody OnlineLinkRequest request) {
        return service.setPlanOnlineLink(planKey, request.uri(), request.label());
    }

    @PatchMapping("/plans/current/nodes/{nodeKey}/move")
    public CalendarNodeRevisionView moveNode(
            @RequestHeader("Plan-Key") String planKey,
            @org.springframework.web.bind.annotation.PathVariable String nodeKey,
            @RequestBody NodeRevisionRequest request) {
        return service.moveNodeOrdinarily(
                planKey, nodeKey, request.time(), request.expectedRevision());
    }

    @PatchMapping("/plans/current/nodes/{nodeKey}/revision")
    public CalendarNodeRevisionView reviseNode(
            @RequestHeader("Plan-Key") String planKey,
            @org.springframework.web.bind.annotation.PathVariable String nodeKey,
            @RequestBody NodeRevisionRequest request) {
        return service.reviseLockedNode(
                planKey, nodeKey, request.time(), request.expectedRevision());
    }

    public record CategoryRevisionRequest(String category, long expectedRevision) {}

    public record OnlineLinkRequest(String uri, String label) {}

    public record NodeRevisionRequest(Instant time, long expectedRevision) {}

    public record CreatePlanRequest(
            String title,
            PlacementRequest placement,
            String category,
            String onlineLink,
            String onlineLinkLabel,
            List<ActivityRequest> activities,
            List<NodeRequest> nodes) {

        CreateCalendarPlanCommand toCommand(String requestKey) {
            try {
                return new CreateCalendarPlanCommand(
                        requestKey,
                        title,
                        placement.toDomain(),
                        category,
                        onlineLink,
                        onlineLinkLabel,
                        activities == null
                                ? List.of()
                                : activities.stream().map(ActivityRequest::toDraft).toList(),
                        nodes == null
                                ? List.of()
                                : nodes.stream().map(NodeRequest::toDraft).toList());
            } catch (NullPointerException | IllegalArgumentException invalid) {
                throw new HttpMessageNotReadableException(
                        "Invalid calendar request", invalid, null);
            }
        }
    }

    public record ActivityRequest(
            String title,
            PlacementRequest placement,
            String category,
            List<NodeRequest> nodes) {

        CalendarActivityDraft toDraft() {
            return new CalendarActivityDraft(
                    title,
                    placement.toDomain(),
                    category,
                    nodes == null
                            ? List.of()
                            : nodes.stream().map(NodeRequest::toDraft).toList());
        }
    }

    public record PlacementRequest(
            CalendarPlacement.Kind kind,
            Instant start,
            Instant end,
            String zoneId,
            LocalDate allDayStart,
            LocalDate allDayEndExclusive) {

        CalendarPlacement toDomain() {
            return switch (kind) {
                case TIMED_INTERVAL ->
                    CalendarPlacement.interval(start, end, ZoneId.of(zoneId));
                case TIMED_POINT -> CalendarPlacement.point(start, ZoneId.of(zoneId));
                case ALL_DAY -> CalendarPlacement.allDay(allDayStart, allDayEndExclusive);
            };
        }
    }

    public record NodeRequest(
            String nodeKey,
            String label,
            String expressionKind,
            Instant absoluteTime,
            CalendarTimeNode.OwnerBoundary boundary,
            Long offsetSeconds,
            String baseNodeKey,
            Criticality criticality,
            Adjustability adjustability) {

        CalendarNodeDraft toDraft() {
            CalendarTimeNode node = switch (expressionKind) {
                case "ABSOLUTE" -> CalendarTimeNode.absolute(nodeKey, label, absoluteTime);
                case "OWNER_OFFSET" -> CalendarTimeNode.relativeToOwner(
                        nodeKey,
                        label,
                        boundary,
                        Duration.ofSeconds(offsetSeconds),
                        criticality,
                        adjustability);
                case "NODE_OFFSET" -> CalendarTimeNode.relativeToNode(
                        nodeKey,
                        label,
                        baseNodeKey,
                        Duration.ofSeconds(offsetSeconds),
                        criticality,
                        adjustability);
                default -> throw new IllegalArgumentException("Unknown time expression");
            };
            return CalendarNodeDraft.of(node);
        }
    }
}
