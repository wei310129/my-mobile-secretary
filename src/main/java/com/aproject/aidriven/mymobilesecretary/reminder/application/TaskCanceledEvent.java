package com.aproject.aidriven.mymobilesecretary.reminder.application;

import java.time.Instant;

/** Published after a task reaches its terminal canceled state. */
public record TaskCanceledEvent(Long taskId, String title, Instant canceledAt) {
}
