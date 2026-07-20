package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.health.application.BloodDonationService;
import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord.SourceType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BloodDonationIntentHandler implements IntentHandler {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final BloodDonationService service;
    private final Clock clock;

    public BloodDonationIntentHandler(BloodDonationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return Set.of(IntentCommand.Type.RECORD_BLOOD_DONATION,
                IntentCommand.Type.SET_BLOOD_DONATION_ELIGIBILITY,
                IntentCommand.Type.ASK_BLOOD_DONATION_ELIGIBILITY);
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case RECORD_BLOOD_DONATION -> record(command);
            case SET_BLOOD_DONATION_ELIGIBILITY -> setEligibility(command);
            case ASK_BLOOD_DONATION_ELIGIBILITY -> query(command);
            default -> throw new IllegalArgumentException("unsupported blood donation intent");
        };
    }

    private IntentResult record(IntentCommand command) {
        LocalDate donationDate = date(command.startAt());
        if (donationDate == null) {
            return IntentResult.clarificationNeeded("請告訴我這次捐血的確切日期；我不會從今天自行猜測。");
        }
        LocalDate eligibleDate = date(command.dueAt());
        try {
            service.record(donationDate, command.placeName(), eligibleDate, SourceType.USER);
            String eligibility = eligibleDate == null
                    ? "尚未保存下次最早可捐日，請把捐血單上的日期或你確認過的日期告訴我。"
                    : "下次最早可捐日：" + eligibleDate + "。";
            return IntentResult.message(IntentResult.Action.BLOOD_DONATION_RECORDED,
                    "已記錄 %s 的捐血。%s 若要排下次行程，請再指定日期與時間。"
                            .formatted(donationDate, eligibility));
        } catch (IllegalArgumentException e) {
            return IntentResult.clarificationNeeded("捐血日期或下次最早可捐日不合理，請確認後再告訴我。");
        }
    }

    private IntentResult setEligibility(IntentCommand command) {
        LocalDate eligibleDate = date(command.dueAt());
        if (eligibleDate == null) {
            return IntentResult.clarificationNeeded("請提供下次最早可捐血的確切日期。");
        }
        try {
            var record = service.setLatestEligibleDate(eligibleDate);
            return IntentResult.message(IntentResult.Action.BLOOD_DONATION_RECORDED,
                    "已把最近一次 %s 捐血紀錄的下次最早可捐日設為 %s；這是你提供的日期，不是系統推算。"
                            .formatted(record.getDonationDate(), eligibleDate));
        } catch (IllegalStateException e) {
            return IntentResult.clarificationNeeded("目前還沒有捐血紀錄；請先告訴我最近一次捐血日期。");
        } catch (IllegalArgumentException e) {
            return IntentResult.clarificationNeeded("下次最早可捐日不能早於最近一次捐血日，請再確認日期。");
        }
    }

    private IntentResult query(IntentCommand command) {
        LocalDate target = date(command.startAt());
        if (target == null) target = LocalDate.now(clock.withZone(TAIPEI));
        var result = service.eligibilityOn(target);
        return switch (result.status()) {
            case NO_RECORD -> IntentResult.message(IntentResult.Action.BLOOD_DONATION_INFO,
                    "目前沒有可供比較的捐血紀錄。請告訴我最近一次捐血日，以及確認過的下次最早可捐日。");
            case ELIGIBILITY_DATE_MISSING -> IntentResult.message(
                    IntentResult.Action.BLOOD_DONATION_INFO,
                    "最近一次捐血是 %s，但尚未保存下次最早可捐日，因此不能替你推算；請提供捐血單或血液中心告知的日期。"
                            .formatted(result.record().getDonationDate()));
            case BEFORE_RECORDED_DATE -> IntentResult.message(
                    IntentResult.Action.BLOOD_DONATION_INFO,
                    "依你保存的日期，%s 尚未到門檻；最早是 %s。"
                            .formatted(target, result.record().getNextEligibleDate()));
            case ON_OR_AFTER_RECORDED_DATE -> IntentResult.message(
                    IntentResult.Action.BLOOD_DONATION_INFO,
                    "%s 已到你保存的最早日期 %s；實際能否捐血仍以當日健康狀況與捐血現場評估為準。"
                            .formatted(target, result.record().getNextEligibleDate()));
        };
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(TAIPEI).toLocalDate();
        } catch (java.time.DateTimeException e) {
            try { return LocalDate.parse(value); }
            catch (java.time.DateTimeException ignored) { return null; }
        }
    }
}
