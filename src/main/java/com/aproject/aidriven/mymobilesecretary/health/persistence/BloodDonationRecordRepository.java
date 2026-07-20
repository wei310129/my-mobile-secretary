package com.aproject.aidriven.mymobilesecretary.health.persistence;

import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BloodDonationRecordRepository extends JpaRepository<BloodDonationRecord, Long> {
    Optional<BloodDonationRecord> findByCreatedByUserIdAndDonationDate(UUID actorId, LocalDate date);
    Optional<BloodDonationRecord> findFirstByCreatedByUserIdOrderByDonationDateDesc(UUID actorId);
}
