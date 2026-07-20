package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Immutable startup registry for deterministic intent command handlers. */
@Component
public final class IntentHandlerRegistry {

    private final Map<IntentCommand.Type, IntentHandler> handlersByType;

    public IntentHandlerRegistry(List<IntentHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        EnumMap<IntentCommand.Type, IntentHandler> registrations =
                new EnumMap<>(IntentCommand.Type.class);
        for (IntentHandler handler : handlers) {
            register(registrations, handler);
        }
        this.handlersByType = Map.copyOf(registrations);
    }

    public boolean supports(IntentCommand.Type type) {
        return type != null && handlersByType.containsKey(type);
    }

    public IntentResult dispatch(String text, IntentCommand command) {
        if (command == null || command.type() == null) {
            throw new IllegalArgumentException("intent command and type must not be null");
        }
        IntentHandler handler = handlersByType.get(command.type());
        if (handler == null) {
            throw new IllegalArgumentException("no intent handler registered for type " + command.type());
        }
        return handler.handle(text, command);
    }

    private static void register(
            EnumMap<IntentCommand.Type, IntentHandler> registrations,
            IntentHandler handler) {
        if (handler == null) {
            throw new IllegalStateException("intent handler list must not contain null");
        }
        var supportedTypes = handler.supportedTypes();
        if (supportedTypes == null || supportedTypes.isEmpty()) {
            throw new IllegalStateException(
                    "intent handler must declare at least one type: " + handler.getClass().getName());
        }
        for (IntentCommand.Type type : supportedTypes) {
            if (type == null) {
                throw new IllegalStateException(
                        "intent handler declared a null type: " + handler.getClass().getName());
            }
            IntentHandler duplicate = registrations.putIfAbsent(type, handler);
            if (duplicate != null) {
                throw new IllegalStateException("duplicate intent handler registration for type "
                        + type + ": " + duplicate.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
    }
}
