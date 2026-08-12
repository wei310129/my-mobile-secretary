package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteAssessment;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteAssessmentService;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteProjectionService;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderRuleView;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusDirective;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationOperationCompletionGate;
import com.aproject.aidriven.mymobilesecretary.conversation.application.PublicPlaceLookupDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.RouteConversationOperationCompletionContributor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.RoutePlaceCreationChoiceCatalog;
import com.aproject.aidriven.mymobilesecretary.conversation.application.RoutePlaceCreationDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraftMode;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.SystemPlaceCatalog;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest.TravelMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Secretary-style bridge from an actor-private typed draft to conversation focus. */
@Service
public class CalendarIntentDraftConversationService {

    public static final String FOCUS_DOMAIN = "CALENDAR_DRAFT";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter ROUTE_DATE =
            DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter ROUTE_TIME =
            DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarIntentDraftService drafts;
    private final ConversationFocusService focuses;
    private final CalendarIntentDraftPreflightResponsePolicy preflightResponses;
    private final ConversationOperationCompletionGate completionGate;
    private PlaceAliasService placeAliases;
    private SystemPlaceCatalog systemPlaceCatalog;
    private PublicPlaceLookupDraftService publicPlaceDrafts;
    private com.aproject.aidriven.mymobilesecretary.conversation.application
                    .ConversationPendingQuestionService
            pendingQuestions;
    private com.aproject.aidriven.mymobilesecretary.planner.application
                    .ProviderNeutralRouteService
            routePlanner;
    private RouteOriginContextService routeOriginContexts;
    private com.aproject.aidriven.mymobilesecretary.geo.application
                    .ActorLocationPreferenceService
            actorLocationPreferences;
    private RouteOperationPreferenceService routeOperationPreferences;
    private PersonalRouteProjectionService routeProjection;
    private PersonalRouteAssessmentService routeAssessments;
    private CalendarStartReminderLifecycleService startReminderLifecycle;
    private RoutePlaceCreationDraftService routePlaceCreations;

    public CalendarIntentDraftConversationService(
            CalendarIntentDraftService drafts,
            ConversationFocusService focuses,
            CalendarIntentDraftPreflightResponsePolicy preflightResponses,
            ConversationOperationCompletionGate completionGate) {
        this.drafts = drafts;
        this.focuses = focuses;
        this.preflightResponses = preflightResponses;
        this.completionGate = completionGate;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPlaceResolutionDependencies(
            PlaceAliasService placeAliases, SystemPlaceCatalog systemPlaceCatalog) {
        this.placeAliases = placeAliases;
        this.systemPlaceCatalog = systemPlaceCatalog;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPublicPlaceDrafts(PublicPlaceLookupDraftService publicPlaceDrafts) {
        this.publicPlaceDrafts = publicPlaceDrafts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRoutePlaceCreations(RoutePlaceCreationDraftService routePlaceCreations) {
        this.routePlaceCreations = routePlaceCreations;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRoutePlanner(
            com.aproject.aidriven.mymobilesecretary.planner.application
                            .ProviderNeutralRouteService routePlanner) {
        this.routePlanner = routePlanner;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPendingQuestions(
            com.aproject.aidriven.mymobilesecretary.conversation.application
                            .ConversationPendingQuestionService
                    pendingQuestions) {
        this.pendingQuestions = pendingQuestions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRouteOriginDependencies(
            RouteOriginContextService routeOriginContexts,
            com.aproject.aidriven.mymobilesecretary.geo.application
                            .ActorLocationPreferenceService
                    actorLocationPreferences) {
        this.routeOriginContexts = routeOriginContexts;
        this.actorLocationPreferences = actorLocationPreferences;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRouteOperationPreferences(RouteOperationPreferenceService routeOperationPreferences) {
        this.routeOperationPreferences = routeOperationPreferences;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRouteAdjustmentDependencies(
            PersonalRouteProjectionService routeProjection,
            PersonalRouteAssessmentService routeAssessments) {
        this.routeProjection = routeProjection;
        this.routeAssessments = routeAssessments;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStartReminderLifecycle(
            CalendarStartReminderLifecycleService startReminderLifecycle) {
        this.startReminderLifecycle = startReminderLifecycle;
    }

    public IntentResult propose(IntentCommand command) {
        if (!CalendarIntentRecurrencePolicy.resolve(command).valid()) {
            return recurrenceClarification();
        }
        DraftView draft = drafts.propose(command);
        return IntentResult.message(
                        IntentResult.Action.SUGGESTION_MADE,
                        "我先整理成「%s」，時間是 %s，尚未放進行事曆。要照這個版本建立嗎？"
                                .formatted(draft.title(), placement(draft.placement())))
                .withFocusBinding(binding(draft));
    }

    public IntentResult createWithPreflight(IntentCommand command) {
        if (!CalendarIntentRecurrencePolicy.resolve(command).valid()) {
            return recurrenceClarification();
        }
        DraftView draft = drafts.propose(command);
        SystemPlaceCatalog.Resolution unresolved = systemResolution(command.placeName());
        if (unresolved != null
                && unresolved.status()
                        == SystemPlaceCatalog.Resolution.Status.ENTITY_AMBIGUOUS) {
            if (publicPlaceDrafts != null) {
                publicPlaceDrafts.start(
                        unresolved,
                        PublicPlaceLookupDraftMode.CALENDAR_LOCATION,
                        new PublicPlaceLookupDraft.CalendarDraftReference(
                                draft.id(), draft.revision()));
            }
            String label = unresolved.category() == null
                    ? "這個名稱" : unresolved.category().publicLabel();
            return IntentResult.clarificationNeeded(
                            "系統找到分屬不同縣市的同名地點；行程的其他已確認資訊已保留，"
                                    + "目前不會建立行程或自行猜測地點。",
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "place.system-region", "place.region",
                                            "你指的是哪個縣市的%s？".formatted(label), 10))
                    .withFocusBinding(binding(draft));
        }
        var confirmation = drafts.confirm(draft.id(), draft.revision());
        String locationDecision = locationDecision(command.placeName());
        if (confirmation.awaitingRouteConfirmation()) {
            return IntentResult.message(
                            IntentResult.Action.SUGGESTION_MADE,
                            preflightResponses.describe(confirmation.preflight())
                                    + locationDecision)
                    .withFocusBinding(binding(confirmation.draft()));
        }
        DraftView saved = confirmation.draft();
        return IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED,
                "已建立行程「%s」，時間是 %s。%s"
                        .formatted(saved.title(), placement(saved.placement()), locationDecision));
    }

    public IntentResult createRouteWithPreflight(IntentCommand command) {
        CalendarPlacement placement = CalendarIntentPlacementResolver.resolve(command);
        RouteJourneyKind journeyKind = RouteJourneyKind.resolve(command, placement);
        String origin = command.safeOptions().fromPlaceName();
        if (origin == null || origin.isBlank()) {
            return createRouteWithoutOrigin(command, placement, journeyKind);
        }
        if (journeyKind == RouteJourneyKind.STANDALONE_TRIP) {
            return createStandaloneRoute(command);
        }
        IntentResult created = createWithPreflight(command);
        if (created.action() != IntentResult.Action.SCHEDULE_CONFIRMED) return created;
        drafts.offerTransportForCurrentRequest();
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(
                        "route.transport-offer", "要不要我也幫你規劃交通方式？");
        return new IntentResult(
                created.action(), created.message() + "\n\n" + question.prompt(),
                created.task(), created.decision(), created.focusNotice(),
                created.focusBinding(), created.focusDirective(), question);
    }

    private IntentResult createStandaloneRoute(IntentCommand command) {
        DraftView draft = drafts.propose(command);
        return createStandaloneRoute(command, draft, "");
    }

    private IntentResult createRouteWithoutOrigin(
            IntentCommand command,
            CalendarPlacement placement,
            RouteJourneyKind journeyKind) {
        DraftView draft = drafts.propose(command);
        if (draft.location() == null || routeOriginContexts == null) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.endpoint",
                                            "route.endpoint",
                                            "我還不能確定這趟行程的起點或目的地。您要補充哪一個地點？",
                                            10))
                    .withFocusBinding(binding(draft));
        }
        RouteOriginContextDecision decision = routeOriginContexts.resolve(routeRequestedTime(placement));
        if (decision.hasLocation()) {
            DraftView withOrigin = drafts.setTransportOrigin(
                    draft.id(),
                    draft.revision(),
                    decision.location(),
                    decision.kind() == RouteOriginContextDecision.Kind.HOME
                            ? CalendarIntentDraftService.EndpointSource.CONFIRMED_HOME
                            : CalendarIntentDraftService.EndpointSource.CONFIRMED_CONTEXT);
            String notice = decision.kind() == RouteOriginContextDecision.Kind.HOME
                    ? "這次先以家裡為出發地。\n\n"
                    : "";
            return journeyKind == RouteJourneyKind.STANDALONE_TRIP
                    ? createStandaloneRoute(command, withOrigin, notice)
                    : completeActivityWithTransport(withOrigin, notice);
        }
        var mode = journeyKind == RouteJourneyKind.STANDALONE_TRIP
                ? routeMode(command)
                : null;
        DraftView prepared = drafts.prepareStandaloneRouteRequest(
                draft.id(),
                draft.revision(),
                mode != null
                                && mode.status()
                                == com.aproject.aidriven.mymobilesecretary.planner.application
                                        .TransportModePolicy.Status.RESOLVED
                        ? mode.mode()
                        : null,
                routeTimeRole(command.sourceText()));
        if (decision.kind() == RouteOriginContextDecision.Kind.POSSIBLY_AWAY) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.origin-context",
                                            "route.origin",
                                            "依前後行程看，這次可能不是從家裡出發。這趟要從哪裡出發？",
                                            10))
                    .withFocusBinding(binding(prepared));
        }
        return IntentResult.clarificationNeeded(
                        com.aproject.aidriven.mymobilesecretary.intent.application
                                .ClarificationStep.blocking(
                                        "route.home-location",
                                        "route.home",
                                        "我還沒有您的住家地點。要把哪個地點設為家裡？",
                                        10))
                .withFocusBinding(binding(prepared));
    }

    private IntentResult completeActivityWithTransport(DraftView draft, String originNotice) {
        var confirmation = drafts.confirm(draft.id(), draft.revision());
        if (confirmation.awaitingRouteConfirmation()) {
            return IntentResult.message(
                            IntentResult.Action.SUGGESTION_MADE,
                            originNotice + preflightResponses.describe(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft()));
        }
        DraftView offered = drafts.offerTransport(
                confirmation.draft().id(), confirmation.draft().revision());
        IntentResult created = IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED,
                originNotice
                        + "已建立行程「%s」，時間是 %s。"
                                .formatted(offered.title(), placement(offered.placement())));
        return withChoiceQuestion(created, offered, RouteCalendarChoiceCatalog.transportOffer());
    }

    /** Stages only typed standalone-route state; provider and Calendar mutations remain forbidden. */
    @org.springframework.transaction.annotation.Transactional
    public Optional<DraftView> stageStandaloneRouteForContextChoice(IntentCommand command) {
        if (command == null
                || command.type() != IntentCommand.Type.PLAN_ROUTE_ITINERARY
                || !CalendarIntentRecurrencePolicy.resolve(command).valid()) {
            return Optional.empty();
        }
        CalendarPlacement placement = CalendarIntentPlacementResolver.resolve(command);
        if (RouteJourneyKind.resolve(command, placement) != RouteJourneyKind.STANDALONE_TRIP) {
            return Optional.empty();
        }
        DraftView draft = drafts.propose(command);
        var mode = routeMode(command);
        return Optional.of(drafts.prepareStandaloneRouteRequest(
                draft.id(),
                draft.revision(),
                mode.status()
                                == com.aproject.aidriven.mymobilesecretary.planner.application
                                        .TransportModePolicy.Status.RESOLVED
                        ? mode.mode()
                        : null,
                routeTimeRole(command.sourceText())));
    }

    /** Stages a validated non-route Calendar draft without materializing a Calendar plan. */
    @org.springframework.transaction.annotation.Transactional
    public Optional<DraftView> stageCalendarForContextChoice(IntentCommand command) {
        if (command == null
                || command.type() != IntentCommand.Type.CREATE_SCHEDULE
                || !CalendarIntentRecurrencePolicy.resolve(command).valid()) {
            return Optional.empty();
        }
        return Optional.of(drafts.propose(command));
    }

