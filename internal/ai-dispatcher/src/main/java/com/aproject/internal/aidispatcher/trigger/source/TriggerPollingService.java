package com.aproject.internal.aidispatcher.trigger.source;

import com.aproject.internal.aidispatcher.config.TriggerPollingProperties;
import com.aproject.internal.aidispatcher.trigger.domain.TriggerEventPage;
import com.aproject.internal.aidispatcher.trigger.domain.TriggerIngestionResult;
import com.aproject.internal.aidispatcher.trigger.persistence.JdbcTriggerEventStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TriggerPollingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TriggerPollingService.class);

    private final List<TriggerSource> sources;
    private final JdbcTriggerEventStore eventStore;
    private final TriggerPollingProperties properties;

    public TriggerPollingService(List<TriggerSource> sources,
                                 JdbcTriggerEventStore eventStore,
                                 TriggerPollingProperties properties) {
        this.sources = List.copyOf(sources);
        this.eventStore = eventStore;
        this.properties = properties;
        requireUniqueSourceKeys(this.sources);
    }

    public TriggerPollingResult pollAll() {
        int pages = 0;
        int inserted = 0;
        for (TriggerSource source : sources) {
            try {
                SourcePollCount count = pollSource(source);
                pages += count.pages();
                inserted += count.inserted();
            } catch (RuntimeException failure) {
                LOGGER.warn("Trigger source {} polling failed", source.sourceKey(), failure);
                return TriggerPollingResult.failure(
                        sources.size(), pages, inserted, source.sourceKey());
            }
        }
        return TriggerPollingResult.success(sources.size(), pages, inserted);
    }

    private SourcePollCount pollSource(TriggerSource source) {
        int pageCount = 0;
        int inserted = 0;
        int fetchAttemptCount = 0;
        boolean hasMore = true;
        do {
            fetchAttemptCount++;
            if (fetchAttemptCount > properties.maxPagesPerPoll() * 2) {
                throw new IllegalStateException(
                        "Trigger cursor contention exceeded the bounded retry limit");
            }
            String expectedCursor = eventStore.currentCursor(source.sourceKey());
            TriggerSourcePage fetched = source.fetchAfter(expectedCursor, properties.pageSize());
            TriggerIngestionResult ingested = eventStore.ingest(new TriggerEventPage(
                    source.sourceKey(), expectedCursor, fetched.nextCursor(), fetched.events()));
            if (ingested.status() == TriggerIngestionResult.Status.STALE_CURSOR) {
                continue;
            }
            pageCount++;
            inserted += ingested.insertedCount();
            hasMore = fetched.hasMore();
        } while (hasMore && pageCount < properties.maxPagesPerPoll());
        return new SourcePollCount(pageCount, inserted);
    }

    private static void requireUniqueSourceKeys(List<TriggerSource> sources) {
        Set<String> keys = new HashSet<>();
        for (TriggerSource source : sources) {
            if (source.sourceKey() == null || source.sourceKey().isBlank()) {
                throw new IllegalArgumentException("Trigger source key is required");
            }
            if (!keys.add(source.sourceKey())) {
                throw new IllegalArgumentException("Duplicate trigger source key: " + source.sourceKey());
            }
        }
    }

    private record SourcePollCount(int pages, int inserted) {
    }
}
