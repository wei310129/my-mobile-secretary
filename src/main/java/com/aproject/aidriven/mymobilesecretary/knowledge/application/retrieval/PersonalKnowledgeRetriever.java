package com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval;

import java.util.List;

/** Persistence-neutral boundary for read-only personal-knowledge evidence retrieval. */
public interface PersonalKnowledgeRetriever {
    List<KnowledgeEvidence> retrieve(KnowledgeQuery query);
}