    /** Activates a staged Calendar draft without asking the user to repeat the request. */
    @org.springframework.transaction.annotation.Transactional
    public IntentResult activateStagedCalendarDraft(UUID draftId) {
        DraftView draft = drafts.findAvailableForLifecycle(draftId)
                .filter(candidate -> candidate.status() == Status.PENDING)
                .filter(candidate -> candidate.routeJourneyKind() == null)
                .orElseThrow(() -> new IllegalStateException(
                        "staged calendar draft is no longer available"));
        return IntentResult.message(
                        IntentResult.Action.SUGGESTION_MADE,
                        "我先整理成「%s」，時間是 %s，尚未放進行事曆。要照這個版本建立嗎？"
                                .formatted(draft.title(), placement(draft.placement())))
                .withFocusBinding(binding(draft));
    }

    /** Continues a previously staged typed route without reinterpreting or retaining user text. */
    @org.springframework.transaction.annotation.Transactional
    public IntentResult activateStagedStandaloneRoute(UUID draftId) {
        DraftView draft = drafts.findAvailableForLifecycle(draftId)
                .filter(candidate -> candidate.status() == Status.PENDING)
                .filter(candidate -> candidate.routeJourneyKind() == RouteJourneyKind.STANDALONE_TRIP)
                .filter(candidate -> candidate.routeProviderStatus()
                        == CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED)
                .orElseThrow(() -> new IllegalStateException(
                        "staged standalone route is no longer available"));
        return continueStandaloneRoute(draft, "");
    }

