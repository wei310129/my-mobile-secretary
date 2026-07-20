package com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaggedLifeRecordRepository extends JpaRepository<TaggedLifeRecord, Long> {
    List<TaggedLifeRecord> findByIdInAndOccurredAtBetweenOrderByOccurredAtDesc(
            List<Long> ids, Instant from, Instant to);
    List<TaggedLifeRecord> findByIdInOrderByOccurredAtDesc(List<Long> ids);
}
