package com.aproject.internal.aidispatcher.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DispatcherStateMachine {

    private static final Map<DispatcherState, Set<DispatcherState>> ALLOWED_TRANSITIONS =
            allowedTransitions();

    public DispatcherState transition(DispatcherState current, DispatcherState target) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        if (current == target) {
            return current;
        }
        if (!ALLOWED_TRANSITIONS.get(current).contains(target)) {
            throw new IllegalStateException("Illegal dispatcher transition: " + current + " -> " + target);
        }
        return target;
    }

    private static Map<DispatcherState, Set<DispatcherState>> allowedTransitions() {
        EnumMap<DispatcherState, Set<DispatcherState>> transitions =
                new EnumMap<>(DispatcherState.class);
        transitions.put(DispatcherState.IDLE,
                EnumSet.of(DispatcherState.WAITING, DispatcherState.PAUSED));
        transitions.put(DispatcherState.WAITING,
                EnumSet.of(DispatcherState.IDLE, DispatcherState.STARTING, DispatcherState.PAUSED));
        transitions.put(DispatcherState.STARTING,
                EnumSet.of(DispatcherState.RUNNING, DispatcherState.RECOVERING,
                        DispatcherState.WAITING, DispatcherState.PAUSED));
        transitions.put(DispatcherState.RUNNING,
                EnumSet.of(DispatcherState.IDLE, DispatcherState.WAITING,
                        DispatcherState.RECOVERING, DispatcherState.PAUSED));
        transitions.put(DispatcherState.RECOVERING,
                EnumSet.of(DispatcherState.RUNNING, DispatcherState.IDLE,
                        DispatcherState.WAITING, DispatcherState.PAUSED));
        transitions.put(DispatcherState.PAUSED,
                EnumSet.of(DispatcherState.RECOVERING, DispatcherState.WAITING,
                        DispatcherState.IDLE));
        return Map.copyOf(transitions);
    }
}
