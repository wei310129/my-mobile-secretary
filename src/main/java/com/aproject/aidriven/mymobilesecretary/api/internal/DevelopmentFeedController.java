package com.aproject.aidriven.mymobilesecretary.api.internal;

import com.aproject.aidriven.mymobilesecretary.integration.developmentfeed.DevelopmentFeedPage;
import com.aproject.aidriven.mymobilesecretary.integration.developmentfeed.DevelopmentFeedService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/integration/v1/development-events")
@ConditionalOnProperty(
        prefix = "app.integration.development-feed", name = "enabled", havingValue = "true")
public class DevelopmentFeedController {

    private final DevelopmentFeedService service;

    public DevelopmentFeedController(DevelopmentFeedService service) {
        this.service = service;
    }

    @GetMapping
    public DevelopmentFeedPage read(
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") int limit) {
        return service.readAfter(after, limit);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> handleInvalidFeedRequest(ResponseStatusException invalid) {
        return ResponseEntity.status(invalid.getStatusCode()).build();
    }
}
