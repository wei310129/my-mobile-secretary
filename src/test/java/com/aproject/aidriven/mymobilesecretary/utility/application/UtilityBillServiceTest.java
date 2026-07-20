package com.aproject.aidriven.mymobilesecretary.utility.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.utility.domain.UtilityBillRecord;
import com.aproject.aidriven.mymobilesecretary.utility.domain.UtilityBillRecord.Status;
import com.aproject.aidriven.mymobilesecretary.utility.persistence.UtilityBillRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UtilityBillServiceTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-07-19T02:00:00Z");

    @Test
    void rocMonthConversionIsDeterministicJavaLogic() {
        assertThat(UtilityBillService.parseBillingMonth("民國 113/03"))
                .contains(YearMonth.of(2024, 3));
        assertThat(UtilityBillService.parseBillingMonth("2024-03"))
                .contains(YearMonth.of(2024, 3));
        assertThat(UtilityBillService.parseBillingMonth("113/13")).isEmpty();
    }

    @Test
    void imageRowsRemainPendingUntilLocationIsConfirmed() {
        UtilityBillRecordRepository repository = mock(UtilityBillRecordRepository.class);
        when(repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(ACTOR, Status.PENDING))
                .thenReturn(List.of());
        UtilityBillService service = service(repository);

        UtilityBillService.CaptureResult captured = withContext(() -> service.capture(
                new ReceiptCommand.UtilityBillInfo("台灣電力公司", List.of(
                        new ReceiptCommand.UtilityBillEntry("113/03", 640, 1248),
                        new ReceiptCommand.UtilityBillEntry("112/07", 728, 0),
                        new ReceiptCommand.UtilityBillEntry("看不清楚", null, null)))));

        assertThat(captured.savedRows()).isEqualTo(2);
        assertThat(captured.preview()).contains("民國 113 年 3 月", "金額未顯示");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UtilityBillRecord>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(rows.getValue()).allMatch(row -> row.getStatus() == Status.PENDING)
                .allMatch(row -> row.getLocationLabel() == null);
    }

    @Test
    void locationConfirmationImportsLatestBatchWithoutGuessing() {
        UtilityBillRecordRepository repository = mock(UtilityBillRecordRepository.class);
        UUID batch = UUID.randomUUID();
        UtilityBillRecord pending = UtilityBillRecord.pending(batch, "台灣電力公司",
                LocalDate.of(2024, 3, 1), 640, 1248, NOW);
        when(repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(ACTOR, Status.PENDING))
                .thenReturn(List.of(pending));
        when(repository.findByCreatedByUserIdAndImportBatchIdAndStatusOrderByBillingMonthDesc(
                ACTOR, batch, Status.PENDING)).thenReturn(List.of(pending));
        when(repository.findFirstByCreatedByUserIdAndStatusAndProviderAndLocationLabelAndBillingMonth(
                ACTOR, Status.CONFIRMED, "台灣電力公司", "家裡", LocalDate.of(2024, 3, 1)))
                .thenReturn(Optional.empty());
        AtomicBoolean mutated = new AtomicBoolean();

        var result = withContext(() -> service(repository).answer(
                "這是家裡的電費", () -> mutated.set(true))).orElseThrow();

        assertThat(result.message()).contains("家裡", "1 筆");
        assertThat(mutated).isTrue();
        assertThat(pending.getStatus()).isEqualTo(Status.CONFIRMED);
        assertThat(pending.getLocationLabel()).isEqualTo("家裡");
    }

    @Test
    void annualQuerySumsKnownAmountsAndDisclosesMissingRows() {
        UtilityBillRecordRepository repository = mock(UtilityBillRecordRepository.class);
        UtilityBillRecord march = confirmed("家裡", LocalDate.of(2024, 3, 1), 640, 1248);
        UtilityBillRecord july = confirmed("家裡", LocalDate.of(2024, 7, 1), 728, null);
        when(repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(ACTOR, Status.PENDING))
                .thenReturn(List.of());
        when(repository.findByCreatedByUserIdAndStatusOrderByBillingMonthAsc(
                ACTOR, Status.CONFIRMED))
                .thenReturn(List.of(march, july));

        var result = withContext(() -> service(repository).answer(
                "113年各月份電費與當年度總和", () -> { })).orElseThrow();

        assertThat(result.message()).contains("03 月：NT$ 1,248", "07 月：金額未顯示")
                .contains("不能當作完整年度總額");
    }

    @Test
    void recentConversationPhrasesRouteToUtilityHistoryInsteadOfGenericQueries() {
        UtilityBillRecordRepository repository = historyRepository();
        UtilityBillService service = service(repository);

        assertThat(answer(service, "我家113年電費"))
                .contains("民國 113 年", "01 月：NT$ 1,321", "03 月：NT$ 1,248", "合計 NT$ 2,569");
        assertThat(answer(service, "我家民國113年度電費"))
                .contains("民國 113 年", "已記錄 2 個月份");
        assertThat(answer(service, "給我我家歷年9月電費"))
                .contains("9 月的歷年", "民國 112 年 9 月：NT$ 3,337");
        assertThat(answer(service, "我家以前有電費超過3000嗎？"))
                .contains("有 1 個月份", "超過 NT$ 3,000", "NT$ 3,337");
    }

    @Test
    void commonHistorySummaryAndIntegratedQueriesRemainDeterministic() {
        UtilityBillService service = service(historyRepository());

        assertThat(answer(service, "我家最新電費"))
                .contains("民國 113 年 3 月", "NT$ 1,248");
        assertThat(answer(service, "我家最高電費"))
                .contains("最高", "民國 112 年 9 月", "NT$ 3,337");
        assertThat(answer(service, "我家平均電費是多少"))
                .contains("4 筆已知金額", "NT$ 1,890");
        assertThat(answer(service, "我家113年跟112年電費比較"))
                .contains("民國 113 年", "NT$ 2,569", "民國 112 年", "NT$ 4,992")
                .contains("前者少 NT$ 2,423");
        assertThat(answer(service, "查看我家全部電費紀錄"))
                .contains("全部電費紀錄", "民國 112 年 7 月", "民國 113 年 3 月");
    }

    @Test
    void commaSeparatedRocYearsReturnIndividualDetailsAndTotals() {
        UtilityBillService service = service(historyRepository());

        String answer = answer(service, "給我113、112兩年個別的的家裡電費明細與總和");

        assertThat(answer).contains("多年度電費明細", "民國 113 年", "合計 NT$ 2,569")
                .contains("民國 112 年", "小計 NT$ 4,992", "1 個月份金額未顯示")
                .doesNotContain("民國 115 年");
        assertThat(answer(service, "給我 113 和 112 年各自電費明細"))
                .contains("民國 113 年", "民國 112 年");
        assertThat(answer(service,
                "我是問你「給我113、112兩年個別的的家裡電費明細與總和」，根本沒有提到115年"))
                .contains("民國 113 年", "民國 112 年")
                .doesNotContain("民國 115 年");
    }

    @Test
    void relativeAndExactMonthQueriesWorkButPaymentTasksAreNotIntercepted() {
        UtilityBillRecordRepository repository = mock(UtilityBillRecordRepository.class);
        UtilityBillRecord june = confirmed("我家", LocalDate.of(2026, 6, 1), 500, 990);
        when(repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(ACTOR, Status.PENDING))
                .thenReturn(List.of());
        when(repository.findByCreatedByUserIdAndStatusOrderByBillingMonthAsc(ACTOR, Status.CONFIRMED))
                .thenReturn(List.of(june));
        UtilityBillService service = service(repository);

        assertThat(answer(service, "我家上個月電費"))
                .contains("民國 115 年 6 月", "NT$ 990");
        assertThat(answer(service, "我家2026年6月電費"))
                .contains("民國 115 年 6 月", "NT$ 990");
        assertThat(withContext(() -> service.answer("週五前要繳電費", () -> { }))).isEmpty();
    }

    private static UtilityBillRecordRepository historyRepository() {
        UtilityBillRecordRepository repository = mock(UtilityBillRecordRepository.class);
        List<UtilityBillRecord> rows = List.of(
                confirmed("我家", LocalDate.of(2023, 7, 1), 728, null),
                confirmed("我家", LocalDate.of(2023, 9, 1), 1153, 3337),
                confirmed("我家", LocalDate.of(2023, 11, 1), 753, 1655),
                confirmed("我家", LocalDate.of(2024, 1, 1), 670, 1321),
                confirmed("我家", LocalDate.of(2024, 3, 1), 640, 1248));
        when(repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(ACTOR, Status.PENDING))
                .thenReturn(List.of());
        when(repository.findByCreatedByUserIdAndStatusOrderByBillingMonthAsc(ACTOR, Status.CONFIRMED))
                .thenReturn(rows);
        return repository;
    }

    private static String answer(UtilityBillService service, String text) {
        return withContext(() -> service.answer(text, () -> { })).orElseThrow().message();
    }

    private static UtilityBillRecord confirmed(String location, LocalDate month,
                                                Integer usage, Integer amount) {
        UtilityBillRecord row = UtilityBillRecord.pending(
                UUID.randomUUID(), "台灣電力公司", month, usage, amount, NOW);
        row.confirm(location, NOW);
        return row;
    }

    private static UtilityBillService service(UtilityBillRecordRepository repository) {
        return new UtilityBillService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
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
