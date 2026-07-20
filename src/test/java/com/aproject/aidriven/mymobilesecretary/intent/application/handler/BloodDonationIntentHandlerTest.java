package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.health.application.BloodDonationService;
import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BloodDonationIntentHandlerTest {
    private final BloodDonationService service = mock(BloodDonationService.class);
    private final BloodDonationIntentHandler handler = new BloodDonationIntentHandler(service,
            Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void queryNeverClaimsMedicalEligibility() {
        LocalDate target = LocalDate.of(2026, 10, 3);
        BloodDonationRecord record = BloodDonationRecord.create(LocalDate.of(2026, 7, 1),
                null, target, BloodDonationRecord.SourceType.USER,
                Instant.parse("2026-07-18T08:00:00Z"));
        when(service.eligibilityOn(target)).thenReturn(new BloodDonationService.Eligibility(
                target, record, BloodDonationService.Status.ON_OR_AFTER_RECORDED_DATE));

        IntentResult result = handler.handle("10月3日可以捐血了嗎",
                command(IntentCommand.Type.ASK_BLOOD_DONATION_ELIGIBILITY,
                        "2026-10-03T00:00:00+08:00", null));

        assertThat(result.action()).isEqualTo(IntentResult.Action.BLOOD_DONATION_INFO);
        assertThat(result.message()).contains("已到你保存的最早日期")
                .contains("現場評估為準");
    }

    @Test
    void missingDonationDateRequiresClarification() {
        IntentResult result = handler.handle("我捐血了",
                command(IntentCommand.Type.RECORD_BLOOD_DONATION, null, null));
        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
    }

    private static IntentCommand command(IntentCommand.Type type, String startAt, String dueAt) {
        return new IntentCommand(type, null, dueAt, startAt, null, null, null, null,
                null, null, null, null, null);
    }
}
