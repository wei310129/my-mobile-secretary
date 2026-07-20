package com.aproject.aidriven.mymobilesecretary.utility.persistence;

import com.aproject.aidriven.mymobilesecretary.utility.domain.UtilityBillRecord;
import com.aproject.aidriven.mymobilesecretary.utility.domain.UtilityBillRecord.Status;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UtilityBillRecordRepository extends JpaRepository<UtilityBillRecord, Long> {
    List<UtilityBillRecord> findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
            UUID actorId, Status status);

    List<UtilityBillRecord> findByCreatedByUserIdAndStatusOrderByBillingMonthAsc(
            UUID actorId, Status status);

    List<UtilityBillRecord> findByCreatedByUserIdAndImportBatchIdAndStatusOrderByBillingMonthDesc(
            UUID actorId, UUID batchId, Status status);

    List<UtilityBillRecord> findByCreatedByUserIdAndStatusAndBillingMonthBetweenOrderByBillingMonthAsc(
            UUID actorId, Status status, LocalDate from, LocalDate to);

    Optional<UtilityBillRecord>
            findFirstByCreatedByUserIdAndStatusAndProviderAndLocationLabelAndBillingMonth(
                    UUID actorId, Status status, String provider, String location, LocalDate month);
}
