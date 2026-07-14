package com.aproject.aidriven.mymobilesecretary.api.schedule;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行程 API:提出即驗算(要可行才放行),不可行時回警告與選項。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleFollowUpService followUpService;

    public ScheduleController(ScheduleService scheduleService, ScheduleFollowUpService followUpService) {
        this.scheduleService = scheduleService;
        this.followUpService = followUpService;
    }

    /** 提出行程 → 201;可行自動 CONFIRMED,不可行留 PROPOSED + issues。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDecisionResponse createSchedule(@Valid @RequestBody CreateScheduleRequest request) {
        return ScheduleDecisionResponse.from(scheduleService.createSchedule(
                request.title(), request.startAt(), request.endAt(), request.placeId()));
    }

    /** 列出行程(可用 ?status=PENDING 過濾 pending 池)。 */
    @GetMapping
    public List<ScheduleResponse> listSchedules(@RequestParam(required = false) ScheduleStatus status) {
        return scheduleService.listSchedules(status).stream().map(ScheduleResponse::from).toList();
    }

    /** 查單一行程;不存在 → 404。 */
    @GetMapping("/{scheduleId}")
    public ScheduleResponse getSchedule(@PathVariable Long scheduleId) {
        return ScheduleResponse.from(scheduleService.getSchedule(scheduleId));
    }

    /** 改時間 → 重新驗算(可行自動 CONFIRMED)。 */
    @PatchMapping("/{scheduleId}/reschedule")
    public ScheduleDecisionResponse reschedule(@PathVariable Long scheduleId,
                                               @Valid @RequestBody RescheduleRequest request) {
        return ScheduleDecisionResponse.from(
                scheduleService.reschedule(scheduleId, request.startAt(), request.endAt()));
    }

    /** 看過警告後強制確認。 */
    @PatchMapping("/{scheduleId}/confirm")
    public ScheduleResponse confirm(@PathVariable Long scheduleId) {
        return ScheduleResponse.from(scheduleService.confirmSchedule(scheduleId));
    }

    /** 暫無想法 → pending 池。 */
    @PatchMapping("/{scheduleId}/park")
    public ScheduleResponse park(@PathVariable Long scheduleId) {
        return ScheduleResponse.from(scheduleService.parkSchedule(scheduleId));
    }

    /** 放棄提案。 */
    @PatchMapping("/{scheduleId}/reject")
    public ScheduleResponse reject(@PathVariable Long scheduleId) {
        return ScheduleResponse.from(scheduleService.rejectSchedule(scheduleId));
    }

    /** 取消已確認行程。 */
    @PatchMapping("/{scheduleId}/cancel")
    public ScheduleResponse cancel(@PathVariable Long scheduleId) {
        return ScheduleResponse.from(scheduleService.cancelSchedule(scheduleId));
    }

    /** 完成行程。 */
    @PatchMapping("/{scheduleId}/complete")
    public ScheduleResponse complete(@PathVariable Long scheduleId) {
        return ScheduleResponse.from(scheduleService.completeSchedule(scheduleId));
    }

    /** 回報行程實際結果(準時/超時多久/原因)→ 201;重複回報 → 422(業務錯誤)。 */
    @PostMapping("/{scheduleId}/outcome")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleOutcomeResponse recordOutcome(@PathVariable Long scheduleId,
                                                 @Valid @RequestBody RecordOutcomeRequest request) {
        return ScheduleOutcomeResponse.from(followUpService.recordOutcome(
                scheduleId, request.onTime(), request.overrunMinutes(), request.reason(), request.note()));
    }

    /** 查行程結果;尚未回報 → 404。 */
    @GetMapping("/{scheduleId}/outcome")
    public ScheduleOutcomeResponse getOutcome(@PathVariable Long scheduleId) {
        return ScheduleOutcomeResponse.from(followUpService.getOutcome(scheduleId));
    }
}
