package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.UserKnowledgeFactRepository;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores only explicit user-taught facts; it never promotes model guesses into memory. */
@Service
@Transactional
public class UserKnowledgeService {

    private final UserKnowledgeFactRepository repository;
    private final Clock clock;

    public UserKnowledgeService(UserKnowledgeFactRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public UserKnowledgeFact remember(Category category, String subject, String detail) {
        String safeSubject = bounded(subject, 160, "knowledge subject");
        String safeDetail = bounded(detail, 1200, "knowledge detail");
        String normalized = normalize(safeSubject);
        var actorId = WorkspaceContextHolder.requireContext().actorId();
        Instant now = Instant.now(clock);
        UserKnowledgeFact fact = repository
                .findByCreatedByUserIdAndCategoryAndNormalizedSubject(
                        actorId, category, normalized)
                .orElseGet(() -> UserKnowledgeFact.create(
                        category, safeSubject, normalized, safeDetail, now));
        if (fact.getId() != null) {
            fact.update(safeSubject, safeDetail, now);
        }
        return repository.save(fact);
    }

    @Transactional(readOnly = true)
    public Optional<UserKnowledgeFact> find(Category category, String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        var actorId = WorkspaceContextHolder.requireContext().actorId();
        String needle = normalize(subject);
        Optional<UserKnowledgeFact> exact = repository
                .findByCreatedByUserIdAndCategoryAndNormalizedSubject(actorId, category, needle);
        if (exact.isPresent()) {
            return exact;
        }
        return repository.findByCreatedByUserIdAndCategoryOrderByUpdatedAtDesc(actorId, category)
                .stream()
                .filter(fact -> fact.getNormalizedSubject().contains(needle)
                        || needle.contains(fact.getNormalizedSubject()))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<UserKnowledgeFact> list(Category category) {
        return repository.findByCreatedByUserIdAndCategoryOrderByUpdatedAtDesc(
                WorkspaceContextHolder.requireContext().actorId(), category);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\p{P}\\p{S}]+", "")
                .strip();
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
