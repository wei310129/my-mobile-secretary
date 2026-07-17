package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityId;

/** Stable IDs shared by routing, traces and future workspace-aware execution adapters. */
public final class CoreCapabilityIds {

    public static final CapabilityId CREATE_TASK = CapabilityId.of("task.create");
    public static final CapabilityId CREATE_SCHEDULE = CapabilityId.of("schedule.create");
    public static final CapabilityId ASK_TASK_INFO = CapabilityId.of("task.info");
    public static final CapabilityId ASK_PRICE_HISTORY = CapabilityId.of("price.history");
    public static final CapabilityId CANCEL_TASK = CapabilityId.of("task.cancel");
    public static final CapabilityId REMOVE_TASK_PLACE = CapabilityId.of("task.place.remove");
    public static final CapabilityId SET_INVENTORY = CapabilityId.of("inventory.set");
    public static final CapabilityId ADJUST_INVENTORY = CapabilityId.of("inventory.adjust");
    public static final CapabilityId LIST_SCHEDULES_ON_DATE = CapabilityId.of("schedule.list_on_date");
    public static final CapabilityId EXPLAIN_LAST_FAILURE = CapabilityId.of("intent.failure.explain");

    private CoreCapabilityIds() {
    }
}
