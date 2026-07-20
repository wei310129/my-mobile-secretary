package com.aproject.aidriven.mymobilesecretary.schedule.conditional.application;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceResolution;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceRule;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.OfficialDayStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.persistence.ConditionalRecurrenceResolutionRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.conditional.persistence.ConditionalRecurrenceRuleRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and resolves official-fact-dependent recurrences without pretending they are ordinary
 * weekly schedules. A caller may propose a concrete schedule only for a {@link
 * ConditionalRecurrenceResolution.Status#READY} result.
 */
@Service
@Transactional
public class ConditionalRecurrenceService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String NATIONAL = "全國";

    private final ConditionalRecurrenceRuleRepository ruleRepository;
    private final ConditionalRecurrenceResolutionRepository resolutionRepository;
    private final OfficialCalendarGateway officialCalendarGateway;
    private final Clock clock;

    public ConditionalRecurrenceService(
            ConditionalRecurrenceRuleRepository ruleRepository,
            ConditionalRecurrenceResolutionRepository resolutionRepository,
            OfficialCalendarGateway officialCalendarGateway,
            Clock clock) {
        this.ruleRepository = ruleRepository;
        this.resolutionRepository = resolutionRepository;
        this.officialCalendarGateway = officialCalendarGateway;
        this.clock = clock;
    }

    /** Saves a draft only. Explicit activation is required before any occurrence can be ready. */
    public ConditionalRecurrenceRule createDraft(
            String title,
            Instant anchorStartAt,
            Instant anchorEndAt,
            LocalDate recurrenceUntil,
            ConditionalRecurrenceRule.HolidayPolicy holidayPolicy,
            ConditionalRecurrenceRule.ClosurePolicy closurePolicy,
            String closureJurisdiction) {
        if (holidayPolicy == ConditionalRecurrenceRule.HolidayPolicy.NONE
                && closurePolicy == ConditionalRecurrenceRule.ClosurePolicy.NONE) {
            throw new IllegalArgumentException("at least one conditional recurrence policy is required");
        }
        Instant now = Instant.now(clock);
        return ruleRepository.save(ConditionalRecurrenceRule.draft(
                title,
                anchorStartAt,
                anchorEndAt,
                recurrenceUntil,
                holidayPolicy,
                closurePolicy,
                closureJurisdiction,
                now));
    }

    public ConditionalRecurrenceRule activate(Long ruleId) {
        ConditionalRecurrenceRule rule = getRule(ruleId);
        rule.activate(Instant.now(clock));
        return rule;
    }

    /**
     * Resolves one unadjusted weekly date. UNKNOWN official facts always produce a waiting result;
     * they are never interpreted as a normal workday.
     */
    public ResolutionDecision resolve(Long ruleId, LocalDate baseDate) {
        ConditionalRecurrenceRule rule = getRule(ruleId);
        Instant now = Instant.now(clock);
        Evaluation evaluation = evaluate(rule, baseDate);
        ConditionalRecurrenceResolution resolution = resolutionRepository
                .findByRuleIdAndBaseDate(ruleId, baseDate)
                .orElseGet(() -> ConditionalRecurrenceResolution.create(ruleId, baseDate, now));
        resolution.record(
                evaluation.status(),
                evaluation.startAt(),
                evaluation.endAt(),
                evaluation.reason(),
                evaluation.sourceSnapshot(),
                now);
        return new ResolutionDecision(resolutionRepository.save(resolution),
                evaluation.status() == ConditionalRecurrenceResolution.Status.READY);
    }

    private Evaluation evaluate(ConditionalRecurrenceRule rule, LocalDate baseDate) {
        if (baseDate == null) {
            throw new IllegalArgumentException("base occurrence date is required");
        }
        if (rule.getStatus() != ConditionalRecurrenceRule.Status.ACTIVE) {
            return Evaluation.unresolved(
                    ConditionalRecurrenceResolution.Status.RULE_NOT_ACTIVE,
                    "條件式週期尚未啟用；不建立或移動行程。",
                    null);
        }
        LocalDate anchorDate = rule.getAnchorStartAt().atZone(TAIPEI).toLocalDate();
        if (baseDate.isBefore(anchorDate)
                || baseDate.getDayOfWeek() != anchorDate.getDayOfWeek()
                || (rule.getRecurrenceUntil() != null
                && baseDate.isAfter(rule.getRecurrenceUntil()))) {
            return Evaluation.unresolved(
                    ConditionalRecurrenceResolution.Status.OUTSIDE_RULE_RANGE,
                    "指定日期不在這條每週條件規則的有效範圍；不建立行程。",
                    null);
        }

        ResolutionContext context = new ResolutionContext();
        LocalDate resolvedDate = baseDate;
        if (rule.getHolidayPolicy() != ConditionalRecurrenceRule.HolidayPolicy.NONE) {
            OfficialDayStatus holiday = query(
                    OfficialDayStatus.Fact.NATIONAL_HOLIDAY,
                    resolvedDate,
                    NATIONAL,
                    context);
            if (holiday.verdict() == OfficialDayStatus.Verdict.UNKNOWN) {
                return waiting(holiday, NATIONAL, context);
            }
            if (holiday.verdict() == OfficialDayStatus.Verdict.CONFIRMED_TRUE) {
                if (rule.getHolidayPolicy() == ConditionalRecurrenceRule.HolidayPolicy.SKIP) {
                    return Evaluation.unresolved(
                            ConditionalRecurrenceResolution.Status.SKIPPED,
                            "%s 已確認為國定假日，依規則跳過本次；補課不自動建立，待使用者另行提供。"
                                    .formatted(resolvedDate),
                            sourceSnapshot(context.evidence));
                }
                LocalDate previous = previousBusinessDay(resolvedDate, context);
                if (previous == null) {
                    return context.waiting;
                }
                context.reasons.add("國定假日，依規則由 %s 提前至 %s"
                        .formatted(resolvedDate, previous));
                resolvedDate = previous;
            }
        }

        if (rule.getClosurePolicy() == ConditionalRecurrenceRule.ClosurePolicy.NEXT_BUSINESS_DAY) {
            OfficialDayStatus closure = query(
                    OfficialDayStatus.Fact.TYPHOON_WORK_SCHOOL_CLOSURE,
                    resolvedDate,
                    rule.getClosureJurisdiction(),
                    context);
            if (closure.verdict() == OfficialDayStatus.Verdict.UNKNOWN) {
                return waiting(closure, rule.getClosureJurisdiction(), context);
            }
            if (closure.verdict() == OfficialDayStatus.Verdict.CONFIRMED_TRUE) {
                LocalDate next = nextConfirmedBusinessDay(
                        resolvedDate, rule.getClosureJurisdiction(), context);
                if (next == null) {
                    return context.waiting;
                }
                context.reasons.add("%s 已確認停班停課，依規則由 %s 順延至 %s"
                        .formatted(rule.getClosureJurisdiction(), resolvedDate, next));
                resolvedDate = next;
            }
        }

        ZonedDateTime anchor = rule.getAnchorStartAt().atZone(TAIPEI);
        Instant startAt = resolvedDate.atTime(anchor.toLocalTime()).atZone(TAIPEI).toInstant();
        Instant endAt = startAt.plusSeconds(rule.getDurationMinutes() * 60L);
        String reason = context.reasons.isEmpty()
                ? "官方條件已確認，日期維持 %s".formatted(baseDate)
                : String.join("；", context.reasons);
        return new Evaluation(
                ConditionalRecurrenceResolution.Status.READY,
                startAt,
                endAt,
                reason,
                sourceSnapshot(context.evidence));
    }

    private LocalDate previousBusinessDay(LocalDate date, ResolutionContext context) {
        LocalDate candidate = date.minusDays(1);
        for (int checked = 0; checked < 14; checked++, candidate = candidate.minusDays(1)) {
            if (isWeekend(candidate)) {
                continue;
            }
            OfficialDayStatus holiday = query(
                    OfficialDayStatus.Fact.NATIONAL_HOLIDAY, candidate, NATIONAL, context);
            if (holiday.verdict() == OfficialDayStatus.Verdict.UNKNOWN) {
                context.waiting = waiting(holiday, NATIONAL, context);
                return null;
            }
            if (holiday.verdict() == OfficialDayStatus.Verdict.CONFIRMED_FALSE) {
                return candidate;
            }
        }
        context.waiting = Evaluation.unresolved(
                ConditionalRecurrenceResolution.Status.WAITING_OFFICIAL_CONFIRMATION,
                "無法在有限範圍內確認前一個上班日；請人工確認，且不建立行程。",
                sourceSnapshot(context.evidence));
        return null;
    }

    private LocalDate nextConfirmedBusinessDay(
            LocalDate date, String jurisdiction, ResolutionContext context) {
        LocalDate candidate = date.plusDays(1);
        for (int checked = 0; checked < 14; checked++, candidate = candidate.plusDays(1)) {
            if (isWeekend(candidate)) {
                continue;
            }
            OfficialDayStatus holiday = query(
                    OfficialDayStatus.Fact.NATIONAL_HOLIDAY, candidate, NATIONAL, context);
            if (holiday.verdict() == OfficialDayStatus.Verdict.UNKNOWN) {
                context.waiting = waiting(holiday, NATIONAL, context);
                return null;
            }
            if (holiday.verdict() == OfficialDayStatus.Verdict.CONFIRMED_TRUE) {
                continue;
            }
            OfficialDayStatus closure = query(
                    OfficialDayStatus.Fact.TYPHOON_WORK_SCHOOL_CLOSURE,
                    candidate,
                    jurisdiction,
                    context);
            if (closure.verdict() == OfficialDayStatus.Verdict.UNKNOWN) {
                context.waiting = waiting(closure, jurisdiction, context);
                return null;
            }
            if (closure.verdict() == OfficialDayStatus.Verdict.CONFIRMED_FALSE) {
                return candidate;
            }
        }
        context.waiting = Evaluation.unresolved(
                ConditionalRecurrenceResolution.Status.WAITING_OFFICIAL_CONFIRMATION,
                "無法在有限範圍內確認下一個上班日；請人工確認，且不建立行程。",
                sourceSnapshot(context.evidence));
        return null;
    }

    private Evaluation waiting(
            OfficialDayStatus status, String jurisdiction, ResolutionContext context) {
        String label = status.fact() == OfficialDayStatus.Fact.NATIONAL_HOLIDAY
                ? "國定假日"
                : jurisdiction + "停班停課";
        return Evaluation.unresolved(
                ConditionalRecurrenceResolution.Status.WAITING_OFFICIAL_CONFIRMATION,
                "%s 的 %s官方狀態尚未確認；請等待公告或人工確認，不建立或移動行程。"
                        .formatted(status.date(), label),
                sourceSnapshot(context.evidence));
    }

    private OfficialDayStatus query(
            OfficialDayStatus.Fact fact,
            LocalDate date,
            String jurisdiction,
            ResolutionContext context) {
        OfficialDayStatus status = officialCalendarGateway.query(fact, date, jurisdiction);
        if (status == null || status.fact() != fact || !status.date().equals(date)) {
            status = OfficialDayStatus.unknown(fact, date);
        }
        context.evidence.add(status);
        return status;
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static String sourceSnapshot(List<OfficialDayStatus> statuses) {
        List<String> values = statuses.stream()
                .filter(status -> status.sourceName() != null && status.observedAt() != null)
                .map(status -> "%s@%s=%s|%s|%s".formatted(
                        status.fact(),
                        status.date(),
                        status.verdict(),
                        status.sourceName(),
                        status.observedAt()))
                .distinct()
                .toList();
        return values.isEmpty() ? null : String.join("\n", values);
    }

    private ConditionalRecurrenceRule getRule(Long ruleId) {
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new NotFoundException("Conditional recurrence rule", ruleId));
    }

    public record ResolutionDecision(
            ConditionalRecurrenceResolution resolution, boolean readyForScheduleProposal) {
    }

    private record Evaluation(
            ConditionalRecurrenceResolution.Status status,
            Instant startAt,
            Instant endAt,
            String reason,
            String sourceSnapshot) {

        private static Evaluation unresolved(
                ConditionalRecurrenceResolution.Status status,
                String reason,
                String sourceSnapshot) {
            return new Evaluation(status, null, null, reason, sourceSnapshot);
        }
    }

    private static final class ResolutionContext {
        private final List<OfficialDayStatus> evidence = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();
        private Evaluation waiting;
    }
}
