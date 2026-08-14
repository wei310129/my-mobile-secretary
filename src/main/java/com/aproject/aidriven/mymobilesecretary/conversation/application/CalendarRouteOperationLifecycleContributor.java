package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Calendar-owned lifecycle adapter; it exposes no provider or database diagnostics. */
@Component
public final class CalendarRouteOperationLifecycleContributor
        implements ConversationOperationLifecycleContributor {

    public static final String KIND = "calendar-route";
    private final CalendarIntentDraftService drafts;

    public CalendarRouteOperationLifecycleContributor(CalendarIntentDraftService drafts) {
        this.drafts = drafts;
    }

    @Override
    public String operationKind() {
        return KIND;
    }

    @Override
    public Optional<Operation> resolve(Reference reference) {
        if (!CalendarIntentDraftService.ROOT_DOMAIN.equals(reference.rootDomain())
                || reference.workflowId() == null) {
            return Optional.empty();
        }
        return drafts.findAvailableForLifecycle(reference.workflowId())
                .map(this::operation);
    }

    @Override
    public Optional<ResumeQuestion> resumeQuestion(Operation operation, String currentQuestionCode) {
        if (!KIND.equals(operation.operationKind()) || operation.workflowId() == null) {
            return Optional.empty();
        }
        return drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(draft -> draft.status() == CalendarIntentDraftService.Status.PENDING)
                .map(this::question);
    }

    @Override
    public CloseOutcome close(Operation operation) {
        if (!KIND.equals(operation.operationKind()) || operation.workflowId() == null) {
            return new CloseOutcome(0, true);
        }
        return drafts.findAvailableForLifecycle(operation.workflowId())
                .filter(draft -> draft.status() == CalendarIntentDraftService.Status.PENDING)
                .map(draft -> new CloseOutcome(
                        drafts.discard(draft.id(), draft.revision()) ? 1 : 0,
                        true))
                .orElseGet(() -> new CloseOutcome(0, true));
    }

    private Operation operation(CalendarIntentDraftService.DraftView draft) {
        return new Operation(
                KIND,
                CalendarIntentDraftService.ROOT_DOMAIN,
                draft.id(),
                null,
                draft.title(),
                draft.status() == CalendarIntentDraftService.Status.PENDING);
    }

    private ResumeQuestion question(CalendarIntentDraftService.DraftView draft) {
        LifecycleContext context = new LifecycleContext(
                draft.title(),
                activeStep(draft),
                List.of(preservedEndpointFact(draft), routeEvidenceFact(draft)),
                List.of(nextFact(draft)));
        return new ResumeQuestion(questionCode(draft), "route", prompt(draft), 80, context);
    }

    private static String activeStep(CalendarIntentDraftService.DraftView draft) {
        if (draft.origin() == null) {
            return "等待補足出發地點";
        }
        return switch (draft.providerStatus()) {
            case NOT_REQUESTED -> "等待確認可信路線資料";
            case AVAILABLE -> "等待建立行程";
            case UNAVAILABLE, RETAINED -> "等待調整路線條件";
        };
    }

    private static String routeEvidenceFact(CalendarIntentDraftService.DraftView draft) {
        return draft.hasProviderEvidence() ? "路線資料已確認" : "尚未建立行程";
    }

    private static String preservedEndpointFact(CalendarIntentDraftService.DraftView draft) {
        return draft.origin() == null ? "終點已保留" : "起點和終點已保留";
    }

    private static String nextFact(CalendarIntentDraftService.DraftView draft) {
        if (draft.origin() == null) {
            return "需要出發地點";
        }
        return switch (draft.providerStatus()) {
            case NOT_REQUESTED -> "需要確認路線資料";
            case AVAILABLE -> "需要確認是否建立行程";
            case UNAVAILABLE, RETAINED -> "需要調整出發時間、地點或稍後再試";
        };
    }

    private static String questionCode(CalendarIntentDraftService.DraftView draft) {
        if (draft.origin() == null) {
            return "route.origin";
        }
        return switch (draft.providerStatus()) {
            case NOT_REQUESTED -> "route.provider-evidence";
            case AVAILABLE -> "route.materialize-confirm";
            case UNAVAILABLE, RETAINED -> "route.provider-unavailable";
        };
    }

    private static String prompt(CalendarIntentDraftService.DraftView draft) {
        if (draft.origin() == null) {
            return "目前正在處理「" + draft.title()
                    + "」。尚未取得出發地點，行程尚未建立；請提供出發地點。";
        }
        return switch (draft.providerStatus()) {
            case NOT_REQUESTED -> "目前正在處理「" + draft.title()
                    + "」。起點和終點已保留，下一步是確認路線資料。";
            case AVAILABLE -> "目前正在處理「" + draft.title()
                    + "」。路線資料已確認，尚未建立行程；要建立這筆行程嗎？";
            case UNAVAILABLE, RETAINED -> "目前正在處理「" + draft.title()
                    + "」。尚未取得可信路線資料，因此尚未建立行程。請調整出發時間、地點或稍後再試。";
        };
    }
}
