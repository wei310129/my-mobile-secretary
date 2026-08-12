package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraftMode;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.PublicPlaceLookupDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.SystemPlaceCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable actor-scoped system-place ambiguity without retaining arbitrary user text. */
@Service
public class PublicPlaceLookupDraftService {

    private static final Duration RETENTION = Duration.ofDays(7);
    private final ConversationScopeResolver scopes;
    private final PublicPlaceLookupDraftRepository drafts;
    private final ConversationPendingQuestionRepository questions;
    private final SystemPlaceCatalog catalog;
    private final Clock clock;

    public PublicPlaceLookupDraftService(
            ConversationScopeResolver scopes,
            PublicPlaceLookupDraftRepository drafts,
            ConversationPendingQuestionRepository questions,
            SystemPlaceCatalog catalog,
            Clock clock) {
        this.scopes = scopes;
        this.drafts = drafts;
        this.questions = questions;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public PublicPlaceLookupDraft start(
            SystemPlaceCatalog.Resolution resolution, PublicPlaceLookupDraftMode mode) {
        return start(resolution, mode, null);
    }

    @Transactional
    public PublicPlaceLookupDraft startMultipoint(SystemPlaceCatalog.Resolution resolution) {
        if (resolution.status()
                != SystemPlaceCatalog.Resolution.Status.LOGICAL_PLACE_MULTIPOINT) {
            throw new IllegalArgumentException("only a logical multipoint result creates browse context");
        }
        return startCandidates(
                resolution, PublicPlaceLookupDraftMode.READ_ONLY_MULTIPOINT, null);
    }

    @Transactional
    public PublicPlaceLookupDraft start(
            SystemPlaceCatalog.Resolution resolution, PublicPlaceLookupDraftMode mode,
            PublicPlaceLookupDraft.CalendarDraftReference calendarDraft) {
        if (resolution.status() != SystemPlaceCatalog.Resolution.Status.ENTITY_AMBIGUOUS) {
            throw new IllegalArgumentException("only entity ambiguity creates a place lookup draft");
        }
        return startCandidates(resolution, mode, calendarDraft);
    }

    private PublicPlaceLookupDraft startCandidates(
            SystemPlaceCatalog.Resolution resolution, PublicPlaceLookupDraftMode mode,
            PublicPlaceLookupDraft.CalendarDraftReference calendarDraft) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scopes.current(context);
        Instant now = Instant.now(clock);
        current(context, scope).ifPresent(existing -> {
            if (!existing.expireIfDue(now)) existing.cancel(now);
            drafts.saveAndFlush(existing);
        });
        PublicPlaceLookupDraft draft = PublicPlaceLookupDraft.create(
                scope, context.channel(), mode, resolution.category(),
                resolution.candidates().stream().map(SystemPlaceCatalog.SystemPlace::key).toList(),
                calendarDraft, now.plus(RETENTION), now);
        return drafts.save(draft);
    }

    @Transactional
    public Optional<BrowseAnswer> browse(String keyword) {
        Optional<PublicPlaceLookupDraft> current = current();
        if (current.isEmpty()
                || current.orElseThrow().getMode()
                        != PublicPlaceLookupDraftMode.READ_ONLY_MULTIPOINT) {
            return Optional.empty();
        }
        PublicPlaceLookupDraft draft = current.orElseThrow();
        Instant now = Instant.now(clock);
        if (draft.expireIfDue(now)) {
            drafts.save(draft);
            return Optional.empty();
        }
        var all = catalog.points(draft.candidateKeyList());
        var filtered = keyword == null ? all : catalog.filterCandidates(
                draft.candidateKeyList(), keyword);
        return Optional.of(new BrowseAnswer(all, filtered));
    }

    @Transactional
    public Optional<Answer> answerRegion(String text) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scopes.current(context);
        Optional<PublicPlaceLookupDraft> current = current(context, scope);
        if (current.isEmpty()) return Optional.empty();
        PublicPlaceLookupDraft draft = current.orElseThrow();
        Instant now = Instant.now(clock);
        if (draft.expireIfDue(now)) {
            drafts.save(draft);
            return Optional.empty();
        }
        var matches = catalog.matchRegion(draft.candidateKeyList(), text);
        if (matches.isEmpty()) return Optional.empty();
        SystemPlaceCatalog.SystemPlace selected = catalog.selectPlanningPoint(matches);
        draft.complete(selected.key(), now);
        drafts.save(draft);
        questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndWorkflowIdAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        draft.getId(), ConversationPendingQuestionStatus.PENDING)
                .ifPresent(question -> question.answer(now));
        return Optional.of(new Answer(
                selected, matches.size(), draft.getMode(),
                draft.getCalendarDraftId(), draft.getCalendarDraftRevision()));
    }

    @Transactional
    public Optional<PublicPlaceLookupDraft> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        return current(context, scopes.current(context));
    }

    private Optional<PublicPlaceLookupDraft> current(
            WorkspaceContext context, ConversationScopeKey scope) {
        return drafts
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        PublicPlaceLookupDraftStatus.PENDING);
    }

    public record Answer(
            SystemPlaceCatalog.SystemPlace selected, int matchedCandidates,
            PublicPlaceLookupDraftMode mode, java.util.UUID calendarDraftId,
            Long calendarDraftRevision) {
    }

    public record BrowseAnswer(
            java.util.List<SystemPlaceCatalog.SystemPlace> all,
            java.util.List<SystemPlaceCatalog.SystemPlace> matches) {
    }
}
