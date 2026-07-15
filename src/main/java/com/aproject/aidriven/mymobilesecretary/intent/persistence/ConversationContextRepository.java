package com.aproject.aidriven.mymobilesecretary.intent.persistence;

import com.aproject.aidriven.mymobilesecretary.intent.domain.ConversationContext;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationContextRepository extends JpaRepository<ConversationContext, Integer> {
}
