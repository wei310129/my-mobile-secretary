package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationStep;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.RoutePlaceCreationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable Route -> Place child workflow with exact parent continuation. */
@Service
public class RoutePlaceCreationDraftService {

    public static final String QUESTION_CODE = "place.route-create-confirm";
    public static final String OFFER_QUESTION_CODE = "place.route-create-offer";
    public static final String DETAILS_QUESTION_CODE = "place.route-create-details";
    private static final Duration RETENTION = Duration.ofDays(7);

    private final ConversationScopeResolver scopes;
    private final RoutePlaceCreationDraftRepository repository;
    private final CalendarIntentDraftService calendarDrafts;
    private final PlaceService places;
    private final PlaceAliasService aliases;
    private final ConversationPendingQuestionService pendingQuestions;
    private final Clock clock;

    public RoutePlaceCreationDraftService(
            ConversationScopeResolver scopes,
            RoutePlaceCreationDraftRepository repository,
            CalendarIntentDraftService calendarDrafts,
            PlaceService places,
            PlaceAliasService aliases,
            ConversationPendingQuestionService pendingQuestions,
            Clock clock) {
        this.scopes = scopes;
        this.repository = repository;
        this.calendarDrafts = calendarDrafts;
        this.places = places;
        this.aliases = aliases;
        this.pendingQuestions = pendingQuestions;
        this.clock = clock;
    }

    @Transactional
    public Optional<RoutePlaceCreationDraft> startFromText(DraftView parent, String text) {
        RoutePlaceCreationPhrasePolicy.Request request =
                RoutePlaceCreationPhrasePolicy.parse(text).orElse(null);
        if (parent == null || request == null || parent.transportOrigin() != null) {
            return Optional.empty();
        }
        Optional<PlaceService.ResolvedPlaceCandidate> candidate =
                places.findPlaceCandidate(request.query());
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scopes.current(context);
        Instant now = Instant.now(clock);
        current(context, scope).ifPresent(existing -> {
            if (!existing.expireIfDue(now)) existing.cancel(now);
            repository.saveAndFlush(existing);
        });
        RoutePlaceCreationDraft draft;
        if (candidate.isPresent()) {
            draft = RoutePlaceCreationDraft.create(
                    scope,
                    context.channel(),
                    parent.id(),
                    parent.revision(),
                    request.alias(),
                    request.query(),
                    candidate.orElseThrow(),
                    now.plus(RETENTION),
                    now);
        } else {
            draft = RoutePlaceCreationDraft.offer(
                    scope,
                    context.channel(),
                    parent.id(),
                    parent.revision(),
                    request.alias(),
                    now.plus(RETENTION),
                    now);
            draft.requestDetails(now);
        }
        return Optional.of(repository.save(draft));
    }

