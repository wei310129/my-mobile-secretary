package com.aproject.aidriven.mymobilesecretary.payment.persistence;

import com.aproject.aidriven.mymobilesecretary.payment.domain.PaymentNotice;
import com.aproject.aidriven.mymobilesecretary.payment.domain.PaymentNotice.Status;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentNoticeRepository extends JpaRepository<PaymentNotice, Long> {
    Optional<PaymentNotice> findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
            UUID actorId, Status status);

    Optional<PaymentNotice> findByFlexiblePlanId(Long flexiblePlanId);
}
