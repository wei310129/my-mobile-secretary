package com.aproject.aidriven.mymobilesecretary.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.payment.domain.PaymentNotice;
import com.aproject.aidriven.mymobilesecretary.payment.persistence.PaymentNoticeRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.FlexibleDayTaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.FlexibleDayTaskPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PaymentNoticeServiceTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-07-19T01:00:00Z");

    @Test
    void parsesChineseLeadDaysAndExplicitEveningTime() {
        var request = PaymentNoticeService.parseLeadRequest("到期前三天晚上8點提醒我");

        assertThat(request.days()).isEqualTo(3);
        assertThat(request.time()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void schedulesPendingNoticeWithoutClaimingPayment() {
        PaymentNoticeRepository notices = mock(PaymentNoticeRepository.class);
        FlexibleDayTaskPlanRepository plans = mock(FlexibleDayTaskPlanRepository.class);
        FlexibleDayTaskService flexible = mock(FlexibleDayTaskService.class);
        PaymentNotice notice = PaymentNotice.pending(
                "信用卡帳單", "範例銀行", 3200, LocalDate.of(2026, 7, 30), NOW);
        when(notices.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                ACTOR, PaymentNotice.Status.PENDING_REMINDER)).thenReturn(Optional.of(notice));
        FlexibleDayTaskPlan plan = mock(FlexibleDayTaskPlan.class);
        Task task = mock(Task.class);
        when(plan.getId()).thenReturn(91L);
        when(plan.getRemindAt()).thenReturn(Instant.parse("2026-07-27T12:00:00Z"));
        when(flexible.plan(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FlexibleDayTaskService.PlannedTask(task, plan, false));
        PaymentNoticeService service = new PaymentNoticeService(
                notices, plans, flexible, mock(UniversalLifeRecordService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicBoolean mutation = new AtomicBoolean();

        var result = withContext(() -> service.answer(
                "到期前3天晚上8點提醒我", () -> mutation.set(true)));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().message()).contains("不會自動付款", "2026-07-30")
                .contains("2026-07-27 20:00");
        assertThat(mutation).isTrue();
        assertThat(notice.getStatus()).isEqualTo(PaymentNotice.Status.SCHEDULED);
        assertThat(notice.getReminderLeadDays()).isEqualTo(3);
        verify(notices).save(notice);
    }

    private static <T> T withContext(java.util.concurrent.Callable<T> action) {
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            return action.call();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
