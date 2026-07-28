package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.time.Instant;

public record CalendarNodeRevisionView(String nodeKey, long revision, Instant effectiveTime) {}
