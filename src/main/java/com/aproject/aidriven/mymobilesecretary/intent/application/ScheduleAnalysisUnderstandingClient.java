package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Provider-neutral boundary for bounded, read-only schedule-analysis understanding. */
interface ScheduleAnalysisUnderstandingClient {

    boolean available();

    Decision understand(String text);

    enum Facet {
        BUSIEST_DAY,
        LONGEST_ITEM,
        ADJACENT_GAPS
    }

    record Decision(List<Facet> facets, boolean containsOtherRequest) {

        public Decision {
            facets = List.copyOf(Objects.requireNonNull(facets, "facets"));
            if (facets.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("facets must not contain null");
            }
            if (new HashSet<>(facets).size() != facets.size()) {
                throw new IllegalArgumentException("facets must not contain duplicates");
            }
        }
    }
}
