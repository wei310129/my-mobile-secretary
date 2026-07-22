package com.aproject.internal.aidispatcher.session.api;

import com.aproject.internal.aidispatcher.config.SessionBindingAdminProperties;
import com.aproject.internal.aidispatcher.coordination.DispatcherDrainService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/dispatcher-drain")
@ConditionalOnProperty(prefix = "ai-dispatcher.session-binding-api", name = "enabled", havingValue = "true")
public class DispatcherDrainController {
    private final DispatcherDrainService drainService;
    private final SessionBindingAdminAuthenticator authenticator;

    public DispatcherDrainController(DispatcherDrainService drainService,
                                     SessionBindingAdminProperties properties) {
        this.drainService = drainService;
        this.authenticator = new SessionBindingAdminAuthenticator(properties);
    }

    @PostMapping
    public ResponseEntity<DispatcherDrainService.DispatcherDrainStatus> requestDrain(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Dispatcher-Actor", required = false) String actor) {
        authenticateMutation(authorization, actor);
        return response(drainService.requestDrain(actor.strip()));
    }

    @DeleteMapping
    public ResponseEntity<DispatcherDrainService.DispatcherDrainStatus> resume(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Dispatcher-Actor", required = false) String actor) {
        authenticateMutation(authorization, actor);
        return response(drainService.resume(actor.strip()));
    }

    private void authenticateMutation(String authorization, String actor) {
        authenticator.authenticate(authorization);
        if (actor == null || actor.isBlank()) { throw new IllegalArgumentException("X-Dispatcher-Actor is required"); }
    }

    private static ResponseEntity<DispatcherDrainService.DispatcherDrainStatus> response(
            DispatcherDrainService.DispatcherDrainStatus status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }
}
