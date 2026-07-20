package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectAnnotationRepository extends JpaRepository<ObjectAnnotation, Long> {
}
