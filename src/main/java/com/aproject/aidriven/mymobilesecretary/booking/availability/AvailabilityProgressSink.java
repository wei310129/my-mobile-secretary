package com.aproject.aidriven.mymobilesecretary.booking.availability;

@FunctionalInterface
public interface AvailabilityProgressSink {

    void emit(AvailabilitySearchProgress progress);
}
