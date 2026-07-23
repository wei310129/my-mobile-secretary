package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Routes a newly persisted focus to an optional domain-owned typed binding writer. */
@Component
public final class ConversationFocusTypedBindingRegistry {

    private final Map<String, ConversationFocusTypedBindingWriter> writers;

    public ConversationFocusTypedBindingRegistry(List<ConversationFocusTypedBindingWriter> writers) {
        this.writers = Objects.requireNonNull(writers, "writers").stream()
                .collect(Collectors.toUnmodifiableMap(
                        writer -> required(writer.rootDomain()),
                        Function.identity(),
                        (first, duplicate) -> {
                            throw new IllegalStateException(
                                    "duplicate typed focus binding writer for "
                                            + first.rootDomain());
                        }));
    }

    public void bindIfSupported(ConversationFocus focus) {
        Objects.requireNonNull(focus, "focus");
        ConversationFocusTypedBindingWriter writer = writers.get(focus.getRootDomain());
        if (writer != null) {
            writer.bind(focus);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("typed focus binding root domain is required");
        }
        return value.strip();
    }
}
