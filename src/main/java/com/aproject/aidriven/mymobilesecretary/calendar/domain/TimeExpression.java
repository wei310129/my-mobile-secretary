package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public sealed interface TimeExpression
        permits TimeExpression.Absolute, TimeExpression.OwnerRelative, TimeExpression.NodeRelative {

    record Absolute(Instant time) implements TimeExpression {
        public Absolute {
            Objects.requireNonNull(time, "time");
        }
    }

    record OwnerRelative(CalendarTimeNode.OwnerBoundary boundary, Duration offset)
            implements TimeExpression {
        public OwnerRelative {
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(offset, "offset");
        }
    }

    record NodeRelative(String baseNodeId, Duration offset) implements TimeExpression {
        public NodeRelative {
            if (baseNodeId == null || baseNodeId.isBlank()) {
                throw new IllegalArgumentException("Base node is required");
            }
            Objects.requireNonNull(offset, "offset");
        }
    }
}
