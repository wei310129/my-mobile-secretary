package com.aproject.aidriven.mymobilesecretary.schedule.conditional.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceResolution;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceRule;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.OfficialDayStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.persistence.ConditionalRecurrenceResolutionRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.persistence.ConditionalRecurrenceRuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConditionalRecurrenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant FRIDAY_START = Instant.parse("2026-07-24T02:00:00Z");
    private static final Instant FRIDAY_END = Instant.parse("2026-07-24T03:00:00Z");
    private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 24);
    private static final Long RULE_ID = 41L;

    private ConditionalRecurrenceRuleRepository ruleRepository;
    private ConditionalRecurrenceResolutionRepository resolutionRepository;
    private Map<Key, OfficialDayStatus> officialFacts;
    private OfficialCalendarGateway gateway;
    private ConditionalRecurrenceService service;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(ConditionalRecurrenceRuleRepository.class);
        resolutionRepository = mock(ConditionalRecurrenceResolutionRepository.class);
        officialFacts = new HashMap<>();
        gateway = mock(OfficialCalendarGateway.class);
        when(gateway.query(any(), any(), any())).thenAnswer(call -> {
            OfficialDayStatus.Fact fact = call.getArgument(0);
            LocalDate date = call.getArgument(1);
            String jurisdiction = call.getArgument(2);
            return officialFacts.getOrDefault(
                    new Key(fact, date, jurisdiction), OfficialDayStatus.unknown(fact, date));
        });
        service = new ConditionalRecurrenceService(
                ruleRepository,
                resolutionRepository,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(ruleRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(resolutionRepository.findByRuleIdAndBaseDate(any(), any()))
                .thenReturn(Optional.empty());
        when(resolutionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createDraftDoesNotMasqueradeAsAnOrdinaryWeeklySchedule() {
        ConditionalRecurrenceRule rule = service.createDraft(
                "週會",
                FRIDAY_START,
                FRIDAY_END,
                null,
                ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY,
                ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY,
                "臺北市");

        assertThat(rule.getStatus()).isEqualTo(ConditionalRecurrenceRule.Status.DRAFT);
        assertThat(rule.getHolidayPolicy())
                .isEqualTo(ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY);
        assertThat(rule.getClosurePolicy())
                .isEqualTo(ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY);
        verify(ruleRepository).save(rule);
    }

    @Test
    void draftRuleNeverQueriesOrProducesAnOccurrence() {
        ConditionalRecurrenceRule rule = rule(false);
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule));

        ConditionalRecurrenceService.ResolutionDecision decision = service.resolve(RULE_ID, FRIDAY);

        assertThat(decision.readyForScheduleProposal()).isFalse();
        assertThat(decision.resolution().getStatus())
                .isEqualTo(ConditionalRecurrenceResolution.Status.RULE_NOT_ACTIVE);
        assertThat(decision.resolution().getResolvedStartAt()).isNull();
        verifyNoInteractions(gateway);
    }

    @Test
    void unknownFutureOfficialStatusRemainsWaitingWithoutInventingASource() {
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule(true)));

        ConditionalRecurrenceService.ResolutionDecision decision = service.resolve(RULE_ID, FRIDAY);

        assertThat(decision.readyForScheduleProposal()).isFalse();
        assertThat(decision.resolution().getStatus())
                .isEqualTo(ConditionalRecurrenceResolution.Status.WAITING_OFFICIAL_CONFIRMATION);
        assertThat(decision.resolution().getReason()).contains("官方狀態尚未確認", "不建立或移動行程");
        assertThat(decision.resolution().getOfficialSourceSnapshot()).isNull();
        assertThat(decision.resolution().getResolvedStartAt()).isNull();
    }

    @Test
    void confirmedNationalHolidayMovesToPreviousBusinessDayAndKeepsReasonAndEvidence() {
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule(true)));
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY, FRIDAY, "全國", true);
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY, FRIDAY.minusDays(1), "全國", false);
        fact(OfficialDayStatus.Fact.TYPHOON_WORK_SCHOOL_CLOSURE,
                FRIDAY.minusDays(1), "臺北市", false);

        ConditionalRecurrenceService.ResolutionDecision decision = service.resolve(RULE_ID, FRIDAY);

        assertThat(decision.readyForScheduleProposal()).isTrue();
        assertThat(decision.resolution().getResolvedStartAt())
                .isEqualTo(Instant.parse("2026-07-23T02:00:00Z"));
        assertThat(decision.resolution().getResolvedEndAt())
                .isEqualTo(Instant.parse("2026-07-23T03:00:00Z"));
        assertThat(decision.resolution().getReason())
                .contains("國定假日", "2026-07-24", "提前至 2026-07-23");
        assertThat(decision.resolution().getOfficialSourceSnapshot())
                .contains("政府測試來源", "NATIONAL_HOLIDAY", "TYPHOON_WORK_SCHOOL_CLOSURE");
    }

    @Test
    void confirmedNationalHolidayCanSkipOccurrenceWithoutCreatingMakeup() {
        ConditionalRecurrenceRule skipRule = ConditionalRecurrenceRule.draft(
                "英文課", FRIDAY_START, FRIDAY_END, LocalDate.of(2026, 12, 31),
                ConditionalRecurrenceRule.HolidayPolicy.SKIP,
                ConditionalRecurrenceRule.ClosurePolicy.NONE, null, NOW);
        skipRule.activate(NOW);
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(skipRule));
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY, FRIDAY, "全國", true);

        ConditionalRecurrenceService.ResolutionDecision decision = service.resolve(RULE_ID, FRIDAY);

        assertThat(decision.readyForScheduleProposal()).isFalse();
        assertThat(decision.resolution().getStatus())
                .isEqualTo(ConditionalRecurrenceResolution.Status.SKIPPED);
        assertThat(decision.resolution().getResolvedStartAt()).isNull();
        assertThat(decision.resolution().getReason())
                .contains("國定假日", "跳過本次", "補課不自動建立");
        assertThat(decision.resolution().getOfficialSourceSnapshot()).contains("政府測試來源");
    }

    @Test
    void confirmedTyphoonClosureSkipsWeekendAndMovesToNextConfirmedBusinessDay() {
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule(true)));
        LocalDate monday = LocalDate.of(2026, 7, 27);
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY, FRIDAY, "全國", false);
        fact(OfficialDayStatus.Fact.TYPHOON_WORK_SCHOOL_CLOSURE, FRIDAY, "臺北市", true);
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY, monday, "全國", false);
        fact(OfficialDayStatus.Fact.TYPHOON_WORK_SCHOOL_CLOSURE, monday, "臺北市", false);

        ConditionalRecurrenceService.ResolutionDecision decision = service.resolve(RULE_ID, FRIDAY);

        assertThat(decision.readyForScheduleProposal()).isTrue();
        assertThat(decision.resolution().getResolvedStartAt())
                .isEqualTo(Instant.parse("2026-07-27T02:00:00Z"));
        assertThat(decision.resolution().getReason())
                .contains("臺北市 已確認停班停課", "順延至 2026-07-27");
    }

    @Test
    void unknownStatusOnCandidateBusinessDayBlocksTyphoonShift() {
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule(true)));
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY, FRIDAY, "全國", false);
        fact(OfficialDayStatus.Fact.TYPHOON_WORK_SCHOOL_CLOSURE, FRIDAY, "臺北市", true);
        fact(OfficialDayStatus.Fact.NATIONAL_HOLIDAY,
                LocalDate.of(2026, 7, 27), "全國", false);

        ConditionalRecurrenceService.ResolutionDecision decision = service.resolve(RULE_ID, FRIDAY);

        assertThat(decision.readyForScheduleProposal()).isFalse();
        assertThat(decision.resolution().getStatus())
                .isEqualTo(ConditionalRecurrenceResolution.Status.WAITING_OFFICIAL_CONFIRMATION);
        assertThat(decision.resolution().getReason())
                .contains("2026-07-27", "臺北市停班停課", "尚未確認");
        assertThat(decision.resolution().getResolvedStartAt()).isNull();
    }

    @Test
    void confirmedOfficialFactRequiresRealSourceEvidence() {
        assertThatThrownBy(() -> new OfficialDayStatus(
                OfficialDayStatus.Fact.NATIONAL_HOLIDAY,
                FRIDAY,
                OfficialDayStatus.Verdict.CONFIRMED_TRUE,
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source evidence");
    }

    private ConditionalRecurrenceRule rule(boolean activate) {
        ConditionalRecurrenceRule rule = ConditionalRecurrenceRule.draft(
                "週會",
                FRIDAY_START,
                FRIDAY_END,
                null,
                ConditionalRecurrenceRule.HolidayPolicy.PREVIOUS_BUSINESS_DAY,
                ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY,
                "臺北市",
                NOW);
        if (activate) {
            rule.activate(NOW);
        }
        return rule;
    }

    private void fact(
            OfficialDayStatus.Fact fact,
            LocalDate date,
            String jurisdiction,
            boolean value) {
        officialFacts.put(new Key(fact, date, jurisdiction), new OfficialDayStatus(
                fact,
                date,
                value
                        ? OfficialDayStatus.Verdict.CONFIRMED_TRUE
                        : OfficialDayStatus.Verdict.CONFIRMED_FALSE,
                "政府測試來源",
                NOW));
    }

    private record Key(OfficialDayStatus.Fact fact, LocalDate date, String jurisdiction) {
    }
}
