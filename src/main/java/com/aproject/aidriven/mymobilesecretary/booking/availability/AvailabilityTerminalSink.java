package com.aproject.aidriven.mymobilesecretary.booking.availability;

@FunctionalInterface
public interface AvailabilityTerminalSink {

    void emit(AvailabilitySearchResult result);
}
