package com.aproject.aidriven.mymobilesecretary.intent.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/** Immutable startup registry for versioned capability metadata and typed argument validation. */
@Component
public final class CapabilityRegistry {

    private static final Comparator<CapabilityDescriptor> DESCRIPTOR_ORDER = Comparator
            .comparing(CapabilityDescriptor::id)
            .thenComparing(Comparator.comparingInt(CapabilityDescriptor::version).reversed());

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Map<CapabilityId, NavigableMap<Integer, CapabilityHandler<?>>> handlersById;
    private final List<CapabilityDescriptor> descriptors;
    private final List<CapabilityDescriptor> activeDescriptors;

    public CapabilityRegistry(
            List<CapabilityHandler<?>> handlers,
            ObjectMapper objectMapper,
            Validator validator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.validator = Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(handlers, "handlers");

        Map<CapabilityId, NavigableMap<Integer, CapabilityHandler<?>>> registrations = new TreeMap<>();
        List<CapabilityDescriptor> registeredDescriptors = new ArrayList<>();
        for (CapabilityHandler<?> handler : handlers) {
            register(registrations, registeredDescriptors, handler);
        }

        LinkedHashMap<CapabilityId, NavigableMap<Integer, CapabilityHandler<?>>> immutable = new LinkedHashMap<>();
        registrations.forEach((id, versions) -> immutable.put(id, new TreeMap<>(versions)));
        this.handlersById = Map.copyOf(immutable);
        this.descriptors = registeredDescriptors.stream().sorted(DESCRIPTOR_ORDER).toList();
        this.activeDescriptors = registrations.values().stream()
                .map(NavigableMap::lastEntry)
                .map(Map.Entry::getValue)
                .map(CapabilityHandler::descriptor)
                .sorted(DESCRIPTOR_ORDER)
                .toList();
    }

    /** Returns every registered version in deterministic order. */
    public List<CapabilityDescriptor> descriptors() {
        return descriptors;
    }

    /** Returns only the newest registered version of each stable capability ID. */
    public List<CapabilityDescriptor> activeDescriptors() {
        return activeDescriptors;
    }

    public Optional<CapabilityDescriptor> descriptor(CapabilityId id) {
        return activeHandler(id).map(CapabilityHandler::descriptor);
    }

    public Optional<CapabilityDescriptor> descriptor(CapabilityId id, int version) {
        return handler(id, version).map(CapabilityHandler::descriptor);
    }

    public List<CapabilityDescriptor> descriptors(CapabilityDomain domain) {
        Objects.requireNonNull(domain, "domain");
        return activeDescriptors.stream().filter(descriptor -> descriptor.domain() == domain).toList();
    }

    public Optional<CapabilityHandler<?>> handler(CapabilityId id) {
        return activeHandler(id);
    }

    public Optional<CapabilityHandler<?>> handler(CapabilityId id, int version) {
        Objects.requireNonNull(id, "id");
        NavigableMap<Integer, CapabilityHandler<?>> versions = handlersById.get(id);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    public ValidatedCapabilityCall<?> mapAndValidate(CapabilityId id, JsonNode arguments) {
        CapabilityHandler<?> handler = activeHandler(id)
                .orElseThrow(() -> unknownCapability(id, 0));
        return mapUnknownHandler(handler, arguments);
    }

    public ValidatedCapabilityCall<?> mapAndValidate(CapabilityId id, int version, JsonNode arguments) {
        CapabilityHandler<?> handler = handler(id, version)
                .orElseThrow(() -> unknownCapability(id, version));
        return mapUnknownHandler(handler, arguments);
    }

    public <P> ValidatedCapabilityCall<P> mapAndValidate(
            CapabilityId id,
            int version,
            Class<P> expectedType,
            JsonNode arguments) {
        Objects.requireNonNull(expectedType, "expectedType");
        CapabilityHandler<?> rawHandler = handler(id, version)
                .orElseThrow(() -> unknownCapability(id, version));
        if (!rawHandler.inputType().equals(expectedType)) {
            throw new CapabilityArgumentException(id, version,
                    "capability input type does not match expected type " + expectedType.getName());
        }
        return mapExpectedHandler(rawHandler, arguments);
    }

    private Optional<CapabilityHandler<?>> activeHandler(CapabilityId id) {
        Objects.requireNonNull(id, "id");
        NavigableMap<Integer, CapabilityHandler<?>> versions = handlersById.get(id);
        return versions == null || versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.lastEntry().getValue());
    }

