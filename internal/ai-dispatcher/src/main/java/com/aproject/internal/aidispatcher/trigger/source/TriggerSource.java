package com.aproject.internal.aidispatcher.trigger.source;

public interface TriggerSource {

    String sourceKey();

    TriggerSourcePage fetchAfter(String cursor, int limit);
}