    @Transactional
    public Optional<RoutePlaceCreationDraft> startOffer(DraftView parent, String alias) {
        String requestedAlias = RouteOriginInputPolicy.namedAlias(alias).orElse(null);
        if (parent == null || requestedAlias == null || parent.transportOrigin() != null) {
            return Optional.empty();
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scopes.current(context);
        Instant now = Instant.now(clock);
        Optional<RoutePlaceCreationDraft> existing = current(context, scope);
        if (existing.isPresent()) {
            RoutePlaceCreationDraft value = existing.orElseThrow();
            if (!value.expireIfDue(now)
                    && value.getParentCalendarDraftId().equals(parent.id())
                    && value.getParentCalendarDraftRevision() == parent.revision()
                    && value.getRequestedAlias().equalsIgnoreCase(requestedAlias)) {
                return Optional.of(value);
            }
            if (value.getStatus() == RoutePlaceCreationDraftStatus.PENDING) value.cancel(now);
            repository.saveAndFlush(value);
        }
        RoutePlaceCreationDraft draft = RoutePlaceCreationDraft.offer(
                scope,
                context.channel(),
                parent.id(),
                parent.revision(),
                requestedAlias,
                now.plus(RETENTION),
                now);
        return Optional.of(repository.save(draft));
    }

    @Transactional
    public Optional<Progress> startOrigin(
            DraftView parent, String structuredOrigin, String sourceText) {
        RouteOriginInputPolicy.Input input =
                RouteOriginInputPolicy.resolve(structuredOrigin, sourceText).orElse(null);
        if (parent == null || input == null || parent.transportOrigin() != null) {
            return Optional.empty();
        }
        if (input.kind() == RouteOriginInputPolicy.Kind.NAMED_PLACE) {
            return startOffer(parent, input.alias())
                    .map(child -> new Progress(Action.ASK_OFFER, null, parent, child, false));
        }
        RoutePlaceCreationDraft child = startOffer(parent, input.alias()).orElse(null);
        if (child == null) return Optional.empty();
        Instant now = Instant.now(clock);
        if (child.getStep() == RoutePlaceCreationStep.OFFER) {
            child.requestDetails(now);
            repository.saveAndFlush(child);
        }
        if (input.query() == null) {
            return Optional.of(new Progress(Action.ASK_DETAILS, null, parent, child, false));
        }
        return Optional.of(confirmTransientCandidate(
                child, parent, input.query(), DETAILS_QUESTION_CODE, now));
    }

    @Transactional
    public Optional<Progress> answer(String text) {
        RoutePlaceCreationDraft child = current().orElse(null);
        if (child == null) return Optional.empty();
        Instant now = Instant.now(clock);
        DraftView parent = calendarDrafts
                .findAvailableForLifecycle(child.getParentCalendarDraftId())
                .orElseThrow(() -> new IllegalStateException("route parent changed during place creation"));
        boolean parentAlreadyFinished = parent.status() == CalendarIntentDraftService.Status.MATERIALIZED
                && parent.revision() == child.getParentCalendarDraftRevision() + 1;
        if (parent.revision() != child.getParentCalendarDraftRevision()
                && !parentAlreadyFinished) {
            throw new IllegalStateException("route parent changed during place creation");
        }
        RoutePlaceCreationPhrasePolicy.Request declaration =
                RoutePlaceCreationPhrasePolicy.parse(text).orElse(null);
        if (child.getStep() == RoutePlaceCreationStep.OFFER) {
            String directQuery = declaration == null
                    ? directLocationQuery(text)
                    : declaration.query();
            if (directQuery != null) {
                return Optional.of(confirmCandidate(
                        child,
                        parent,
                        directQuery,
                        OFFER_QUESTION_CODE,
                        now));
            }
            OfferChoice offerChoice = OfferChoice.from(text).orElse(null);
            if (offerChoice == null) return Optional.empty();
            pendingQuestions.answerCurrent(OFFER_QUESTION_CODE);
            if (offerChoice == OfferChoice.DECLINE) {
                child.cancel(now);
                repository.save(child);
                return Optional.of(new Progress(Action.CANCELED, null, parent, child, false));
            }
            child.requestDetails(now);
            repository.save(child);
            return Optional.of(new Progress(Action.ASK_DETAILS, null, parent, child, false));
        }
        if (child.getStep() == RoutePlaceCreationStep.DETAILS) {
            if (RoutePlaceCreationPhrasePolicy.clearlyIncompatibleWithPlaceDetails(text)) {
                return Optional.empty();
            }
            String query;
            if (RouteOriginInputPolicy.isTransientAlias(child.getRequestedAlias())) {
                query = RouteOriginInputPolicy.resolve(text, text)
                        .map(RouteOriginInputPolicy.Input::query)
                        .orElseGet(() -> boundedQuery(text));
            } else {
                query = declaration == null ? boundedQuery(text) : declaration.query();
            }
            if (query == null) return Optional.empty();
            if (RouteOriginInputPolicy.isTransientAlias(child.getRequestedAlias())) {
                return Optional.of(confirmTransientCandidate(
                        child, parent, query, DETAILS_QUESTION_CODE, now));
            }
            return Optional.of(confirmCandidate(
                    child, parent, query, DETAILS_QUESTION_CODE, now));
        }
        Choice choice = Choice.from(text).orElse(null);
        if (choice == null) return Optional.empty();
        if (choice == Choice.CANCEL) {
            child.cancel(now);
            repository.save(child);
            pendingQuestions.answerCurrent(QUESTION_CODE);
            return Optional.of(new Progress(Action.CANCELED, choice, parent, child, false));
        }
        if (parentAlreadyFinished) {
            PlaceService.ResolvedPlaceCandidate candidate = child.candidate();
            boolean saved = false;
            String publicName = candidate.name();
            if (choice == Choice.SAVE) {
                Place place = places.createResolvedPlace(candidate);
                if (!child.getRequestedAlias().equalsIgnoreCase(place.getName())) {
                    aliases.remember(child.getRequestedAlias(), place.getId());
                }
                publicName = place.getName();
                saved = true;
            }
            DraftView withOrigin = calendarDrafts.setPrematureMaterializedRouteOrigin(
                    parent.id(),
                    parent.revision(),
                    new CalendarLocation(
                            publicName, candidate.latitude(), candidate.longitude()),
                    CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN);
            child.complete(now);
            repository.save(child);
            pendingQuestions.answerCurrent(QUESTION_CODE);
            return Optional.of(new Progress(
                    Action.COMPLETED, choice, withOrigin, child, saved));
        }
        PlaceService.ResolvedPlaceCandidate candidate = child.candidate();
        boolean saved = false;
        String publicName = candidate.name();
        if (choice == Choice.SAVE) {
            Place place = places.createResolvedPlace(candidate);
            if (!child.getRequestedAlias().equalsIgnoreCase(place.getName())) {
                aliases.remember(child.getRequestedAlias(), place.getId());
            }
            publicName = place.getName();
            saved = true;
        }
        DraftView withOrigin = calendarDrafts.setTransportOrigin(
                parent.id(),
                parent.revision(),
                new CalendarLocation(publicName, candidate.latitude(), candidate.longitude()),
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN);
        child.complete(now);
        repository.save(child);
        pendingQuestions.answerCurrent(QUESTION_CODE);
        return Optional.of(new Progress(Action.COMPLETED, choice, withOrigin, child, saved));
    }

    @Transactional
    public boolean recognizesCurrentAnswer(String text) {
        RoutePlaceCreationDraft child = current().orElse(null);
        if (child == null) return false;
        return switch (child.getStep()) {
            case OFFER -> OfferChoice.from(text).isPresent()
                    || RoutePlaceCreationPhrasePolicy.parse(text).isPresent()
                    || directLocationQuery(text) != null;
            case DETAILS -> !RoutePlaceCreationPhrasePolicy.clearlyIncompatibleWithPlaceDetails(text)
                    && boundedQuery(text) != null;
            case CONFIRM -> Choice.from(text).isPresent();
        };
    }

    private static String directLocationQuery(String text) {
        String query = boundedQuery(text);
        if (query == null) return null;
        if (GooglePlacesClient.isGoogleMapsLink(query)) return query;
        if (RoutePlaceCreationPhrasePolicy.clearlyIncompatibleWithPlaceDetails(query)) return null;
        return query.matches(".*(?:縣|市|區|鄉|鎮|村|里|路|街|巷|弄|號|樓|站|大樓|公司|公園|醫院|學校|餐廳|飯店|機場|港).*")
                ? query
                : null;
    }

    private Progress confirmCandidate(
            RoutePlaceCreationDraft child,
            DraftView parent,
            String query,
            String answeredQuestionCode,
            Instant now) {
        Optional<PlaceService.ResolvedPlaceCandidate> candidate = places.findPlaceCandidate(query);
        if (candidate.isEmpty()) {
            if (child.getStep() == RoutePlaceCreationStep.OFFER) {
                child.requestDetails(now);
                repository.save(child);
            }
            return new Progress(Action.RETRY_DETAILS, null, parent, child, false);
        }
        PlaceService.ResolvedPlaceCandidate resolved = candidate.orElseThrow();
        String persistedQuery = GooglePlacesClient.isGoogleMapsLink(query)
                ? resolved.name()
                : query;
        child.confirmCandidate(persistedQuery, resolved, now);
        repository.save(child);
        pendingQuestions.answerCurrent(answeredQuestionCode);
        return new Progress(Action.ASK_CONFIRM, null, parent, child, false);
    }

    private Progress confirmTransientCandidate(
            RoutePlaceCreationDraft child,
            DraftView parent,
            String query,
            String answeredQuestionCode,
            Instant now) {
        Optional<PlaceService.ResolvedPlaceCandidate> candidate = places.findPlaceCandidate(query);
        if (candidate.isEmpty()) {
            return new Progress(Action.RETRY_DETAILS, null, parent, child, false);
        }
        PlaceService.ResolvedPlaceCandidate resolved = candidate.orElseThrow();
        child.confirmCandidate(resolved.name(), resolved, now);
        DraftView withOrigin = calendarDrafts.setTransportOrigin(
                parent.id(),
                parent.revision(),
                new CalendarLocation(resolved.name(), resolved.latitude(), resolved.longitude()),
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN);
        child.complete(now);
        repository.save(child);
        pendingQuestions.answerCurrent(answeredQuestionCode);
        return new Progress(Action.COMPLETED, Choice.ONE_TIME, withOrigin, child, false);
    }

    private static String boundedAlias(String text) {
        if (text == null) return null;
        String value = text.strip().replaceAll("^[「『\"']+|[」』\"']+$", "");
        if (value.isBlank() || value.length() > 80 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return null;
        }
        return value;
    }

    private static String boundedQuery(String text) {
        if (text == null) return null;
        String value = text.strip();
        int maximumLength = GooglePlacesClient.isGoogleMapsLink(value) ? 2048 : 300;
        if (value.length() < 2
                || value.length() > maximumLength
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            return null;
        }
        return value;
    }

    @Transactional
    public Optional<RoutePlaceCreationDraft> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scopes.current(context);
        Optional<RoutePlaceCreationDraft> current = current(context, scope);
        if (current.isPresent() && current.orElseThrow().expireIfDue(Instant.now(clock))) {
            repository.save(current.orElseThrow());
            return Optional.empty();
        }
        return current;
    }

