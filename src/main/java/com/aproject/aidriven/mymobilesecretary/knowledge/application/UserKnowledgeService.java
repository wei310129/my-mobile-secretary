package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.KnowledgeTextNormalizer;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.UserKnowledgeFactRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores only explicit user-taught facts; it never promotes model guesses into memory. */
@Service
@Transactional
public class UserKnowledgeService {

    private final UserKnowledgeFactRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UserKnowledgeService(
            UserKnowledgeFactRepository repository,
            ApplicationEventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    public UserKnowledgeFact remember(Category category, String subject, String detail) {
        String safeSubject = bounded(subject, 160, "knowledge subject");
        String safeDetail = bounded(detail, 1200, "knowledge detail");
        String normalized = normalize(safeSubject);
        var context = WorkspaceContextHolder.requireContext();
        Instant now = Instant.now(clock);
        UserKnowledgeFact fact = repository
                .findByWorkspaceIdAndCreatedByUserIdAndCategoryAndNormalizedSubject(
                        context.workspaceId(), context.actorId(), category, normalized)
                .orElseGet(() -> UserKnowledgeFact.create(
                        category, safeSubject, normalized, safeDetail, now));
        boolean updated = fact.getId() != null;
        if (updated) {
            fact.update(safeSubject, safeDetail, now);
        }
        UserKnowledgeFact saved = repository.save(fact);
        if (updated) {
            events.publishEvent(new UserKnowledgeFactUpdatedEvent(
                    saved.getId(), saved.getSubject(), now));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<UserKnowledgeFact> find(Category category, String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        var context = WorkspaceContextHolder.requireContext();
        String needle = normalize(subject);
        Optional<UserKnowledgeFact> exact = repository
                .findByWorkspaceIdAndCreatedByUserIdAndCategoryAndNormalizedSubject(
                        context.workspaceId(), context.actorId(), category, needle);
        if (exact.isPresent()) {
            return exact;
        }
        return repository.findByWorkspaceIdAndCreatedByUserIdAndCategoryOrderByUpdatedAtDesc(
                        context.workspaceId(), context.actorId(), category)
                .stream()
                .filter(fact -> fact.getNormalizedSubject().contains(needle)
                        || needle.contains(fact.getNormalizedSubject()))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<UserKnowledgeFact> list(Category category) {
        var context = WorkspaceContextHolder.requireContext();
        return repository.findByWorkspaceIdAndCreatedByUserIdAndCategoryOrderByUpdatedAtDesc(
                context.workspaceId(), context.actorId(), category);
    }

    static String normalize(String value) {
        return KnowledgeTextNormalizer.normalize(value);
    }

    private static String bounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return stripped;
    }
}
