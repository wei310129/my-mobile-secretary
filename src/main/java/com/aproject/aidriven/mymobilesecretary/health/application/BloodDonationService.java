package com.aproject.aidriven.mymobilesecretary.health.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord;
import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord.SourceType;
import com.aproject.aidriven.mymobilesecretary.health.persistence.BloodDonationRecordRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic storage and date comparison; this service never infers medical eligibility. */
@Service
@Transactional
public class BloodDonationService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final BloodDonationRecordRepository repository;
    private final SemanticTagGraphService graph;
    private final UniversalLifeRecordService life;
    private final Clock clock;

    public BloodDonationService(BloodDonationRecordRepository repository,
                                SemanticTagGraphService graph,
                                UniversalLifeRecordService life, Clock clock) {
        this.repository = repository;
        this.graph = graph;
        this.life = life;
        this.clock = clock;
    }

    public BloodDonationRecord record(LocalDate donationDate, String location,
                                      LocalDate nextEligibleDate, SourceType source) {
        if (donationDate == null || donationDate.isAfter(LocalDate.now(clock.withZone(TAIPEI)))) {
            throw new IllegalArgumentException("donation date must be today or in the past");
        }
        Instant now = Instant.now(clock);
        var actorId = WorkspaceContextHolder.requireContext().actorId();
        Optional<BloodDonationRecord> existing = repository
                .findByCreatedByUserIdAndDonationDate(actorId, donationDate);
        BloodDonationRecord record = existing.orElseGet(() -> BloodDonationRecord.create(
                donationDate, location, nextEligibleDate, source, now));
        if (existing.isPresent()) record.update(location, nextEligibleDate, source, now);
        BloodDonationRecord saved = repository.save(record);
        index(saved);
        if (existing.isEmpty()) {
            Instant occurredAt = donationDate.atStartOfDay(TAIPEI).toInstant();
            life.recordDomainEvent(TaggedLifeRecord.RecordType.HEALTH,
                    "捐血紀錄", occurredAt, List.of("捐血", "健康紀錄"));
        }
        return saved;
    }

    public BloodDonationRecord setLatestEligibleDate(LocalDate date) {
        BloodDonationRecord latest = latest().orElseThrow(
                () -> new IllegalStateException("no blood donation record exists"));
        latest.setNextEligibleDate(date, Instant.now(clock));
        return repository.save(latest);
    }

    @Transactional(readOnly = true)
    public Optional<BloodDonationRecord> latest() {
        return repository.findFirstByCreatedByUserIdOrderByDonationDateDesc(
                WorkspaceContextHolder.requireContext().actorId());
    }

    @Transactional(readOnly = true)
    public Eligibility eligibilityOn(LocalDate targetDate) {
        if (targetDate == null) throw new IllegalArgumentException("target date is required");
        Optional<BloodDonationRecord> record = latest();
        if (record.isEmpty()) return new Eligibility(targetDate, null, Status.NO_RECORD);
        if (record.get().getNextEligibleDate() == null) {
            return new Eligibility(targetDate, record.get(), Status.ELIGIBILITY_DATE_MISSING);
        }
        return new Eligibility(targetDate, record.get(),
                targetDate.isBefore(record.get().getNextEligibleDate())
                        ? Status.BEFORE_RECORDED_DATE : Status.ON_OR_AFTER_RECORDED_DATE);
    }

    private void index(BloodDonationRecord record) {
        List<SemanticTagGraphService.TagSpec> tags = new ArrayList<>();
        tags.add(tag("捐血", SemanticTag.Kind.ACTIVITY));
        tags.add(tag("健康紀錄", SemanticTag.Kind.TOPIC));
        if (record.getDonationLocation() != null) {
            tags.add(tag(record.getDonationLocation(), SemanticTag.Kind.ORGANIZATION));
        }
        graph.indexBloodDonation(record.getId(), tags);
    }

    private static SemanticTagGraphService.TagSpec tag(String name, SemanticTag.Kind kind) {
        return new SemanticTagGraphService.TagSpec(name, kind, SemanticTagEdge.SourceType.SYSTEM_RULE);
    }

    public record Eligibility(LocalDate targetDate, BloodDonationRecord record, Status status) {}
    public enum Status { NO_RECORD, ELIGIBILITY_DATE_MISSING, BEFORE_RECORDED_DATE, ON_OR_AFTER_RECORDED_DATE }
}