    @Transactional(readOnly = true)
    public Optional<RoutePlaceCreationDraft> find(UUID id) {
        return id == null ? Optional.empty() : repository.findById(id);
    }

    @Transactional(readOnly = true)
    public boolean hasUnfinishedChild(UUID parentCalendarDraftId) {
        if (parentCalendarDraftId == null) return false;
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        return repository
                .existsByWorkspaceIdAndCreatedByUserIdAndParentCalendarDraftIdAndStatus(
                        context.workspaceId(),
                        context.actorId(),
                        parentCalendarDraftId,
                        RoutePlaceCreationDraftStatus.PENDING);
    }

    @Transactional
    public boolean cancel(UUID id) {
        RoutePlaceCreationDraft draft = find(id).orElse(null);
        if (draft == null || draft.getStatus() != RoutePlaceCreationDraftStatus.PENDING) return false;
        draft.cancel(Instant.now(clock));
        repository.save(draft);
        return true;
    }

    private Optional<RoutePlaceCreationDraft> current(
            WorkspaceContext context, ConversationScopeKey scope) {
        return repository
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(),
                        context.actorId(),
                        context.channel(),
                        scope.digest(),
                        RoutePlaceCreationDraftStatus.PENDING);
    }

    public enum Choice {
        SAVE,
        ONE_TIME,
        CANCEL;

        static Optional<Choice> from(String text) {
            return RoutePlaceCreationChoiceCatalog.usePlace()
                    .resolveAction(text)
                    .map(Choice::valueOf);
        }
    }

    private enum OfferChoice {
        ACCEPT,
        DECLINE;

        static Optional<OfferChoice> from(String text) {
            String compact = text == null ? "" : text.replaceAll("[\\s，。！？!?：:；;]", "");
            if (compact.equals("不要") || compact.equals("不用")
                    || compact.equals("先不要") || compact.equals("取消")
                    || compact.equals("不要建立") || compact.equals("不用建立")) {
                return Optional.of(DECLINE);
            }
            if (compact.matches("(?:要)?(?:建立|新增)(?:新的?)?(?:地點)?")
                    || compact.equals("要") || compact.equals("好")
                    || compact.equals("可以") || compact.equals("是")) {
                return Optional.of(ACCEPT);
            }
            return Optional.empty();
        }
    }

    public enum Action {
        ASK_OFFER,
        ASK_DETAILS,
        ASK_CONFIRM,
        RETRY_DETAILS,
        COMPLETED,
        CANCELED
    }

    public record Progress(
            Action action,
            Choice choice,
            DraftView parent,
            RoutePlaceCreationDraft child,
            boolean saved) {}
}