    private IntentResult createStandaloneRoute(
            IntentCommand command, DraftView draft, String originNotice) {
        var mode = routeMode(command);
        var timeRole = routeTimeRole(command.sourceText());
        if (draft.location() == null) {
            DraftView prepared = prepareStandaloneRouteQuestion(draft, mode, timeRole);
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.endpoint", "route.endpoint",
                                            "我還不能確定這趟行程的起點或目的地。您要補充哪一個地點？", 10))
                    .withFocusBinding(binding(prepared));
        }
        if (draft.transportOrigin() == null) {
            DraftView prepared = prepareStandaloneRouteQuestion(draft, mode, timeRole);
            String requestedOrigin = command.safeOptions().fromPlaceName();
            if (routePlaceCreations != null
                    && requestedOrigin != null
                    && !requestedOrigin.isBlank()) {
                Optional<RoutePlaceCreationDraftService.Progress> origin =
                        routePlaceCreations.startOrigin(
                                prepared, requestedOrigin, command.sourceText());
                if (origin.isPresent()) {
                    return routePlaceOriginStart(origin.orElseThrow());
                }
            }
            return unconfirmedExplicitOriginQuestion(prepared);
        }
        if (mode.status()
                != com.aproject.aidriven.mymobilesecretary.planner.application
                        .TransportModePolicy.Status.RESOLVED) {
            DraftView prepared = prepareStandaloneRouteQuestion(draft, mode, timeRole);
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.transport-mode", "route.mode",
                                            "這趟要用大眾運輸、開車、機車，還是步行？", 10))
                    .withFocusBinding(binding(prepared));
        }
        if (routePlanner == null) {
            return providerUnavailable(draft, mode.mode(), timeRole);
        }
        Instant requestedTime = routeRequestedTime(draft.placement());
        if (requestedTime == null) {
            DraftView prepared = prepareStandaloneRouteQuestion(draft, mode, timeRole);
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.time", "route.time",
                                            "這趟預計幾點出發？", 10))
                    .withFocusBinding(binding(prepared));
        }
        var route = routePlanner.plan(
                new com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest(
                                draft.transportOrigin().latitude(),
                                draft.transportOrigin().longitude(),
                                draft.location().latitude(),
                                draft.location().longitude(),
                                mode.mode(),
                                timeRole,
                                requestedTime,
                                Duration.ZERO));
        if (route.status()
                != com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.Status.AVAILABLE) {
            return providerUnavailable(draft, mode.mode(), timeRole);
        }
        var option = route.options().getFirst();
        DraftView prepared = drafts.prepareStandaloneRoute(
                draft.id(), draft.revision(), mode.mode(), timeRole, option);
        var confirmation = drafts.confirmStandaloneRoute(
                prepared.id(), prepared.revision());
        if (confirmation.awaitingRouteConfirmation()) {
            String details = preflightResponses.details(confirmation.preflight());
            String preview = originNotice + "我先依路線資料規劃："
                    + standaloneRouteDetails(command, confirmation.draft(), option)
                    + "\n\n"
                    + details;
            return IntentResult.choiceNeeded(
                            preview, preflightResponses.choiceQuestion(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft()));
        }
        return withOriginNotice(
                standaloneRouteResult(command, confirmation, option), originNotice);
    }

    private DraftView prepareStandaloneRouteQuestion(
            DraftView draft,
            com.aproject.aidriven.mymobilesecretary.planner.application
                            .TransportModePolicy.Resolution
                    mode,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TimeRole
                    timeRole) {
        return drafts.prepareStandaloneRouteRequest(
                draft.id(),
                draft.revision(),
                mode.status()
                                == com.aproject.aidriven.mymobilesecretary.planner.application
                                        .TransportModePolicy.Status.RESOLVED
                        ? mode.mode()
                        : null,
                timeRole);
    }

    private IntentResult providerUnavailable(
            DraftView draft,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TravelMode
                    mode,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TimeRole
                    timeRole) {
        DraftView unavailable = drafts.markStandaloneRouteUnavailable(
                draft.id(), draft.revision(), mode, timeRole);
        return IntentResult.clarificationNeeded(
                        "起點、目的地和時間都已確認，但目前暫時查不到可採用的路線。",
                        com.aproject.aidriven.mymobilesecretary.intent.application
                                .ClarificationStep.blocking(
                                        "route.provider-unavailable", "route.provider-choice",
                                        "要保留這份待確認安排，之後再查路線嗎？", 10))
                .withFocusBinding(binding(unavailable));
    }

    private static com.aproject.aidriven.mymobilesecretary.planner.application
                    .TransportModePolicy.Resolution
            routeMode(IntentCommand command) {
        return com.aproject.aidriven.mymobilesecretary.planner.application
                .TransportModePolicy.resolve(String.join(
                        " ",
                        safe(command.sourceText()),
                        safe(command.safeOptions().fromPlaceName()),
                        safe(command.placeName())));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Instant routeRequestedTime(CalendarPlacement placement) {
        return switch (placement) {
            case CalendarPlacement.TimedPoint point -> point.time();
            case CalendarPlacement.TimedInterval interval -> interval.start();
            case CalendarPlacement.AllDay ignored -> null;
        };
    }

    private static IntentResult withOriginNotice(IntentResult result, String originNotice) {
        if (originNotice == null || originNotice.isBlank()) return result;
        return new IntentResult(
                result.action(),
                originNotice + result.message(),
                result.task(),
                result.decision(),
                result.focusNotice(),
                result.focusBinding(),
                result.focusDirective(),
                result.nextQuestion());
    }

    private IntentResult standaloneRouteResult(
            IntentCommand command,
            CalendarIntentDraftService.ConfirmationResult confirmation,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        return standaloneRouteResult(
                command.safeOptions().fromPlaceName(),
                command.placeName(),
                confirmation,
                option);
    }

    private IntentResult standaloneRouteResult(
            String requestedOrigin,
            String requestedDestination,
            CalendarIntentDraftService.ConfirmationResult confirmation,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        DraftView saved = confirmation.draft();
        completionGate.requireCompleted(
                RouteConversationOperationCompletionContributor.KIND,
                saved.id());
        String details = standaloneRouteDetails(
                requestedOrigin, requestedDestination, saved, option);
        String risk = routeRiskDetails(confirmation);
        if (!risk.isBlank()) {
            String reasons = routeRiskReasonDetails(confirmation);
            IntentResult completed = IntentResult.message(
                    IntentResult.Action.SCHEDULE_CONFIRMED,
                    risk + "\n\n" + details + "\n\n"
                            + "💥 衝突原因：\n" + reasons + "\n\n"
                            + routeRiskRecommendation(confirmation));
            var choice = hasNextConnectionRisk(confirmation)
                    ? RouteCalendarChoiceCatalog.keepOnlyConflict()
                    : RouteCalendarChoiceCatalog.scheduleConflict();
            return withChoiceQuestion(completed, saved, choice);
        }
        MissingAdjacentSide missing = missingAdjacentSide(confirmation);
        if (missing == null) {
            IntentResult completed = IntentResult.message(
                    IntentResult.Action.SCHEDULE_CONFIRMED,
                    "好的，已替您安排✅：\n\n" + details + "\n\n目前沒有與其他行程衝突。");
            return withPostCreationOptions(completed, saved);
        }
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(
                        missing.questionCode(), missing.question());
        IntentResult completed = IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED,
                "好的，已替您安排✅：\n\n" + details + "\n\n" + missing.explanation());
        return new IntentResult(
                completed.action(), completed.message() + "\n\n" + question.prompt(),
                completed.task(), completed.decision(), completed.focusNotice(),
                completed.focusBinding(), completed.focusDirective(), question);
    }

    private IntentResult withDepartureReminderQuestion(
            IntentResult completed, DraftView draft) {
        boolean rideHail = "RIDE_HAIL".equals(draft.transportMode());
        return withChoiceQuestion(
                completed, draft, RouteCalendarChoiceCatalog.departureReminder(rideHail));
    }

    private IntentResult withChoiceQuestion(
            IntentResult completed,
            DraftView draft,
            com.aproject.aidriven.mymobilesecretary.intent.application
                            .PublicConversationChoiceQuestion
                    choice) {
        String prompt = com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationChoiceRenderer.render(choice);
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(
                        choice.code(), prompt);
        return new IntentResult(
                completed.action(),
                completed.message() + "\n\n" + question.prompt(),
                completed.task(),
                completed.decision(),
                completed.focusNotice(),
                binding(draft),
                completed.focusDirective(),
                question);
    }

    private IntentResult withPostCreationOptions(IntentResult completed, DraftView draft) {
        IntentResult operationQuestion = postCreationOperationQuestion(completed, draft);
        return operationQuestion == null
                ? withDepartureReminderQuestion(completed, draft)
                : operationQuestion;
    }

    private IntentResult withPostCreationOptions(
            IntentResult completed,
            DraftView draft,
            CalendarStartReminderLifecycleService.Decision reminderDecision) {
        IntentResult operationQuestion = postCreationOperationQuestion(completed, draft);
        return operationQuestion == null
                ? withAdjustedDepartureReminder(completed, draft, reminderDecision)
                : operationQuestion;
    }

    private IntentResult withAdjustedDepartureReminder(
            IntentResult completed,
            DraftView draft,
            CalendarStartReminderLifecycleService.Decision decision) {
        if (decision == null
                || decision.action()
                        == CalendarStartReminderLifecycleService.Action.ASK) {
            return withDepartureReminderQuestion(completed, draft);
        }
        if (decision.action()
                == CalendarStartReminderLifecycleService.Action.RETAINED_RELATIVE) {
            return new IntentResult(
                    completed.action(),
                    completed.message() + "\n\n" + decision.message(),
                    completed.task(),
                    completed.decision(),
                    completed.focusNotice(),
                    binding(draft),
                    completed.focusDirective(),
                    null);
        }
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(
                        "route.departure-reminder", decision.message());
        return new IntentResult(
                completed.action(),
                completed.message() + "\n\n" + question.prompt(),
                completed.task(),
                completed.decision(),
                completed.focusNotice(),
                binding(draft),
                completed.focusDirective(),
                question);
    }

    private static MissingAdjacentSide missingAdjacentSide(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        if (confirmation.preflight() == null
                || !"INSUFFICIENT_EVIDENCE".equals(confirmation.preflight().status())) {
            return null;
        }
        java.util.UUID draftId = confirmation.draft().id();
        for (PersonalRouteAssessment assessment : confirmation.preflight().assessments()) {
            if (assessment.status() != PersonalRouteStatus.INSUFFICIENT_EVIDENCE
                    || assessment.from() == null
                    || assessment.to() == null) {
                continue;
            }
            boolean fromCandidate = assessment.from().planId().equals(draftId);
            boolean toCandidate = assessment.to().planId().equals(draftId);
            if (!fromCandidate && toCandidate) return MissingAdjacentSide.PREVIOUS;
            if (fromCandidate && !toCandidate) return MissingAdjacentSide.NEXT;
        }
        return null;
    }

    private String standaloneRouteDetails(
            IntentCommand command,
            DraftView saved,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        return standaloneRouteDetails(
                command.safeOptions().fromPlaceName(), command.placeName(), saved, option);
    }

    private String standaloneRouteDetails(
            String requestedOrigin,
            String requestedDestination,
            DraftView saved,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        String origin = routePlaceLabel(requestedOrigin, saved.transportOrigin());
        String destination = routePlaceLabel(requestedDestination, saved.location());
        ZonedDateTime departure = ZonedDateTime.ofInstant(
                option.departAt(), ZoneId.of("Asia/Taipei"));
        ZonedDateTime arrival = ZonedDateTime.ofInstant(
                option.arriveAt(), ZoneId.of("Asia/Taipei"));
        String depart = departure.format(ROUTE_TIME);
        String arrive = arrival.format(ROUTE_TIME);
        String source = routeSource(option);
        String guidance = routeGuidance(option);
        RouteOperationWindow operations = routeOperationWindow(option);
        String maps = routeMaps(saved, origin, destination, option.mode());
        String recurrence = routeRecurrenceLine(saved.recurrenceRule(), saved.recurrenceUntil());
        return ("本次行程：\n"
                + "🏷️ %s\n"
                + "📅 %s（%s）\n"
                + "🕒 %s ~ %s\n"
                + "%s"
                + "%s 📍%s｜出發\n"
                + "⬇️ 行程約 %d 分鐘（%s）\n"
                + "%s 📍%s｜抵達\n\n"
                + "%s%s")
                .formatted(
                        routeTitle(destination),
                        departure.format(ROUTE_DATE),
                        weekday(departure),
                        depart,
                        arrive,
                        recurrence,
                        depart,
                        origin,
                        option.duration().toMinutes(),
                        source,
                        arrive,
                        destination,
                        guidance + operationLines(option, operations),
                        maps);
    }

    static String routeRecurrenceLine(String rule, java.time.LocalDate until) {
        if (rule == null || rule.isBlank()) return "";
        String label = switch (rule) {
            case "DAILY" -> "每天";
            case "WEEKDAYS" -> "每個平日";
            case "WEEKLY" -> "每週";
            case "MONTHLY_NTH_WEEKDAY" -> "每月指定星期";
            default -> throw new IllegalArgumentException("Unsupported route recurrence rule");
        };
        String boundary = until == null ? "" : "，到 " + until + " 為止";
        return "🔁 重複：" + label + boundary + "\n";
    }

    private static String routeRiskDetails(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        if (confirmation.preflight() == null
                || confirmation.preflight().assessments().isEmpty()) {
            return "";
        }
        var existingByPlan = new LinkedHashMap<java.util.UUID,
                com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint>();
        java.util.UUID currentPlan = confirmation.draft().id();
        confirmation.preflight().assessments().stream()
                .filter(PersonalRouteAssessment::isRouteRisk)
                .forEach(assessment -> {
                    addExisting(existingByPlan, currentPlan, assessment.from());
                    addExisting(existingByPlan, currentPlan, assessment.to());
                });
        if (existingByPlan.isEmpty()) return "";
        StringBuilder result = new StringBuilder()
                .append("⚠️ 您的規劃與 ")
                .append(existingByPlan.size())
                .append(" 個既有行程有衝突風險，以下資訊請您確認：\n\n")
                .append("💥 衝突行程：");
        for (var constraint : existingByPlan.values()) {
            result.append("\n🏷️ ").append(constraint.planTitle())
                    .append("\n🕒 ")
                    .append(ZonedDateTime.ofInstant(
                                    constraint.effectiveTime(), ZoneId.of("Asia/Taipei"))
                            .format(ROUTE_TIME));
            if (constraint.location() != null) {
                result.append("\n📍 ").append(constraint.location().label());
            }
            result.append('\n');
        }
        return result.toString().stripTrailing();
    }

    private static String routeRiskReasonDetails(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        java.util.UUID currentPlan = confirmation.draft().id();
        return confirmation.preflight().assessments().stream()
                .filter(PersonalRouteAssessment::isRouteRisk)
                .map(assessment -> routeRiskReason(assessment, currentPlan))
                .distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String routeRiskReason(
            PersonalRouteAssessment assessment, java.util.UUID currentPlan) {
        boolean previousConnection = assessment.to().planId().equals(currentPlan);
        var existing = previousConnection ? assessment.from() : assessment.to();
        var current = previousConnection ? assessment.to() : assessment.from();
        String existingTime = ZonedDateTime.ofInstant(
                        existing.effectiveTime(), ZoneId.of("Asia/Taipei"))
                .format(ROUTE_TIME);
        String currentTime = ZonedDateTime.ofInstant(
                        current.effectiveTime(), ZoneId.of("Asia/Taipei"))
                .format(ROUTE_TIME);
        long gapMinutes = Math.max(0, assessment.availableGap().toMinutes());
        long requiredMinutes = Math.max(0, assessment.requiredTravel().toMinutes());
        long shortageMinutes = Math.max(0, requiredMinutes - gapMinutes);
        String order = previousConnection
                ? "「%s」排在 %s，本次行程 %s 出發，中間只有 %d 分鐘"
                        .formatted(existing.planTitle(), existingTime, currentTime, gapMinutes)
                : "本次行程 %s 抵達，「%s」排在 %s，中間只有 %d 分鐘"
                        .formatted(currentTime, existing.planTitle(), existingTime, gapMinutes);
        return "• %s；兩地移動約需 %d 分鐘，至少不足 %d 分鐘。"
                .formatted(order, requiredMinutes, shortageMinutes);
    }

    private static boolean hasNextConnectionRisk(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        java.util.UUID currentPlan = confirmation.draft().id();
        return confirmation.preflight().assessments().stream()
                .filter(PersonalRouteAssessment::isRouteRisk)
                .anyMatch(assessment -> assessment.from().planId().equals(currentPlan));
    }

    private static String routeRiskRecommendation(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        return hasNextConnectionRisk(confirmation)
                ? "建議把本次行程提前，或延後下一個行程；往後安排本次行程無法解決。"
                : "建議把本次行程往後安排，系統會重新查路線確認安全時間。";
    }

    private static String routeRiskQuestion(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        return hasNextConnectionRisk(confirmation)
                ? "往後安排無法解決這個衝突。要忽略衝突並保留原安排嗎？"
                : "要把本次行程往後安排到安全時間，還是忽略這項衝突？";
    }

    private static String routeRiskQuestionCode(
            CalendarIntentDraftService.ConfirmationResult confirmation) {
        return hasNextConnectionRisk(confirmation)
                ? "route.schedule-conflict-keep-only"
                : "route.schedule-conflict";
    }

    private static void addExisting(
            LinkedHashMap<java.util.UUID,
                            com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint>
                    existingByPlan,
            java.util.UUID currentPlan,
            com.aproject.aidriven.mymobilesecretary.calendar.adoption.PersonalRouteConstraint constraint) {
        if (constraint != null && !constraint.planId().equals(currentPlan)) {
            existingByPlan.putIfAbsent(constraint.planId(), constraint);
        }
    }

    private static String routeTitle(String destination) {
        return destination.startsWith("去") ? destination : "去" + destination;
    }

    private static String weekday(ZonedDateTime dateTime) {
        return switch (dateTime.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private static String routeMaps(
            DraftView draft,
            String originLabel,
            String destinationLabel,
            TravelMode travelMode) {
        boolean publicOrigin = draft.transportOriginSource()
                        == CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN
                && hasValidMapCoordinates(draft.transportOrigin());
        boolean publicDestination = draft.locationSource()
                        == CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN
                && hasValidMapCoordinates(draft.location());
        StringBuilder maps = new StringBuilder();
        appendMapPoint(maps, publicOrigin, originLabel, draft.transportOrigin());
        appendMapPoint(maps, publicDestination, destinationLabel, draft.location());
        if (publicOrigin && publicDestination) {
            maps.append("\n🗺️ Google Maps 路線規劃\n")
                    .append(googleMapsRouteUrl(
                            draft.transportOrigin(), draft.location(), travelMode));
        }
        return maps.isEmpty() ? "" : "\n\n【Google Maps 地點】" + maps;
    }

    static String googleMapsRouteUrl(
            CalendarLocation origin,
            CalendarLocation destination,
            TravelMode travelMode) {
        if (!hasValidMapCoordinates(origin)
                || !hasValidMapCoordinates(destination)
                || travelMode == null) {
            throw new IllegalArgumentException("Public route map requires valid typed endpoints and mode");
        }
        return "https://www.google.com/maps/dir/?api=1&origin="
                + mapCoordinate(origin)
                + "&destination="
                + mapCoordinate(destination)
                + "&travelmode="
                + googleMapsTravelMode(travelMode);
    }

    static String googleMapsTravelMode(TravelMode travelMode) {
        return switch (travelMode) {
            case DRIVE, RIDE_HAIL -> "driving";
            case TWO_WHEELER -> "two-wheeler";
            case WALK -> "walking";
            case TRANSIT -> "transit";
        };
    }

    private static String mapCoordinate(CalendarLocation location) {
        return Double.toString(location.latitude())
                + "%2C"
                + Double.toString(location.longitude());
    }

    private static void appendMapPoint(
            StringBuilder maps,
            boolean publicEndpoint,
            String label,
            CalendarLocation location) {
        if (!publicEndpoint || !hasValidMapCoordinates(location)) return;
        maps.append("\n📍").append(label)
                .append("\nhttps://www.google.com/maps/search/?api=1&query=")
                .append(mapCoordinate(location));
    }

    private static boolean hasValidMapCoordinates(CalendarLocation location) {
        if (location == null
                || !Double.isFinite(location.latitude())
                || !Double.isFinite(location.longitude())) {
            return false;
        }
        boolean inRange = location.latitude() >= -90
                && location.latitude() <= 90
                && location.longitude() >= -180
                && location.longitude() <= 180;
        return inRange
                && (Double.compare(location.latitude(), 0) != 0
                        || Double.compare(location.longitude(), 0) != 0);
    }

    private static String routeGuidance(
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        if (option.transitLegs().isEmpty()) return "";
        String legs = option.transitLegs().stream()
                .limit(3)
                .map(CalendarIntentDraftConversationService::routeLeg)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("；再"));
        return legs.isBlank() ? "" : "交通方式：" + legs + "。";
    }

    private static String routeLeg(
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .TransitLeg
                    leg) {
        StringBuilder text = new StringBuilder();
        if (leg.departureStop() != null) {
            text.append("從「").append(leg.departureStop()).append("」");
        }
        if (leg.lineName() != null) {
            text.append("搭「").append(leg.lineName()).append("」");
        }
        if (leg.headsign() != null) {
            text.append("往「").append(leg.headsign()).append("」");
        }
        if (leg.arrivalStop() != null) {
            text.append("到「").append(leg.arrivalStop()).append("」");
        }
        return text.toString();
    }

    private static String routeSource(
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        if (option.provider()
                == com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.Provider.TDX) {
            return "TDX 路線資料";
        }
        int year = option.retrievedAt().atZone(ZoneId.of("Asia/Taipei")).getYear();
        return "Google Maps 路線資料；Powered by Google, ©" + year + " Google";
    }

    private String routePlaceLabel(String requested, CalendarLocation fallback) {
        if (systemPlaceCatalog != null && requested != null && !requested.isBlank()) {
            var resolution = systemPlaceCatalog.resolveMention(requested);
            if (resolution.status() == SystemPlaceCatalog.Resolution.Status.EXACT) {
                return systemPlaceCatalog.publicPointLabel(resolution.selected());
            }
        }
        return fallback.label();
    }

    private static com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningRequest.TimeRole
            routeTimeRole(String sourceText) {
        String text = sourceText == null ? "" : sourceText.replaceAll("\\s+", "");
        return containsAny(text, "抵達", "到達", "前到", "以前到", "之前到")
                ? com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TimeRole.ARRIVE_BY
                : com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest.TimeRole.DEPART_AT;
    }

    public Optional<IntentResult> answerTransportPlanning(String text) {
        return answerTransportPlanning(text, () -> {});
    }

    public Optional<IntentResult> answerTransportPlanning(
            String text, Runnable beforeMutation) {
        java.util.Objects.requireNonNull(beforeMutation, "beforeMutation");
        if (routePlaceCreations != null && routePlaceCreations.recognizesCurrentAnswer(text)) {
            beforeMutation.run();
            Optional<RoutePlaceCreationDraftService.Progress> child =
                    routePlaceCreations.answer(text);
            if (child.isPresent()) {
                RoutePlaceCreationDraftService.Progress progress = child.orElseThrow();
                if (progress.action() == RoutePlaceCreationDraftService.Action.ASK_DETAILS) {
                    return Optional.of(routePlaceDetailsQuestion(progress.parent(), progress.child()));
                }
                if (progress.action() == RoutePlaceCreationDraftService.Action.ASK_CONFIRM) {
                    return Optional.of(routePlaceCreationQuestion(progress.parent(), progress.child()));
                }
                if (progress.action() == RoutePlaceCreationDraftService.Action.RETRY_DETAILS) {
                    return Optional.of(routePlaceDetailsRetryQuestion(
                            progress.parent(), progress.child()));
                }
                if (progress.action() == RoutePlaceCreationDraftService.Action.CANCELED) {
                    IntentResult resumed = unconfirmedExplicitOriginQuestion(progress.parent());
                    return Optional.of(prependMessage(
                            resumed,
                            "已取消建立地點「%s」；原本的路線規劃仍保留。"
                                    .formatted(progress.child().getRequestedAlias())));
                }
                String notice = progress.saved()
                        ? "已將「%1$s」儲存為「%2$s」個人地點紀錄，並用作這次出發地。\n\n"
                                .formatted(
                                        progress.child().getCandidateName(),
                                        progress.child().getRequestedAlias())
                        : "這次只使用「%s」作為出發地，沒有儲存自訂地點。\n\n"
                                .formatted(progress.child().getCandidateName());
                return Optional.of(continueStandaloneRoute(progress.parent(), notice));
            }
        }
        var pendingRouteQuestion = currentRouteQuestion();
        String pendingQuestionCode = pendingRouteQuestion
                .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                        .ConversationPendingQuestion::getQuestionCode)
                .orElse(null);
        if (DepartureReminderTurnPolicy.asksForCurrentDetails(text)
                && startReminderLifecycle != null) {
            Optional<DraftView> reminderDraft = pendingRouteQuestion.flatMap(
                    question -> drafts.findAvailableForLifecycle(question.getWorkflowId()));
            if (reminderDraft.isEmpty()) {
                reminderDraft = focuses.activeFocus()
                        .filter(focus -> FOCUS_DOMAIN.equals(focus.getRootDomain()))
                        .map(ConversationFocus::getWorkflowId)
                        .filter(java.util.Objects::nonNull)
                        .flatMap(drafts::findAvailableForLifecycle);
            }
            if (reminderDraft.isEmpty()) {
                reminderDraft = drafts.currentTransportConversation();
            }
            Optional<IntentResult> details = reminderDraft.flatMap(this::departureReminderDetails);
            if (details.isPresent()) return details;
        }
        Optional<FocusedDraft> focused = activeDraft();
        Optional<DraftView> current;
        if (pendingRouteQuestion.isPresent()) {
            current = pendingRouteQuestion.flatMap(
                    question -> drafts.findAvailableForLifecycle(question.getWorkflowId()));
        } else if (focused.isPresent()) {
            FocusedDraft target = focused.orElseThrow();
            if (!drafts.isAvailableForFocus(target.id())) return Optional.empty();
            current = Optional.of(drafts.get(target.id()))
                    .filter(draft -> isTransportConversation(draft)
                            || isOriginContinuation(pendingQuestionCode));
        } else {
            current = drafts.currentTransportConversation();
        }
        if (current.isEmpty()) return Optional.empty();
        DraftView draft = current.orElseThrow();
        if ("route.schedule-conflict".equals(pendingQuestionCode)
                || "route.schedule-conflict-keep-only".equals(pendingQuestionCode)
                || "route.direct-overlap".equals(pendingQuestionCode)) {
            String choice = text == null ? "" : text.replaceAll("[\\s，。！？!?]", "");
            String choiceAction = RouteCalendarChoiceCatalog.find(pendingQuestionCode, false)
                    .flatMap(question -> question.resolveAction(text))
                    .orElse(null);
            boolean requestsAdjustment = RouteCalendarChoiceCatalog.ADJUST_SAFE.equals(choiceAction)
                    || RouteCalendarChoiceCatalog.CHANGE_TIME.equals(choiceAction)
                    || containsAny(choice, "往後安排", "安全時間", "調整到安全", "往後調整");
            if (requestsAdjustment) {
                if ("route.schedule-conflict-keep-only".equals(pendingQuestionCode)) {
                    return Optional.of(routeAdjustmentUnavailable(
                            draft,
                            "本次行程後方仍有銜接限制，往後安排無法解決；原安排沒有變更。",
                            "要忽略衝突並保留原安排嗎？",
                            pendingQuestionCode));
                }
                if (draft.status() != CalendarIntentDraftService.Status.MATERIALIZED) {
                    String nextStep = RouteCalendarChoiceCatalog.CHANGE_TIME.equals(choiceAction)
                            ? "請直接回覆新的出發日期與時間，例如「明天 10:30」。"
                            : "要照原安排保留嗎？";
                    return Optional.of(routeAdjustmentUnavailable(
                            draft,
                            "這個安排和既有行程直接重疊，不能只靠往後平移判定安全。",
                            nextStep,
                            pendingQuestionCode));
                }
                if (requiresConnectionBufferPreference(draft)) {
                    beforeMutation.run();
                    answerRouteQuestion(pendingQuestionCode);
                    return Optional.of(connectionBufferQuestion(
                            draft, "route.connection-buffer-adjust"));
                }
                return Optional.of(adjustMaterializedRouteToSafeTime(draft, beforeMutation));
            }
            if (!RouteCalendarChoiceCatalog.KEEP_ORIGINAL.equals(choiceAction)
                    && !containsAny(choice, "忽略", "保留原安排", "照原安排", "仍照", "維持原安排")) {
                return Optional.empty();
            }
            beforeMutation.run();
            answerRouteQuestion(pendingQuestionCode);
            if (draft.status() == CalendarIntentDraftService.Status.MATERIALIZED) {
                IntentResult completed = IntentResult.message(
                                IntentResult.Action.CONTEXT_UPDATED,
                                "好的，已保留本次行程；銜接風險仍存在，請預留更多移動時間。")
                        .withFocusBinding(binding(draft));
                if (requiresConnectionBufferPreference(draft)) {
                    return Optional.of(connectionBufferQuestion(
                            completed, draft, "route.connection-buffer-keep"));
                }
                return Optional.of(withPostCreationOptions(completed, draft));
            }
            var confirmation = drafts.confirmStandaloneRoute(draft.id(), draft.revision());
            IntentResult completed = IntentResult.message(
                            IntentResult.Action.SCHEDULE_CONFIRMED,
                            "已依您的確認建立本次行程；它仍與既有行程時間重疊，請留意不要同時赴約。")
                    .withFocusBinding(binding(confirmation.draft()));
            return Optional.of(withPostCreationOptions(
                    completed, confirmation.draft()));
        }
        if ("route.connection-buffer-adjust".equals(pendingQuestionCode)
                || "route.connection-buffer-keep".equals(pendingQuestionCode)) {
            int[] minutes = generalBufferMinutes(text);
            if (minutes == null || routeOperationPreferences == null) return Optional.empty();
            beforeMutation.run();
            routeOperationPreferences.setGeneral(minutes[0], minutes[1]);
            answerRouteQuestion(pendingQuestionCode);
            if ("route.connection-buffer-adjust".equals(pendingQuestionCode)) {
                return Optional.of(adjustMaterializedRouteToSafeTime(draft, () -> {}));
            }
            IntentResult completed = IntentResult.message(
                            IntentResult.Action.CONTEXT_UPDATED,
                            "已設定前後銜接緩衝，並已保留本次行程；銜接風險仍存在，我不會把它標成安全。")
                    .withFocusBinding(binding(draft));
            return Optional.of(withPostCreationOptions(completed, draft));
        }
        if ("route.departure-reminder".equals(pendingQuestionCode)) {
            String reminderChoice = RouteCalendarChoiceCatalog.departureReminder(
                            "RIDE_HAIL".equals(draft.transportMode()))
                    .resolveAction(text)
                    .orElse(null);
            DepartureReminderTurnPolicy.Answer reminderAnswer =
                    RouteCalendarChoiceCatalog.ACCEPT.equals(reminderChoice)
                            ? DepartureReminderTurnPolicy.answer("好")
                            : RouteCalendarChoiceCatalog.DECLINE.equals(reminderChoice)
                                    ? DepartureReminderTurnPolicy.answer("先不用")
                                    : DepartureReminderTurnPolicy.answer(text);
            if (reminderAnswer.action()
                    == DepartureReminderTurnPolicy.Action.UNRECOGNIZED) {
                return Optional.empty();
            }
            beforeMutation.run();
            answerRouteQuestion("route.departure-reminder");
            if (startReminderLifecycle != null && draft.materializedPlanId() != null) {
                startReminderLifecycle.clearReviewedStartReminders(
                        draft.materializedPlanId(), "start");
            }
            if (reminderAnswer.action() == DepartureReminderTurnPolicy.Action.DECLINE) {
                return Optional.of(IntentResult.message(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "好的，這次不另外設定出發提醒。")
                        .withFocusBinding(binding(draft)));
            }
            if (startReminderLifecycle == null
                    || draft.materializedPlanId() == null
                    || !(draft.placement() instanceof CalendarPlacement.TimedInterval interval)) {
                return Optional.of(IntentResult.message(
                        IntentResult.Action.AI_UNAVAILABLE,
                        "行程已保留，但目前無法安全建立出發提醒。"));
            }
            if (reminderAnswer.action()
                    == DepartureReminderTurnPolicy.Action.EXPLICIT_LEAD) {
                CalendarReminderRuleView created =
                        startReminderLifecycle.createExplicitDepartureReminder(
                                draft.materializedPlanId(),
                                "start",
                                reminderAnswer.leadMinutes());
                return Optional.of(IntentResult.message(
                                IntentResult.Action.CONTEXT_UPDATED,
                                departureReminderSchedule(
                                        "已設定「%s」的出發提醒：".formatted(draft.title()),
                                        List.of(created),
                                        interval.start()))
                        .withFocusBinding(binding(draft)));
            }
            if ("RIDE_HAIL".equals(draft.transportMode())) {
                int waitMinutes = routeOperationPreferences == null
                        ? 0
                        : routeOperationPreferences.current()
                                .map(RouteOperationPreferenceService.View::rideHailWaitMinutes)
                                .orElse(0);
                Instant fireAt = interval.start()
                        .minus(Duration.ofMinutes(waitMinutes))
                        .minus(Duration.ofMinutes(5));
                startReminderLifecycle.createEarlyRideHailReminder(
                        draft.materializedPlanId(), fireAt);
                return Optional.of(IntentResult.message(
                                IntentResult.Action.CONTEXT_UPDATED,
                                "已設定提早叫車提醒：%s提醒您叫車。"
                                        .formatted(fireAt.atZone(ZoneId.of("Asia/Taipei"))
                                                .format(ROUTE_TIME)))
                        .withFocusBinding(binding(draft)));
            }
            List<CalendarReminderRuleView> created =
                    startReminderLifecycle.createAdaptiveDepartureReminderSchedule(
                            draft.materializedPlanId(),
                            "start",
                            Duration.between(interval.start(), interval.end()));
            return Optional.of(IntentResult.message(
                            IntentResult.Action.CONTEXT_UPDATED,
                            departureReminderSchedule(
                                    "已依行程長度設定「%s」的出發提醒，共 %d 個提醒時點："
                                            .formatted(draft.title(), created.size()),
                                    created,
                                    interval.start()))
                    .withFocusBinding(binding(draft)));
        }
        if ("route.home-location".equals(pendingQuestionCode)) {
            beforeMutation.run();
            return Optional.of(answerOriginLocation(text, draft, true));
        }
        if ("route.origin-context".equals(pendingQuestionCode)) {
            beforeMutation.run();
            return Optional.of(answerOriginLocation(text, draft, false));
        }
        String compact = text == null ? "" : text.replaceAll("[\\s，。！？!?]", "");
        if ("route.general-buffer".equals(pendingQuestionCode)) {
            int[] minutes = generalBufferMinutes(text);
            if (minutes == null || routeOperationPreferences == null) return Optional.empty();
            beforeMutation.run();
            routeOperationPreferences.setGeneral(minutes[0], minutes[1]);
            answerRouteQuestion("route.general-buffer");
            return Optional.of(continueStandaloneRoute(draft, originNoticeFor(draft)));
        }
        if ("route.parking-buffer".equals(pendingQuestionCode)) {
            Integer minutes = singleMinutes(text);
            if (minutes == null || routeOperationPreferences == null) return Optional.empty();
            beforeMutation.run();
            routeOperationPreferences.setParking(minutes);
            answerRouteQuestion("route.parking-buffer");
            IntentResult completed = IntentResult.message(
                            IntentResult.Action.CONTEXT_UPDATED,
                            "已設定停車緩衝：%d 分鐘；已建立的行程時間不變。"
                                    .formatted(minutes))
                    .withFocusBinding(binding(draft));
            return Optional.of(withPostCreationOptions(completed, draft));
        }
        if ("route.ride-hail-wait".equals(pendingQuestionCode)) {
            Integer minutes = singleMinutes(text);
            if (minutes == null || routeOperationPreferences == null) return Optional.empty();
            beforeMutation.run();
            routeOperationPreferences.setRideHailWait(minutes);
            answerRouteQuestion("route.ride-hail-wait");
            IntentResult completed = IntentResult.message(
                            IntentResult.Action.CONTEXT_UPDATED,
                            "已設定等車緩衝：%d 分鐘；已建立的行程時間不變。"
                                    .formatted(minutes))
                    .withFocusBinding(binding(draft));
            return Optional.of(withPostCreationOptions(completed, draft));
        }
        if ("route.transport-mode".equals(pendingQuestionCode)
                && draft.status() == CalendarIntentDraftService.Status.PENDING
                && draft.routeProviderStatus()
                        == CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED) {
            var resolved = com.aproject.aidriven.mymobilesecretary.planner.application
                    .TransportModePolicy.resolve(text);
            if (resolved.status()
                    != com.aproject.aidriven.mymobilesecretary.planner.application
                            .TransportModePolicy.Status.RESOLVED) {
                return Optional.empty();
            }
            beforeMutation.run();
            DraftView prepared = drafts.prepareStandaloneRouteRequest(
                    draft.id(),
                    draft.revision(),
                    resolved.mode(),
                    com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningRequest.TimeRole.valueOf(draft.routeTimeRole()));
            answerRouteQuestion("route.transport-mode");
            return Optional.of(continueStandaloneRoute(prepared, ""));
        }
        if (draft.routeProviderStatus()
                        == CalendarIntentDraftService.RouteProviderStatus.RETAINED
                && containsAny(compact, "再查", "重查", "查一次路線", "重新規劃", "再試")) {
            beforeMutation.run();
            return Optional.of(retryStandaloneRoute(draft));
        }
        if (draft.routeProviderStatus()
                == CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE) {
            String providerChoice = RouteCalendarChoiceCatalog.providerUnavailable()
                    .resolveAction(text)
                    .orElse(null);
            if (RouteCalendarChoiceCatalog.DISCARD.equals(providerChoice)
                    || containsAny(compact, "不保留", "不要保留", "放棄", "取消")) {
                beforeMutation.run();
                boolean committedIncomplete = draft.status()
                        == CalendarIntentDraftService.Status.MATERIALIZED;
                DraftView discarded = committedIncomplete
                        ? drafts.closePrematureMaterializedRouteRecovery(
                                draft.id(), draft.revision())
                        : drafts.discard(draft.id(), draft.revision());
                answerProviderQuestion();
                return Optional.of(IntentResult.message(
                                IntentResult.Action.CONTEXT_UPDATED,
                                committedIncomplete
                                        ? "好，已停止繼續補完這筆路線；既有行事曆資料保留，"
                                                + "但不會把它說成已完成的路線。"
                                        : "好，這份待確認安排已放棄，行事曆沒有新增資料。")
                        .withFocusDirective(
                                binding(discarded), ConversationFocusDirective.INVALIDATE_TARGET));
            }
            if (RouteCalendarChoiceCatalog.RETAIN.equals(providerChoice)
                    || containsAny(compact, "保留", "好", "可以", "要")) {
                beforeMutation.run();
                DraftView retained = drafts.retainUnavailableRoute(
                        draft.id(), draft.revision());
                answerProviderQuestion();
                return Optional.of(IntentResult.message(
                                IntentResult.Action.CONTEXT_UPDATED,
                                "好，已保留這份待確認安排；目前尚未建立行程。您之後要重查路線時再告訴我。")
                        .withFocusBinding(binding(retained)));
            }
            return Optional.empty();
        }
        if (draft.transportOfferStatus()
                == CalendarIntentDraftService.TransportOfferStatus.OFFERED) {
            String offerChoice = RouteCalendarChoiceCatalog.transportOffer()
                    .resolveAction(text)
                    .orElse(null);
            if (RouteCalendarChoiceCatalog.DECLINE.equals(offerChoice)
                    || containsAny(compact, "不用", "不要", "先不用", "不需要")) {
                beforeMutation.run();
                drafts.answerTransportOffer(draft.id(), draft.revision(), false);
                return Optional.of(IntentResult.message(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "好，目的活動保留原樣，這次不另外規劃交通。"));
            }
            if (!RouteCalendarChoiceCatalog.ACCEPT.equals(offerChoice)
                    && !containsAny(compact, "要", "好", "可以", "幫我規劃", "需要")) {
                return Optional.empty();
            }
            beforeMutation.run();
            DraftView accepted = drafts.answerTransportOffer(
                    draft.id(), draft.revision(), true);
            return Optional.of(IntentResult.choiceNeeded(
                    "交通規劃已加入這份草稿，目的活動目前維持原時間。",
                    RouteCalendarChoiceCatalog.activityAdjustability())
                    .withFocusBinding(binding(accepted)));
        }
        if (draft.activityAdjustability() != null) {
            var mode = transportMode(compact);
            if (mode == null) return Optional.empty();
            if (routePlanner == null || draft.transportOrigin() == null
                    || draft.location() == null) {
                return Optional.of(IntentResult.message(
                        IntentResult.Action.AI_UNAVAILABLE,
                        "目前沒有足夠的可靠路線資料，目的活動與時間都沒有變更。"));
            }
            Instant arriveBy = activityStart(draft.placement());
            var route = routePlanner.plan(
                    new com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningRequest(
                                    draft.transportOrigin().latitude(),
                                    draft.transportOrigin().longitude(),
                                    draft.location().latitude(), draft.location().longitude(),
                                    mode,
                                    com.aproject.aidriven.mymobilesecretary.planner.application
                                            .RoutePlanningRequest.TimeRole.ARRIVE_BY,
                                    arriveBy, Duration.ZERO));
            if (route.status()
                    != com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.Status.AVAILABLE) {
                return Optional.of(IntentResult.message(
                        IntentResult.Action.AI_UNAVAILABLE,
                        "目前查不到足以採用的路線，目的活動與時間都沒有變更。"));
            }
            var option = route.options().getFirst();
            beforeMutation.run();
            drafts.materializeTransportNode(
                    draft.id(), draft.revision(), mode, option);
            String source = routeSource(option);
            String departure = ZonedDateTime.ofInstant(
                    option.departAt(), ZoneId.of("Asia/Taipei")).format(DATE_TIME);
            long minutes = option.duration().toMinutes();
            return Optional.of(IntentResult.message(
                    IntentResult.Action.CONTEXT_UPDATED,
                    "已把交通出發時間加入同一個行程詳情：%s 出發，預估約 %d 分鐘（%s）。"
                            .formatted(departure, minutes, source)
                            + "目的活動仍是原本那一筆，活動時間沒有修改。"));
        }
        com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability adjustability;
        String adjustabilityChoice = RouteCalendarChoiceCatalog.activityAdjustability()
                .resolveAction(text)
                .orElse(null);
        if (RouteCalendarChoiceCatalog.LOCKED.equals(adjustabilityChoice)
                || containsAny(compact, "固定", "不能改", "外部決定", "主辦決定")) {
            adjustability = com.aproject.aidriven.mymobilesecretary.calendar.domain
                    .Adjustability.LOCKED;
        } else if (RouteCalendarChoiceCatalog.WINDOWED.equals(adjustabilityChoice)
                || containsAny(compact, "時間範圍", "時段內", "前後都可以")) {
            adjustability = com.aproject.aidriven.mymobilesecretary.calendar.domain
                    .Adjustability.WINDOWED;
        } else if (RouteCalendarChoiceCatalog.FLEXIBLE.equals(adjustabilityChoice)
                || containsAny(compact, "可以調整", "可以配合", "跟著調整", "彈性")) {
            adjustability = com.aproject.aidriven.mymobilesecretary.calendar.domain
                    .Adjustability.FLEXIBLE;
        } else {
            return Optional.empty();
        }
        beforeMutation.run();
        DraftView changed = drafts.setActivityAdjustability(
                draft.id(), draft.revision(), adjustability);
        String policy = adjustability
                == com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability.LOCKED
                ? "已將活動時間設為固定錨點，交通變動不得修改活動。"
                : "已將活動設為可配合交通調整；每次改動活動時間前仍需取得您的確認。";
        return Optional.of(IntentResult.clarificationNeeded(
                        policy,
                        com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep
                                .blocking("route.transport-mode", "route.mode",
                                        "這趟想優先用哪種交通方式？", 40))
                .withFocusBinding(binding(changed)));
    }

    private IntentResult adjustMaterializedRouteToSafeTime(
            DraftView draft, Runnable beforeMutation) {
        if (routePlanner == null || routeProjection == null || routeAssessments == null
                || draft.materializedPlanId() == null
                || draft.transportOrigin() == null
                || draft.location() == null
                || !(draft.placement() instanceof CalendarPlacement.TimedInterval interval)) {
            return routeAdjustmentUnavailable(
                    draft,
                    "目前缺少可重新驗證的路線資料，原安排沒有變更。",
                    "要忽略衝突並保留原安排嗎？");
        }
        List<PersonalRouteConstraint> current = routeProjection.current().routeConstraints();
        List<PersonalRouteAssessment> relevant = routeAssessments
                .assessAdjacentExcludingInternalPlan(current, draft.materializedPlanId())
                .stream()
                .filter(assessment -> involvesPlan(assessment, draft.materializedPlanId()))
                .filter(PersonalRouteAssessment::isRouteRisk)
                .toList();
        if (relevant.isEmpty()) {
            return routeAdjustmentUnavailable(
                    draft,
                    "目前的銜接風險已變更，原安排沒有再次移動。",
                    "要保留目前時間嗎？");
        }
        if (relevant.stream().anyMatch(assessment ->
                assessment.from().planId().equals(draft.materializedPlanId()))) {
            return routeAdjustmentUnavailable(
                    draft,
                    "本次行程後方也有銜接限制，往後移動不能安全解決。",
                    "要忽略衝突並保留原安排嗎？");
        }
        Duration shift = relevant.stream()
                .map(assessment -> assessment.requiredTravel()
                        .minus(assessment.availableGap()))
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        if (shift.isZero() || shift.isNegative() || shift.compareTo(Duration.ofHours(6)) > 0) {
            return routeAdjustmentUnavailable(
                    draft,
                    "目前無法算出可信的安全時間，原安排沒有變更。",
                    "要忽略衝突並保留原安排嗎？");
        }

        var mode = com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningRequest.TravelMode.valueOf(draft.transportMode());
        var timeRole = com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningRequest.TimeRole.valueOf(draft.routeTimeRole());
        RouteOperationPreferenceService.View preferences = routeOperationPreferences == null
                ? null
                : routeOperationPreferences.current().orElse(null);
        shift = boundedConnectionShift(shift, preferences);
        if (shift == null) {
            return routeAdjustmentUnavailable(
                    draft,
                    "加上銜接緩衝後，無法在六小時內算出可信的安全時間；原安排沒有變更。",
                    "要忽略衝突並保留原安排嗎？");
        }
        int wait = preferences != null
                        && mode == com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.RIDE_HAIL
                        && preferences.rideHailWaitMinutes() != null
                ? preferences.rideHailWaitMinutes()
                : 0;
        int parking = preferences != null
                        && mode == com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TravelMode.DRIVE
                        && preferences.parkingMinutes() != null
                ? preferences.parkingMinutes()
                : 0;
        Instant requestedTime = timeRole
                        == com.aproject.aidriven.mymobilesecretary.planner.application
                                .RoutePlanningRequest.TimeRole.DEPART_AT
                ? interval.start().plus(Duration.ofMinutes(wait)).plus(shift)
                : interval.end().minus(Duration.ofMinutes(parking)).plus(shift);
        CalendarStartReminderLifecycleService.Decision reminderDecision =
                startReminderLifecycle == null
                        ? null
                        : startReminderLifecycle.beforeStartTimeAdjusted(
                                draft.materializedPlanId(),
                                "start",
                                CalendarStartReminderLifecycleService.PublicKind.DEPARTURE);
        beforeMutation.run();
        var result = routePlanner.plan(
                new com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest(
                                draft.transportOrigin().latitude(),
                                draft.transportOrigin().longitude(),
                                draft.location().latitude(),
                                draft.location().longitude(),
                                mode,
                                timeRole,
                                requestedTime,
                                Duration.ZERO));
        if (result.status()
                != com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.Status.AVAILABLE) {
            return routeAdjustmentUnavailable(
                    draft,
                    "重新查詢安全時間時暫時沒有可靠路線資料，原安排沒有變更。",
                    "要忽略衝突並保留原安排嗎？");
        }
        var option = result.options().getFirst();
        if (!safeAfterAdjustment(draft, current, option)) {
            return routeAdjustmentUnavailable(
                    draft,
                    "重新驗證後仍有銜接風險，原安排沒有變更。",
                    "要忽略衝突並保留原安排嗎？");
        }

        DraftView adjusted = drafts.rescheduleMaterializedStandaloneRoute(
                draft.id(), draft.revision(), option);
        answerRouteQuestion("route.schedule-conflict");
        String details = standaloneRouteDetails(null, null, adjusted, option);
        IntentResult completed = IntentResult.message(
                        IntentResult.Action.SCHEDULE_CONFIRMED,
                        "好的，已把本次行程往後調整到安全時間✅：\n\n"
                                + details
                                + "\n\n目前與相鄰行程的銜接已確認。")
                .withFocusBinding(binding(adjusted));
        return withPostCreationOptions(completed, adjusted, reminderDecision);
    }

    private boolean safeAfterAdjustment(
            DraftView draft,
            List<PersonalRouteConstraint> current,
            com.aproject.aidriven.mymobilesecretary.planner.application
                            .RoutePlanningResult.RouteOption
                    option) {
        List<PersonalRouteConstraint> routeNodes = current.stream()
                .filter(constraint -> constraint.planId().equals(draft.materializedPlanId()))
                .toList();
        if (routeNodes.size() != 2
                || routeNodes.stream().anyMatch(
                        node -> !"start".equals(node.nodeKey()) && !"end".equals(node.nodeKey()))) {
            return false;
        }
        var hypothetical = new java.util.ArrayList<PersonalRouteConstraint>();
        current.stream()
                .filter(constraint -> !constraint.planId().equals(draft.materializedPlanId()))
                .forEach(hypothetical::add);
        for (PersonalRouteConstraint node : routeNodes) {
            Instant time = "start".equals(node.nodeKey()) ? option.departAt() : option.arriveAt();
            hypothetical.add(new PersonalRouteConstraint(
                    node.planId(),
                    node.nodeId(),
                    node.sourceCreatedByUserId(),
                    node.planTitle(),
                    node.nodeKey(),
                    time,
                    node.location(),
                    node.adjustability(),
                    node.nodeRevision() + 1));
        }
        return routeAssessments
                .assessAdjacentExcludingInternalPlan(hypothetical, draft.materializedPlanId())
                .stream()
                .filter(assessment -> involvesPlan(assessment, draft.materializedPlanId()))
                .noneMatch(assessment -> assessment.isRouteRisk()
                        || assessment.status() == PersonalRouteStatus.INSUFFICIENT_EVIDENCE);
    }

    private static boolean involvesPlan(
            PersonalRouteAssessment assessment, java.util.UUID planId) {
        return assessment.from() != null
                && assessment.to() != null
                && (assessment.from().planId().equals(planId)
                        || assessment.to().planId().equals(planId));
    }

    private IntentResult routeAdjustmentUnavailable(
            DraftView draft, String explanation, String prompt) {
        return routeAdjustmentUnavailable(
                draft, explanation, prompt, "route.schedule-conflict");
    }

    private IntentResult routeAdjustmentUnavailable(
            DraftView draft, String explanation, String prompt, String questionCode) {
        Optional<com.aproject.aidriven.mymobilesecretary.intent.application
                        .PublicConversationChoiceQuestion>
                choice = RouteCalendarChoiceCatalog.find(questionCode, false);
        if (choice.isPresent() && !prompt.startsWith("請直接回覆")) {
            return IntentResult.choiceNeeded(explanation, choice.orElseThrow())
                    .withFocusBinding(binding(draft));
        }
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(questionCode, prompt);
        IntentResult result = IntentResult.message(
                IntentResult.Action.CLARIFICATION_NEEDED, explanation);
        return new IntentResult(
                result.action(),
                result.message() + "\n\n" + question.prompt(),
                result.task(),
                result.decision(),
                result.focusNotice(),
                binding(draft),
                result.focusDirective(),
                question);
    }

    private static boolean isTransportConversation(DraftView draft) {
        return (draft.status() == CalendarIntentDraftService.Status.PENDING
                        && draft.routeProviderStatus()
                                == CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED
                        && draft.routeTimeRole() != null)
                || draft.routeProviderStatus()
                        == CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE
                || draft.routeProviderStatus()
                        == CalendarIntentDraftService.RouteProviderStatus.RETAINED
                || draft.transportOfferStatus()
                        == CalendarIntentDraftService.TransportOfferStatus.OFFERED
                || draft.transportOfferStatus()
                        == CalendarIntentDraftService.TransportOfferStatus.ACCEPTED;
    }

    private static boolean isOriginContinuation(String questionCode) {
        return "route.home-location".equals(questionCode)
                || "route.origin-context".equals(questionCode)
                || "route.transport-mode".equals(questionCode)
                || "route.general-buffer".equals(questionCode)
                || "route.connection-buffer-adjust".equals(questionCode)
                || "route.connection-buffer-keep".equals(questionCode)
                || "route.parking-buffer".equals(questionCode)
                || "route.ride-hail-wait".equals(questionCode);
    }

    private Optional<com.aproject.aidriven.mymobilesecretary.conversation.domain
                    .ConversationPendingQuestion>
            currentRouteQuestion() {
        if (pendingQuestions == null) return Optional.empty();
        return pendingQuestions.current()
                .filter(question -> question.getQuestionCode().startsWith("route."));
    }

    private IntentResult answerOriginLocation(String text, DraftView draft, boolean saveAsHome) {
        if (placeAliases == null) {
            return originQuestion(draft, saveAsHome);
        }
        Optional<com.aproject.aidriven.mymobilesecretary.geo.domain.Place> resolved =
                placeAliases.resolveMention(text)
                        .or(() -> placeAliases.resolve(text == null ? null : text.strip()));
        if (resolved.isEmpty()) {
            if (!saveAsHome && routePlaceCreations != null) {
                Optional<com.aproject.aidriven.mymobilesecretary.conversation.domain
                                .RoutePlaceCreationDraft>
                        child;
                try {
                    child = routePlaceCreations.startFromText(draft, text);
                } catch (com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException
                        exception) {
                    return prependMessage(
                            originQuestion(draft, false),
                            "目前無法安全確認這個地點，因此尚未建立地點或行程。"
                                    + "請改提供另一個已確認地點，或稍後再試。");
                }
                if (child.isPresent()) {
                    var value = child.orElseThrow();
                    return value.getStep()
                                    == com.aproject.aidriven.mymobilesecretary.conversation.domain
                                            .RoutePlaceCreationStep.CONFIRM
                            ? routePlaceCreationQuestion(draft, value)
                            : routePlaceDetailsRetryQuestion(draft, value);
                }
                Optional<RoutePlaceCreationDraftService.Progress> origin =
                        routePlaceCreations.startOrigin(draft, text, text);
                if (origin.isPresent()) {
                    return routePlaceOriginStart(origin.orElseThrow());
                }
            }
            return originQuestion(draft, saveAsHome);
        }
        var place = resolved.orElseThrow();
        if (saveAsHome) {
            if (actorLocationPreferences == null) return originQuestion(draft, true);
            actorLocationPreferences.setHome(place.getId());
        }
        DraftView withOrigin = drafts.setTransportOrigin(
                draft.id(),
                draft.revision(),
                new com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation(
                        place.getName(), place.getLatitude(), place.getLongitude()),
                saveAsHome
                        ? CalendarIntentDraftService.EndpointSource.CONFIRMED_HOME
                        : CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN);
        answerRouteQuestion(saveAsHome ? "route.home-location" : "route.origin-context");
        String notice = saveAsHome ? "這次先以家裡為出發地。\n\n" : "";
        if (withOrigin.routeJourneyKind() == RouteJourneyKind.ACTIVITY_WITH_TRANSPORT) {
            return completeActivityWithTransport(withOrigin, notice);
        }
        return continueStandaloneRoute(withOrigin, notice);
    }

    private IntentResult originQuestion(DraftView draft, boolean home) {
        return IntentResult.clarificationNeeded(
                        com.aproject.aidriven.mymobilesecretary.intent.application
                                .ClarificationStep.blocking(
                                        home ? "route.home-location" : "route.origin-context",
                                        "route.origin",
                                        home
                                                ? "我還沒有您的住家地點。要把哪個地點設為家裡？"
                                                : "依前後行程看，這次可能不是從家裡出發。這趟要從哪裡出發？",
                                        10))
                .withFocusBinding(binding(draft));
    }

    private IntentResult unconfirmedExplicitOriginQuestion(DraftView draft) {
        return IntentResult.clarificationNeeded(
                        com.aproject.aidriven.mymobilesecretary.intent.application
                                .ClarificationStep.blocking(
                                        "route.origin-context",
                                        "route.origin",
                                        "我已記下出發時間，但還不能確認出發地點。這趟要從哪個已確認地點出發？",
                                        10))
                .withFocusBinding(binding(draft));
    }

    private IntentResult routePlaceOfferQuestion(
            DraftView parent,
            com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft child) {
        String message = """
                目前正在處理：
                🏷️ %s

                目前子步驟：
                📍 確認出發地點「%s」

                我找不到已儲存的地點「%s」。
                原本的出發時間、目的地與路線需求都已保留，行程尚未建立。

                您可以：
                1. 回覆「建立地點」，接著提供地址或 Google Maps 連結
                2. 直接貼上地點名稱、地址或 Google Maps 連結
                3. 回覆另一個已儲存的地點
                4. 回覆「取消」返回路線規劃
                5. 若要改做其他事情，回覆「保留，開始新的操作」
                """.formatted(
                        parent.title(), child.getRequestedAlias(), child.getRequestedAlias());
        return IntentResult.clarificationNeeded(
                message,
                com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep.blocking(
                        RoutePlaceCreationDraftService.OFFER_QUESTION_CODE,
                        "place.route-create-offer",
                        "這次要怎麼處理「%s」？".formatted(child.getRequestedAlias()),
                        80));
    }

    private IntentResult routePlaceDetailsQuestion(
            DraftView parent,
            com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft child) {
        if ("目前位置".equals(child.getRequestedAlias())) {
            return IntentResult.clarificationNeeded(
                    "原本的出發時間、目的地與路線需求都已保留；現在只確認這次的暫時出發位置。",
                    com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep.blocking(
                            RoutePlaceCreationDraftService.DETAILS_QUESTION_CODE,
                            "place.route-create-details",
                            "請貼上目前位置的 Google Maps 連結。這個位置只供本次路線使用，不會儲存為自訂地點。",
                            20));
        }
        return IntentResult.clarificationNeeded(
                "原本的路線規劃仍保留；現在建立出發地點「%s」。"
                        .formatted(child.getRequestedAlias()),
                com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep.blocking(
                        RoutePlaceCreationDraftService.DETAILS_QUESTION_CODE,
                        "place.route-create-details",
                        "請提供「%s」的地點名稱、完整地址或 Google Maps 連結，例如「內湖富邦大樓」。若要改做其他事情，請說「保留，開始新的操作」。"
                                .formatted(child.getRequestedAlias()),
                        80));
    }

    private IntentResult routePlaceOriginStart(RoutePlaceCreationDraftService.Progress progress) {
        return switch (progress.action()) {
            case ASK_OFFER -> routePlaceOfferQuestion(progress.parent(), progress.child());
            case ASK_DETAILS -> routePlaceDetailsQuestion(progress.parent(), progress.child());
            case RETRY_DETAILS -> routePlaceDetailsRetryQuestion(progress.parent(), progress.child());
            case COMPLETED -> continueStandaloneRoute(
                    progress.parent(),
                    "這次只使用「%s」作為出發地，不會儲存為自訂地點。\n\n"
                            .formatted(progress.child().getCandidateName()));
            case ASK_CONFIRM -> routePlaceCreationQuestion(progress.parent(), progress.child());
            case CANCELED -> unconfirmedExplicitOriginQuestion(progress.parent());
        };
    }

    private IntentResult routePlaceDetailsRetryQuestion(
            DraftView parent,
            com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft child) {
        return prependMessage(
                routePlaceDetailsQuestion(parent, child),
                "目前無法安全確認您提供的位置，因此尚未建立地點或行程。請換一個更完整的地點名稱、地址或 Google Maps 連結。");
    }

    private IntentResult routePlaceCreationQuestion(
            DraftView parent,
            com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft child) {
        String preservedTime = parent.placement() instanceof CalendarPlacement.TimedPoint point
                ? point.time().atZone(ZoneId.of("Asia/Taipei")).format(ROUTE_TIME) + "出發"
                : parent.placement() instanceof CalendarPlacement.TimedInterval interval
                        ? interval.start().atZone(ZoneId.of("Asia/Taipei")).format(ROUTE_TIME) + "出發"
                        : "已記下原本的時間";
        String message = """
                目前正在處理：
                🏷️ %s

                目前子步驟：
                📍 建立出發地點「%s」

                已保留：
                - %s
                - 目的地與原本路線需求
                - 路線尚未查詢，行程尚未建立

                已找到「%s」。
                """.formatted(
                        parent.title(),
                        child.getRequestedAlias(),
                        preservedTime,
                        child.getCandidateName());
        return IntentResult.choiceNeeded(
                message, RoutePlaceCreationChoiceCatalog.usePlace());
    }

    private static IntentResult prependMessage(IntentResult result, String prefix) {
        return new IntentResult(
                result.action(),
                prefix + "\n\n" + result.message(),
                result.task(),
                result.decision(),
                result.focusNotice(),
                result.focusBinding(),
                result.focusDirective(),
                result.nextQuestion());
    }

    private IntentResult continueStandaloneRoute(DraftView draft, String originNotice) {
        if (draft.location() == null) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.endpoint",
                                            "route.endpoint",
                                            "我還不能確定這趟行程的起點或目的地。您要補充哪一個地點？",
                                            10))
                    .withFocusBinding(binding(draft));
        }
        if (draft.transportOrigin() == null) {
            return originQuestion(draft, false);
        }
        if (draft.transportMode() == null) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.transport-mode",
                                            "route.mode",
                                            "這趟要用大眾運輸、開車、機車，還是步行？",
                                            10))
                    .withFocusBinding(binding(draft));
        }
        if (draft.routeTimeRole() == null) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.time",
                                            "route.time",
                                            "這趟預計幾點出發？",
                                            10))
                    .withFocusBinding(binding(draft));
        }
        var mode = com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningRequest.TravelMode.valueOf(draft.transportMode());
        var timeRole = com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningRequest.TimeRole.valueOf(draft.routeTimeRole());
        if (routePlanner == null) {
            return withOriginNotice(providerUnavailable(draft, mode, timeRole), originNotice);
        }
        Instant requestedTime = routeRequestedTime(draft.placement());
        if (requestedTime == null) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.time",
                                            "route.time",
                                            "這趟預計幾點出發？",
                                            10))
                    .withFocusBinding(binding(draft));
        }
        var route = routePlanner.plan(
                new com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest(
                                draft.transportOrigin().latitude(),
                                draft.transportOrigin().longitude(),
                                draft.location().latitude(),
                                draft.location().longitude(),
                                mode,
                                timeRole,
                                requestedTime,
                                Duration.ZERO));
        if (route.status()
                != com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.Status.AVAILABLE) {
            return withOriginNotice(providerUnavailable(draft, mode, timeRole), originNotice);
        }
        var option = route.options().getFirst();
        CalendarIntentDraftService.ConfirmationResult confirmation;
        if (draft.status() == CalendarIntentDraftService.Status.MATERIALIZED
                && draft.routeProviderStatus()
                        == CalendarIntentDraftService.RouteProviderStatus.NOT_REQUESTED) {
            confirmation = drafts.completePrematureMaterializedStandaloneRoute(
                    draft.id(), draft.revision(), mode, timeRole, option);
        } else {
            DraftView prepared = drafts.prepareStandaloneRoute(
                    draft.id(), draft.revision(), mode, timeRole, option);
            confirmation = drafts.confirmStandaloneRoute(
                    prepared.id(), prepared.revision());
        }
        if (confirmation.awaitingRouteConfirmation()) {
            String preview = originNotice + "我先依路線資料規劃："
                    + standaloneRouteDetails(null, null, confirmation.draft(), option);
            return IntentResult.choiceNeeded(
                            preview, preflightResponses.choiceQuestion(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft()));
        }
        return withOriginNotice(
                standaloneRouteResult(null, null, confirmation, option), originNotice);
    }

    private IntentResult retryStandaloneRoute(DraftView draft) {
        var mode = com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningRequest.TravelMode.valueOf(draft.transportMode());
        var timeRole = com.aproject.aidriven.mymobilesecretary.planner.application
                .RoutePlanningRequest.TimeRole.valueOf(draft.routeTimeRole());
        if (routePlanner == null) return providerUnavailable(draft, mode, timeRole);
        Instant requestedTime = switch (draft.placement()) {
            case CalendarPlacement.TimedPoint point -> point.time();
            case CalendarPlacement.TimedInterval interval -> interval.start();
            case CalendarPlacement.AllDay ignored -> null;
        };
        if (requestedTime == null || draft.transportOrigin() == null || draft.location() == null) {
            return IntentResult.clarificationNeeded(
                            com.aproject.aidriven.mymobilesecretary.intent.application
                                    .ClarificationStep.blocking(
                                            "route.time", "route.time",
                                            "這趟預計幾點出發？", 10))
                    .withFocusBinding(binding(draft));
        }
        var route = routePlanner.plan(
                new com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningRequest(
                                draft.transportOrigin().latitude(),
                                draft.transportOrigin().longitude(),
                                draft.location().latitude(),
                                draft.location().longitude(),
                                mode,
                                timeRole,
                                requestedTime,
                                Duration.ZERO));
        if (route.status()
                != com.aproject.aidriven.mymobilesecretary.planner.application
                        .RoutePlanningResult.Status.AVAILABLE) {
            return providerUnavailable(draft, mode, timeRole);
        }
        var option = route.options().getFirst();
        CalendarIntentDraftService.ConfirmationResult confirmation;
        if (draft.status() == CalendarIntentDraftService.Status.MATERIALIZED) {
            confirmation = drafts.completePrematureMaterializedStandaloneRoute(
                    draft.id(), draft.revision(), mode, timeRole, option);
        } else {
            DraftView prepared = drafts.prepareStandaloneRoute(
                    draft.id(), draft.revision(), mode, timeRole, option);
            confirmation = drafts.confirmStandaloneRoute(
                    prepared.id(), prepared.revision());
        }
        if (confirmation.awaitingRouteConfirmation()) {
            String preview = "我先依路線資料規劃："
                    + standaloneRouteDetails(null, null, confirmation.draft(), option);
            String details = preflightResponses.details(confirmation.preflight());
            return IntentResult.choiceNeeded(
                            preview + "\n\n" + details,
                            preflightResponses.choiceQuestion(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft()));
        }
        String details = standaloneRouteDetails(null, null, confirmation.draft(), option);
        return standaloneRouteResult(null, null, confirmation, option);
    }

    private void answerProviderQuestion() {
        if (pendingQuestions != null) {
            pendingQuestions.answerCurrent("route.provider-unavailable");
        }
    }

    private void answerRouteQuestion(String questionCode) {
        if (pendingQuestions != null) {
            pendingQuestions.answerCurrent(questionCode);
        }
    }

    private IntentResult postCreationOperationQuestion(
            IntentResult completed, DraftView draft) {
        if (routeOperationPreferences == null) return null;
        Optional<RouteOperationPreferenceService.View> current = routeOperationPreferences.current();
        RouteOperationPreferenceService.View preference = current.orElse(null);
        String questionCode = null;
        String prompt = null;
        if ("DRIVE".equals(draft.transportMode())
                && (preference == null || preference.parkingMinutes() == null)) {
            questionCode = "route.parking-buffer";
            prompt = "開車抵達附近後，通常要預留幾分鐘停車？";
        } else if ("RIDE_HAIL".equals(draft.transportMode())
                && (preference == null || preference.rideHailWaitMinutes() == null)) {
            questionCode = "route.ride-hail-wait";
            prompt = "搭計程車出發前，通常要預留幾分鐘等車？";
        }
        if (questionCode == null) return null;
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(questionCode, prompt);
        return new IntentResult(
                completed.action(),
                completed.message() + "\n\n" + question.prompt(),
                completed.task(),
                completed.decision(),
                completed.focusNotice(),
                binding(draft),
                completed.focusDirective(),
                question);
    }

    static int[] generalBufferMinutes(String text) {
        if (text == null) return null;
        var equalMatcher = java.util.regex.Pattern
                .compile("前後(?:各|都)?[^\\d]{0,8}(\\d{1,3})")
                .matcher(text);
        if (equalMatcher.find()) {
            int minutes = Integer.parseInt(equalMatcher.group(1));
            return minutes <= 240 ? new int[] {minutes, minutes} : null;
        }
        var matcher = java.util.regex.Pattern
                .compile("前[^\\d]{0,8}(\\d{1,3}).*後[^\\d]{0,8}(\\d{1,3})")
                .matcher(text);
        if (!matcher.find()) return null;
        int before = Integer.parseInt(matcher.group(1));
        int after = Integer.parseInt(matcher.group(2));
        return before <= 240 && after <= 240 ? new int[] {before, after} : null;
    }

    private boolean requiresConnectionBufferPreference(DraftView draft) {
        return draft.routeJourneyKind() == RouteJourneyKind.STANDALONE_TRIP
                && routeOperationPreferences != null
                && routeOperationPreferences.current()
                        .filter(RouteOperationPreferenceService.View::hasGeneral)
                        .isEmpty();
    }

    static Duration boundedConnectionShift(
            Duration riskShift, RouteOperationPreferenceService.View preferences) {
        if (riskShift == null || riskShift.isNegative() || riskShift.isZero()) return null;
        Duration result = preferences != null && preferences.hasGeneral()
                ? riskShift.plus(Duration.ofMinutes(preferences.generalBeforeMinutes()))
                : riskShift;
        return result.compareTo(Duration.ofHours(6)) <= 0 ? result : null;
    }

    private IntentResult connectionBufferQuestion(DraftView draft, String questionCode) {
        return connectionBufferQuestion(
                IntentResult.message(
                                IntentResult.Action.CLARIFICATION_NEEDED,
                                "這兩個行程需要保留銜接緩衝，確認後我才會調整時間。")
                        .withFocusBinding(binding(draft)),
                draft,
                questionCode);
    }

    private IntentResult connectionBufferQuestion(
            IntentResult result, DraftView draft, String questionCode) {
        var question = new com.aproject.aidriven.mymobilesecretary.intent.application
                .PublicConversationReply.NextQuestion(
                questionCode,
                "前後各要保留幾分鐘的銜接緩衝？例如：前10分鐘、後15分鐘。");
        return new IntentResult(
                result.action(),
                result.message() + "\n\n" + question.prompt(),
                result.task(),
                result.decision(),
                result.focusNotice(),
                binding(draft),
                result.focusDirective(),
                question);
    }

    private static Integer singleMinutes(String text) {
        if (text == null) return null;
        var matcher = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(text);
        if (!matcher.find()) return null;
        int minutes = Integer.parseInt(matcher.group(1));
        return minutes <= 240 ? minutes : null;
    }

    private static String departureReminderSchedule(
            String header,
            List<CalendarReminderRuleView> reminders,
            Instant departureAt) {
        StringBuilder result = new StringBuilder(header);
        for (int index = 0; index < reminders.size(); index++) {
            CalendarReminderRuleView reminder = reminders.get(index);
            long leadMinutes = Duration.between(
                            reminder.firstScheduledAt(), departureAt)
                    .toMinutes();
            String timing = leadMinutes == 0
                    ? "出發時間"
                    : "出發前 %d 分鐘".formatted(leadMinutes);
            result.append("\n\n")
                    .append(index + 1)
                    .append(". ")
                    .append(reminder.firstScheduledAt()
                            .atZone(ZoneId.of("Asia/Taipei"))
                            .format(DATE_TIME))
                    .append("（")
                    .append(timing)
                    .append("）");
        }
        return result.toString();
    }

    private Optional<IntentResult> departureReminderDetails(DraftView draft) {
        if (draft.materializedPlanId() == null
                || !(draft.placement() instanceof CalendarPlacement.TimedInterval interval)) {
            return Optional.empty();
        }
        List<CalendarReminderRuleView> active = startReminderLifecycle
                .activeDepartureReminders(draft.materializedPlanId(), "start");
        if (active.isEmpty()) return Optional.empty();
        return Optional.of(IntentResult.message(
                IntentResult.Action.SCHEDULE_REMINDER_INFO,
                departureReminderSchedule(
                        "「%s」目前有 %d 個出發提醒："
                                .formatted(draft.title(), active.size()),
                        active,
                        interval.start())));
    }

    private RouteOperationWindow routeOperationWindow(
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        RouteOperationPreferenceService.View preference = routeOperationPreferences == null
                ? null
                : routeOperationPreferences.current().orElse(null);
        int before = 0;
        int after = 0;
        int parking = preference != null
                        && option.mode()
                                == com.aproject.aidriven.mymobilesecretary.planner.application
                                        .RoutePlanningRequest.TravelMode.DRIVE
                        && preference.parkingMinutes() != null
                ? preference.parkingMinutes()
                : 0;
        int wait = preference != null
                        && option.mode()
                                == com.aproject.aidriven.mymobilesecretary.planner.application
                                        .RoutePlanningRequest.TravelMode.RIDE_HAIL
                        && preference.rideHailWaitMinutes() != null
                ? preference.rideHailWaitMinutes()
                : 0;
        return new RouteOperationWindow(
                option.departAt().minus(Duration.ofMinutes((long) before + wait)),
                option.arriveAt().plus(Duration.ofMinutes((long) parking + after)),
                before,
                after,
                parking,
                wait);
    }

    private static String operationLines(
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option,
            RouteOperationWindow window) {
        ZoneId zone = ZoneId.of("Asia/Taipei");
        StringBuilder lines = new StringBuilder();
        if (window.generalBeforeMinutes() > 0) {
            lines.append("\n")
                    .append(window.start().atZone(zone).format(ROUTE_TIME))
                    .append(" 🧩 出發前緩衝開始（")
                    .append(window.generalBeforeMinutes())
                    .append("分鐘）");
        }
        if (window.rideHailWaitMinutes() > 0) {
            lines.append("\n")
                    .append(option.departAt()
                            .minus(Duration.ofMinutes(window.rideHailWaitMinutes()))
                            .atZone(zone)
                            .format(ROUTE_TIME))
                    .append(" 🚕 等車緩衝開始（")
                    .append(window.rideHailWaitMinutes())
                    .append("分鐘）");
        }
        Instant afterArrival = option.arriveAt();
        if (window.parkingMinutes() > 0) {
            afterArrival = afterArrival.plus(Duration.ofMinutes(window.parkingMinutes()));
            lines.append("\n")
                    .append(afterArrival.atZone(zone).format(ROUTE_TIME))
                    .append(" 🚗 停車作業完成（")
                    .append(window.parkingMinutes())
                    .append("分鐘）");
        }
        if (window.generalAfterMinutes() > 0) {
            lines.append("\n")
                    .append(window.end().atZone(zone).format(ROUTE_TIME))
                    .append(" 🧩 行程後緩衝結束（")
                    .append(window.generalAfterMinutes())
                    .append("分鐘）");
        }
        return lines.toString();
    }

    private record RouteOperationWindow(
            Instant start,
            Instant end,
            int generalBeforeMinutes,
            int generalAfterMinutes,
            int parkingMinutes,
            int rideHailWaitMinutes) {}

    private String originNoticeFor(DraftView draft) {
        if (actorLocationPreferences == null || draft.transportOrigin() == null) return "";
        return actorLocationPreferences.home()
                .filter(home -> Double.compare(
                                        home.latitude(), draft.transportOrigin().latitude())
                                == 0
                        && Double.compare(
                                        home.longitude(), draft.transportOrigin().longitude())
                                == 0)
                .map(ignored -> "這次先以家裡為出發地。\n\n")
                .orElse("");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningRequest.TravelMode
            transportMode(String text) {
        if (containsAny(text, "大眾運輸", "捷運", "公車", "火車", "高鐵", "搭車")) {
            return com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningRequest.TravelMode.TRANSIT;
        }
        if (containsAny(text, "開車", "汽車")) {
            return com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningRequest.TravelMode.DRIVE;
        }
        if (containsAny(text, "機車", "騎車")) {
            return com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningRequest.TravelMode.TWO_WHEELER;
        }
        if (containsAny(text, "走路", "步行")) {
            return com.aproject.aidriven.mymobilesecretary.planner.application
                    .RoutePlanningRequest.TravelMode.WALK;
        }
        return null;
    }

    private static Instant activityStart(CalendarPlacement placement) {
        if (placement instanceof CalendarPlacement.TimedInterval interval) return interval.start();
        if (placement instanceof CalendarPlacement.TimedPoint point) return point.time();
        throw new IllegalArgumentException("Transport planning requires a timed activity");
    }

    @org.springframework.transaction.annotation.Transactional
    public Optional<IntentResult> answerSystemPlaceRegion(String text) {
        if (publicPlaceDrafts == null) return Optional.empty();
        Optional<PublicPlaceLookupDraftService.Answer> resolved =
                publicPlaceDrafts.answerRegion(text);
        if (resolved.isEmpty()
                || resolved.orElseThrow().mode()
                        != PublicPlaceLookupDraftMode.CALENDAR_LOCATION) {
            return Optional.empty();
        }
        var answer = resolved.orElseThrow();
        CalendarIntentDraftService.DraftView changed = drafts.reviseLocation(
                answer.calendarDraftId(), answer.calendarDraftRevision(),
                new com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation(
                        answer.selected().name(),
                        answer.selected().latitude(),
                        answer.selected().longitude()));
        var confirmation = drafts.confirm(changed.id(), changed.revision());
        String choice = " 地點採用系統公共地點「%s」；沒有建立自訂地點。"
                .formatted(answer.selected().name());
        if (confirmation.awaitingRouteConfirmation()) {
            return Optional.of(IntentResult.message(
                            IntentResult.Action.SUGGESTION_MADE,
                            preflightResponses.describe(confirmation.preflight()) + choice)
                    .withFocusBinding(binding(confirmation.draft())));
        }
        DraftView saved = confirmation.draft();
        return Optional.of(IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED,
                "已建立行程「%s」，時間是 %s。%s"
                        .formatted(saved.title(), placement(saved.placement()), choice)));
    }

    public Optional<IntentResult> reviseActive(IntentCommand command) {
        Optional<FocusedDraft> focused = activeDraft();
        if (focused.isEmpty() || !referencesActive(command, focused.orElseThrow())) {
            return Optional.empty();
        }
        DraftView changed = drafts.revise(
                focused.orElseThrow().id(),
                focused.orElseThrow().revision(),
                correction(command));
        return Optional.of(IntentResult.message(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "好，我把「%s」的提案改成 %s，還沒有放進行事曆。要照新版建立嗎？"
                                .formatted(changed.title(), placement(changed.placement())))
                .withFocusBinding(binding(changed)));
    }

    public Optional<IntentResult> confirmActive() {
        Optional<FocusedDraft> focused = activeDraft();
        if (focused.isEmpty()) return Optional.empty();
        DraftView current = drafts.get(focused.orElseThrow().id());
        if (current.routeProviderStatus()
                == CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE) {
            DraftView retained = drafts.retainUnavailableRoute(
                    current.id(), focused.orElseThrow().revision());
            return Optional.of(IntentResult.message(
                            IntentResult.Action.CONTEXT_UPDATED,
                            "好，已保留這份待確認安排；目前尚未建立行程。您之後要重查路線時再告訴我。")
                    .withFocusBinding(binding(retained)));
        }
        if (current.routeProviderStatus()
                == CalendarIntentDraftService.RouteProviderStatus.RETAINED) {
            return Optional.of(IntentResult.message(
                            IntentResult.Action.CONTEXT_UPDATED,
                            "這份待確認安排已保留，目前尚未建立行程。")
                    .withFocusBinding(binding(current)));
        }
        var confirmation = drafts.confirm(
                focused.orElseThrow().id(), focused.orElseThrow().revision());
        if (confirmation.awaitingRouteConfirmation()) {
            return Optional.of(IntentResult.message(
                            IntentResult.Action.SUGGESTION_MADE,
                            preflightResponses.describe(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft())));
        }
        DraftView saved = confirmation.draft();
        return Optional.of(IntentResult.message(
                        IntentResult.Action.SCHEDULE_CONFIRMED,
                        "好，已把「%s」放進行事曆。".formatted(saved.title()))
                .withFocusDirective(binding(saved), ConversationFocusDirective.INVALIDATE_TARGET));
    }

    public Optional<IntentResult> discardActive() {
        Optional<FocusedDraft> focused = activeDraft();
        if (focused.isEmpty()) return Optional.empty();
        DraftView discarded = drafts.discard(
                focused.orElseThrow().id(), focused.orElseThrow().revision());
        return Optional.of(IntentResult.message(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "好，已放棄「%s」的提案，行事曆沒有新增資料。"
                                .formatted(discarded.title()))
                .withFocusDirective(
                        binding(discarded), ConversationFocusDirective.INVALIDATE_TARGET));
    }

    private Optional<FocusedDraft> activeDraft() {
        return focuses.activeFocus()
                .filter(focus -> FOCUS_DOMAIN.equals(focus.getRootDomain()))
                .filter(focus -> focus.getWorkflowId() != null)
                .map(focus -> new FocusedDraft(
                        focus.getWorkflowId(), revision(focus), focus.getSafeLabel()));
    }

    private static long revision(ConversationFocus focus) {
        String activity = focus.getActivityCode();
        if (activity == null || !activity.startsWith("revision:")) {
            throw new IllegalStateException("Calendar proposal focus has no trusted revision");
        }
        try {
            long value = Long.parseLong(activity.substring("revision:".length()));
            if (value < 1) throw new NumberFormatException("non-positive revision");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Calendar proposal focus revision is invalid", exception);
        }
    }

    private static boolean referencesActive(IntentCommand command, FocusedDraft focused) {
        String reference = command.safeOptions().referenceTitle();
        if (reference == null || reference.isBlank()) reference = command.title();
        return reference == null
                || reference.isBlank()
                || normalize(reference).equals(normalize(focused.label()));
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s　]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static IntentCommand correction(IntentCommand command) {
        return new IntentCommand(
                command.type(),
                command.safeOptions().newTitle(),
                null,
                command.startAt(),
                command.endAt(),
                command.placeName(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                command.options(),
                command.sourceText());
    }

    private static ConversationFocusBinding binding(DraftView draft) {
        return ConversationFocusBinding.workflowActivity(
                FOCUS_DOMAIN,
                draft.id(),
                draft.title(),
                "revision:" + draft.revision(),
                draft.status() == Status.PENDING ? "等待確認" : "已結束");
    }

    private static IntentResult recurrenceClarification() {
        return IntentResult.clarificationNeeded(
                "請明確告訴我週期是每天、每個平日、每週，或每月第幾個星期幾；"
                        + "確認前不會建立行程。");
    }

    private String locationDecision(String requestedPlace) {
        if (requestedPlace == null
                || requestedPlace.isBlank()
                || placeAliases == null
                || systemPlaceCatalog == null
                || placeAliases.resolve(requestedPlace).isPresent()) {
            return "";
        }
        SystemPlaceCatalog.Resolution resolution =
                systemPlaceCatalog.resolveMention(requestedPlace);
        if (resolution.status() == SystemPlaceCatalog.Resolution.Status.EXACT) {
            return "";
        }
        if (resolution.status()
                == SystemPlaceCatalog.Resolution.Status.LOGICAL_PLACE_MULTIPOINT) {
            return " 地點「%s」包含 %d 個同一場站點位；目前沒有其他可用的路線限制，"
                    .formatted(requestedPlace, resolution.candidates().size())
                    + "本次採用「%s」作為共構場站中心定位；沒有建立自訂地點。"
                            .formatted(resolution.selected().name());
        }
        return "";
    }

    private SystemPlaceCatalog.Resolution systemResolution(String requestedPlace) {
        if (requestedPlace == null
                || requestedPlace.isBlank()
                || placeAliases == null
                || systemPlaceCatalog == null
                || placeAliases.resolve(requestedPlace).isPresent()) {
            return null;
        }
        return systemPlaceCatalog.resolveMention(requestedPlace);
    }

    private static String placement(CalendarPlacement placement) {
        if (placement instanceof CalendarPlacement.TimedInterval interval) {
            ZoneId zone = interval.zoneId();
            return "%s 到 %s".formatted(
                    ZonedDateTime.ofInstant(interval.start(), zone).format(DATE_TIME),
                    ZonedDateTime.ofInstant(interval.end(), zone).format(DATE_TIME));
        }
        if (placement instanceof CalendarPlacement.TimedPoint point) {
            return ZonedDateTime.ofInstant(point.time(), point.zoneId()).format(DATE_TIME);
        }
        CalendarPlacement.AllDay allDay = (CalendarPlacement.AllDay) placement;
        return allDay.endExclusive().equals(allDay.start().plusDays(1))
                ? allDay.start().toString()
                : "%s 到 %s".formatted(allDay.start(), allDay.endExclusive().minusDays(1));
    }

    private record FocusedDraft(java.util.UUID id, long revision, String label) {}

    private enum MissingAdjacentSide {
        PREVIOUS(
                "route.previous-location",
                "前一個行程尚未設定地點，因此那一段銜接還不能確認。",
                "要補上前一個行程的地點嗎？"),
        NEXT(
                "route.next-location",
                "下一個行程尚未設定地點，因此那一段銜接還不能確認。",
                "要補上下一個行程的地點嗎？");

        private final String questionCode;
        private final String explanation;
        private final String question;

        MissingAdjacentSide(String questionCode, String explanation, String question) {
            this.questionCode = questionCode;
            this.explanation = explanation;
            this.question = question;
        }

        String questionCode() {
            return questionCode;
        }

        String explanation() {
            return explanation;
        }

        String question() {
            return question;
        }
    }
}
