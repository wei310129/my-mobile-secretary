package com.aproject.internal.aidispatcher.session.api;

import com.aproject.internal.aidispatcher.config.SessionBindingAdminProperties;
import com.aproject.internal.aidispatcher.session.AgentSessionRegistry;
import com.aproject.internal.aidispatcher.session.BindDevelopmentSessionCommand;
import com.aproject.internal.aidispatcher.session.SessionBinding;
import com.aproject.internal.aidispatcher.session.UnbindDevelopmentSessionCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/session-bindings/development-main")
@ConditionalOnProperty(
        prefix = "ai-dispatcher.session-binding-api",
        name = "enabled",
        havingValue = "true")
public class SessionBindingController {

    private static final String AUTHORIZATION = "Authorization";
    private static final String ACTOR = "X-Dispatcher-Actor";

    private final AgentSessionRegistry registry;
    private final SessionBindingAdminAuthenticator authenticator;

    public SessionBindingController(AgentSessionRegistry registry,
                                    SessionBindingAdminProperties properties) {
        this.registry = registry;
        this.authenticator = new SessionBindingAdminAuthenticator(properties);
    }

    @GetMapping
    public ResponseEntity<SessionBindingResponse> get(
            @RequestHeader(value = AUTHORIZATION, required = false) String authorization) {
        authenticator.authenticate(authorization);
        return response(registry.developmentSession());
    }

    @PutMapping
    public ResponseEntity<SessionBindingResponse> bind(
            @RequestHeader(value = AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = ACTOR, required = false) String actor,
            @RequestBody BindSessionRequest request) {
        authenticator.authenticate(authorization);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        SessionBinding binding = registry.bindDevelopmentSession(
                new BindDevelopmentSessionCommand(
                        request.externalSessionId(), request.expectedVersion(),
                        actor, request.reason()));
        return response(binding);
    }

    @DeleteMapping
    public ResponseEntity<SessionBindingResponse> unbind(
            @RequestHeader(value = AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = ACTOR, required = false) String actor,
            @RequestParam Long expectedVersion,
            @RequestParam(required = false) String reason) {
        authenticator.authenticate(authorization);
        if (expectedVersion == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        SessionBinding binding = registry.unbindDevelopmentSession(
                new UnbindDevelopmentSessionCommand(expectedVersion, actor, reason));
        return response(binding);
    }

    private static ResponseEntity<SessionBindingResponse> response(SessionBinding binding) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag("\"" + binding.version() + "\"")
                .body(SessionBindingResponse.from(binding));
    }
}
