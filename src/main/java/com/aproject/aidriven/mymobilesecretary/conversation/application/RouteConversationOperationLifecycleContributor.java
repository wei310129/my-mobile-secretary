package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.TransportOfferStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.application.RouteCalendarChoiceCatalog;
import com.aproject.aidriven.mymobilesecretary.calendar.application.RouteOperationPreferenceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceQuestion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Route-only lifecycle adapter; later capability adapters are intentionally separate. */
@Component
public final class RouteConversationOperationLifecycleContributor
        implements ConversationOperationLifecycleContributor {

    static final String KIND = "route";
    private final CalendarIntentDraftService drafts;
    private final RouteOperationPreferenceService preferences;
    private final CalendarIntentDraftConversationService conversations;

    public RouteConversationOperationLifecycleContributor(
            CalendarIntentDraftService drafts,
            RouteOperationPreferenceService preferences,
            CalendarIntentDraftConversationService conversations) {
        this.drafts = drafts;
        this.preferences = preferences;
        this.conversations = conversations;
    }

    @Override
    public Optional<Operation> stage(IntentCommand command) {
        return conversations.stageStandaloneRouteForContextChoice(command)
                .map(draft -> new Operation(
                        KIND,
                        CalendarIntentDraftConversationService.FOCUS_DOMAIN,
                        draft.id(),
                        null,
                        draft.title(),
                        true));
    }

    @Override
    public boolean supportsStaging(IntentCommand command) {
        return command != null && command.type() == IntentCommand.Type.PLAN_ROUTE_ITINERARY;
    }

    @Override
    public Optional<Operation> findCurrentUnfinished() {
        return drafts.currentTransportConversation()
                .filter(RouteConversationOperationLifecycleContributor::isRoute)
                .filter(draft -> draft.status() == Status.PENDING)
                .map(draft -> new Operation(
                        KIND,
                        CalendarIntentDraftConversationService.FOCUS_DOMAIN,
                        draft.id(),
                        null,
                        draft.title(),
                        true));
    }

    @Override
    public Optional<IntentResult> activate(Operation operation) {
        DraftView draft = drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(RouteConversationOperationLifecycleContributor::isRoute)
                .orElse(null);
        return draft == null
                ? Optional.empty()
                : Optional.of(conversations.activateStagedStandaloneRoute(draft.id()));
    }

    @Override
    public Optional<ResumeQuestion> resumeQuestion(
            Operation operation, String currentQuestionCode) {
        DraftView draft = drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(RouteConversationOperationLifecycleContributor::isRoute)
                .orElseThrow(() -> new IllegalStateException(
                        "route operation is no longer available"));
        boolean rideHail = "RIDE_HAIL".equals(draft.transportMode());
        Optional<QuestionSpec> retained = knownQuestion(currentQuestionCode, rideHail);
        return (retained.isPresent() ? retained : inferQuestion(draft))
                .map(question -> question.toResumeQuestion(draft));
    }

    @Override
    public String operationKind() {
        return KIND;
    }

    @Override
    public Optional<Operation> resolve(Reference reference) {
        if (reference == null || reference.workflowId() == null) return Optional.empty();
        return drafts.findAvailableForLifecycle(reference.workflowId())
                .filter(RouteConversationOperationLifecycleContributor::isRoute)
                .map(draft -> new Operation(
                        KIND,
                        CalendarIntentDraftConversationService.FOCUS_DOMAIN,
                        draft.id(),
                        null,
                        draft.title(),
                        draft.status() == Status.PENDING));
    }

    @Override
    public CloseOutcome close(Operation operation) {
        DraftView draft = drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(RouteConversationOperationLifecycleContributor::isRoute)
                .orElseThrow(() -> new IllegalStateException(
                        "route operation is no longer available"));
        if (draft.status() == Status.PENDING) {
            drafts.discard(draft.id(), draft.revision());
            return new CloseOutcome(1, true);
        }
        return new CloseOutcome(0, true);
    }

    private static boolean isRoute(DraftView draft) {
        return draft.routeTimeRole() != null || draft.hasRouteJourneyKind();
    }

    private Optional<QuestionSpec> inferQuestion(DraftView draft) {
        if (draft.transportOrigin() == null || draft.location() == null) {
            return knownQuestion("route.origin-context");
        }
        if (draft.transportMode() == null) {
            return knownQuestion("route.transport-mode");
        }
        if (draft.routeTimeRole() == null) {
            return knownQuestion("route.time");
        }
        Optional<RouteOperationPreferenceService.View> current = preferences.current();
        if ("DRIVE".equals(draft.transportMode())
                && (current.isEmpty() || current.orElseThrow().parkingMinutes() == null)) {
            return knownQuestion("route.parking-buffer");
        }
        if ("RIDE_HAIL".equals(draft.transportMode())
                && (current.isEmpty() || current.orElseThrow().rideHailWaitMinutes() == null)) {
            return knownQuestion("route.ride-hail-wait");
        }
        if (draft.routeProviderStatus()
                == CalendarIntentDraftService.RouteProviderStatus.UNAVAILABLE) {
            return knownQuestion("route.provider-unavailable");
        }
        if (draft.transportOfferStatus() == TransportOfferStatus.OFFERED) {
            return knownQuestion("route.transport-offer");
        }
        if (draft.transportOfferStatus() == TransportOfferStatus.ACCEPTED
                && draft.activityAdjustability() == null) {
            return knownQuestion("route.activity-adjustability");
        }
        if (draft.status() == Status.MATERIALIZED
                && (draft.routeProviderStatus()
                                == CalendarIntentDraftService.RouteProviderStatus.AVAILABLE
                        || draft.routeProviderStatus()
                                == CalendarIntentDraftService.RouteProviderStatus.RETAINED)) {
            return knownQuestion(
                    "route.departure-reminder", "RIDE_HAIL".equals(draft.transportMode()));
        }
        return Optional.empty();
    }

    private static Optional<QuestionSpec> knownQuestion(String code) {
        return knownQuestion(code, false);
    }

    private static Optional<QuestionSpec> knownQuestion(String code, boolean rideHail) {
        if (code == null) return Optional.empty();
        Optional<PublicConversationChoiceQuestion> choice =
                RouteCalendarChoiceCatalog.find(code, rideHail);
        if (choice.isPresent()) {
            PublicConversationChoiceQuestion question = choice.orElseThrow();
            return Optional.of(new QuestionSpec(
                    code, choiceSlot(code), question.prompt(), 40, question));
        }
        return Optional.ofNullable(switch (code) {
            case "route.endpoint" -> question(
                    code, "route.endpoint", "我還不能確定這趟行程的起點或目的地。您要補充哪一個地點？", 10);
            case "route.origin-context" -> question(
                    code, "route.origin", "依前後行程看，這次可能不是從家裡出發。這趟要從哪裡出發？", 10);
            case "route.home-location" -> question(
                    code, "route.home", "我還沒有您的住家地點。要把哪個地點設為家裡？", 10);
            case "route.transport-mode" -> question(
                    code, "route.mode", "這趟要用大眾運輸、開車、機車，還是步行？", 10);
            case "route.time" -> question(code, "route.time", "這趟預計幾點出發？", 10);
            case "route.general-buffer" -> question(
                    code, "route.general-buffer",
                    "這類行程前面與後面通常各要預留幾分鐘？可以回覆「前10、後15」。", 10);
            case "route.connection-buffer-adjust", "route.connection-buffer-keep" -> question(
                    code,
                    "route.connection-buffer",
                    "前後各要保留幾分鐘的銜接緩衝？例如：前10分鐘、後15分鐘。",
                    20);
            case "route.parking-buffer" -> question(
                    code, "route.parking-minutes", "開車抵達附近後，通常要預留幾分鐘停車？", 10);
            case "route.ride-hail-wait" -> question(
                    code, "route.ride-hail-wait-minutes", "搭計程車出發前，通常要預留幾分鐘等車？", 10);
            case "route.provider-unavailable" -> question(
                    code, "route.provider-choice", "要保留這份待確認安排，之後再查路線嗎？", 10);
            case "route.transport-offer" -> question(
                    code, "route.options", "要不要我也幫你規劃交通方式？", 10);
            case "route.activity-adjustability" -> question(
                    code, "route.adjustability", "活動時間固定，還是可以配合交通調整？", 30);
            case "route.schedule-conflict" -> question(
                    code, "route.schedule-choice", "要往後安排到安全時間，還是照原安排保留？", 10);
            case "route.schedule-conflict-keep-only" -> question(
                    code,
                    "route.schedule-choice",
                    "本次行程後方有銜接限制，往後安排無法解決。要忽略衝突並保留原安排嗎？",
                    20);
            case "route.direct-overlap" -> question(
                    code,
                    "route.schedule-choice",
                    "本次行程與既有行程直接重疊，目前尚未建立。請直接回覆新的出發時間，或回覆「照原安排保留」。",
                    40);
            case "route.previous-location" -> question(
                    code, "route.previous-location", "要補上前一個行程的地點嗎？", 10);
            case "route.next-location" -> question(
                    code, "route.next-location", "要補上下一個行程的地點嗎？", 10);
            case "route.ride-hail-reminder" -> question(
                    code, "route.ride-hail-reminder", "要幫您設定提早叫車的提醒嗎？", 10);
            case "route.departure-reminder" -> question(
                    code,
                    "route.departure-reminder",
                    "要依照行程長度設定出發提醒嗎？",
                    10);
            default -> null;
        });
    }

    private static QuestionSpec question(
            String code, String slot, String prompt, int maxLength) {
        return new QuestionSpec(code, slot, prompt, maxLength, null);
    }

    private record QuestionSpec(
            String code,
            String slot,
            String prompt,
            int maxLength,
            PublicConversationChoiceQuestion choiceQuestion) {

        ResumeQuestion toResumeQuestion(DraftView draft) {
            LifecycleContext context = lifecycleContext(draft, code, prompt);
            return choiceQuestion == null
                    ? ResumeQuestion.withContext(context, code, slot, prompt, maxLength)
                    : ResumeQuestion.choiceWithContext(
                            context, slot, choiceQuestion, maxLength);
        }
    }

    private static LifecycleContext lifecycleContext(
            DraftView draft, String code, String unresolvedFact) {
        List<String> preserved = new ArrayList<>();
        if (draft.transportOrigin() != null) preserved.add("已確認出發地");
        if (draft.location() != null) preserved.add("已確認目的地");
        if (draft.placement() != null) preserved.add("已確認行程日期與時間");
        if (preserved.isEmpty()) preserved.add("目前已確認的路線資料與進度仍保留");
        return new LifecycleContext(
                draft.title(),
                publicProgress(code),
                preserved,
                List.of(unresolvedRouteFact(code)));
    }

    private static String unresolvedRouteFact(String code) {
        return switch (code) {
            case "route.endpoint" -> "尚未確認完整起點與目的地";
            case "route.origin-context", "route.home-location" -> "尚未確認這趟的出發地";
            case "route.transport-mode" -> "尚未確認交通方式";
            case "route.time" -> "尚未確認出發或抵達時間";
            case "route.provider-unavailable" -> "尚未決定保留或放棄待確認安排";
            case "route.schedule-conflict", "route.schedule-conflict-keep-only" ->
                    "尚未決定如何處理銜接風險";
            case "route.direct-overlap" -> "尚未決定新的出發時間或是否保留原安排";
            case "route.departure-reminder", "route.ride-hail-reminder" ->
                    "尚未決定是否設定提醒";
            default -> "尚未完成目前子步驟的必要資訊或決定";
        };
    }

    private static String choiceSlot(String code) {
        return switch (code) {
            case "route.provider-unavailable" -> "route.provider-choice";
            case "route.transport-offer" -> "route.options";
            case "route.activity-adjustability" -> "route.adjustability";
            case "route.departure-reminder" -> "route.departure-reminder";
            default -> "route.schedule-choice";
        };
    }

    private static String publicProgress(String code) {
        return switch (code) {
            case "route.endpoint" -> "還缺少這趟路線的起點或目的地。";
            case "route.origin-context" -> "還不能安全確認這趟路線的出發地。";
            case "route.home-location" -> "還沒有可使用的住家地點。";
            case "route.transport-mode" -> "還缺少這趟路線的交通方式。";
            case "route.time" -> "還缺少這趟路線的出發或抵達時間。";
            case "route.general-buffer" -> "正在設定這趟路線的前後緩衝時間。";
            case "route.connection-buffer-adjust" ->
                    "已選擇往後安排，正在確認這次銜接需要的前後緩衝。";
            case "route.connection-buffer-keep" ->
                    "已選擇保留原安排，正在確認這次銜接需要的前後緩衝。";
            case "route.parking-buffer" -> "正在設定開車抵達後的停車緩衝時間。";
            case "route.ride-hail-wait" -> "正在設定搭計程車前的等車緩衝時間。";
            case "route.provider-unavailable" -> "路線資料暫時無法取得，這份行程尚未建立。";
            case "route.transport-offer" -> "活動已確認，正在確認是否安排交通。";
            case "route.activity-adjustability" -> "正在確認活動時間是否可以調整。";
            case "route.schedule-conflict" -> "已找到與既有行程的衝突風險。";
            case "route.schedule-conflict-keep-only" ->
                    "已找到後方銜接風險，往後安排本次行程無法解決。";
            case "route.direct-overlap" -> "本次行程與既有行程直接重疊，目前尚未建立。";
            case "route.previous-location" -> "前一個鄰近行程還缺少地點。";
            case "route.next-location" -> "後一個鄰近行程還缺少地點。";
            case "route.ride-hail-reminder" -> "等車緩衝已確認，正在確認是否需要叫車提醒。";
            case "route.departure-reminder" -> "行程已建立，正在確認是否需要出發提醒。";
            default -> throw new IllegalArgumentException("unsupported route resume question");
        };
    }
}
