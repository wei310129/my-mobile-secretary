package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Set;

/** Executes a cohesive group of validated intent commands through deterministic Java services. */
public interface IntentHandler {

    Set<IntentCommand.Type> supportedTypes();

    IntentResult handle(String text, IntentCommand command);
}