    @SuppressWarnings("unchecked")
    private ValidatedCapabilityCall<?> mapUnknownHandler(CapabilityHandler<?> handler, JsonNode arguments) {
        return mapTypedHandler((CapabilityHandler<Object>) handler, arguments);
    }

    @SuppressWarnings("unchecked")
    private <P> ValidatedCapabilityCall<P> mapExpectedHandler(
            CapabilityHandler<?> handler,
            JsonNode arguments) {
        return mapTypedHandler((CapabilityHandler<P>) handler, arguments);
    }

    private <P> ValidatedCapabilityCall<P> mapTypedHandler(
            CapabilityHandler<P> handler,
            JsonNode arguments) {
        CapabilityDescriptor descriptor = handler.descriptor();
        CapabilityId id = descriptor.id();
        int version = descriptor.version();
        if (arguments == null || !arguments.isObject()) {
            throw new CapabilityArgumentException(id, version, "capability arguments must be a JSON object");
        }

        P mapped;
        try {
            mapped = objectMapper.readerFor(handler.inputType())
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(objectMapper.treeAsTokens(arguments));
        } catch (UnrecognizedPropertyException exception) {
            throw new CapabilityArgumentException(id, version,
                    "unknown capability argument: " + exception.getPropertyName(), exception);
        } catch (JsonMappingException exception) {
            throw new CapabilityArgumentException(id, version,
                    "capability argument type mismatch" + mappingPath(exception), exception);
        } catch (JsonProcessingException exception) {
            throw new CapabilityArgumentException(id, version,
                    "capability arguments do not match the registered schema", exception);
        } catch (IOException exception) {
            throw new CapabilityArgumentException(id, version,
                    "capability arguments could not be read", exception);
        }

        if (mapped == null) {
            throw new CapabilityArgumentException(id, version, "capability arguments mapped to null");
        }

        String violations = validator.validate(mapped).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .limit(8)
                .map(CapabilityRegistry::violationMessage)
                .collect(Collectors.joining("; "));
        if (!violations.isEmpty()) {
            throw new CapabilityArgumentException(id, version, "capability arguments are invalid: " + violations);
        }

        try {
            handler.validate(mapped);
        } catch (CapabilityArgumentException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "domain validation failed"
                    : exception.getMessage();
            throw new CapabilityArgumentException(id, version,
                    "capability domain validation failed: " + reason, exception);
        }
        return new ValidatedCapabilityCall<>(descriptor, handler, mapped);
    }

    private static void register(
            Map<CapabilityId, NavigableMap<Integer, CapabilityHandler<?>>> registrations,
            Collection<CapabilityDescriptor> descriptors,
            CapabilityHandler<?> handler) {
        if (handler == null) {
            throw new CapabilityRegistryException("capability handler list must not contain null");
        }
        CapabilityDescriptor descriptor = handler.descriptor();
        if (descriptor == null) {
            throw new CapabilityRegistryException("capability handler returned a null descriptor: "
                    + handler.getClass().getName());
        }
        Class<?> handlerInputType = handler.inputType();
        if (handlerInputType == null) {
            throw new CapabilityRegistryException("capability handler returned a null inputType: "
                    + descriptor.id());
        }
        if (!descriptor.inputType().equals(handlerInputType)) {
            throw new CapabilityRegistryException("capability descriptor inputType "
                    + descriptor.inputType().getName() + " does not match handler inputType "
                    + handlerInputType.getName() + " for " + descriptor.id() + " v" + descriptor.version());
        }

        NavigableMap<Integer, CapabilityHandler<?>> versions = registrations.computeIfAbsent(
                descriptor.id(), ignored -> new TreeMap<>());
        CapabilityHandler<?> duplicate = versions.putIfAbsent(descriptor.version(), handler);
        if (duplicate != null) {
            throw new CapabilityRegistryException("duplicate capability registration: "
                    + descriptor.id() + " v" + descriptor.version());
        }
        descriptors.add(descriptor);
    }

    private static String violationMessage(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        return (path.isBlank() ? "arguments" : path) + " " + violation.getMessage();
    }

    private static String mappingPath(JsonMappingException exception) {
        String path = exception.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? reference.getFieldName()
                        : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining("."));
        return path.isBlank() ? "" : " at " + path;
    }

    private static CapabilityArgumentException unknownCapability(CapabilityId id, int version) {
        return new CapabilityArgumentException(id, version,
                version > 0 ? "unknown capability or version" : "unknown capability");
    }
}
