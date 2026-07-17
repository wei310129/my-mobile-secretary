package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentDecisionTraceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates trace persistence so a trace failure can never mark the business transaction rollback-only. */
@Service
public class IntentDecisionTraceWriter {

    private final IntentDecisionTraceRepository repository;

    public IntentDecisionTraceWriter(IntentDecisionTraceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(IntentDecisionTrace trace) {
        repository.saveAndFlush(trace);
    }
}
