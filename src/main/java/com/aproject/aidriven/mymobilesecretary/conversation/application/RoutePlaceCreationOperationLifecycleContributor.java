package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationStep;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Typed child lifecycle adapter that always returns control to the scoped route parent. */
@Component
public final class RoutePlaceCreationOperationLifecycleContributor
        implements ConversationOperationLifecycleContributor {

    public static final String KIND = "route-place-creation";
    private final RoutePlaceCreationDraftService children;
    private final CalendarIntentDraftService routes;

    public RoutePlaceCreationOperationLifecycleContributor(
            RoutePlaceCreationDraftService children, CalendarIntentDraftService routes) {
        this.children = children;
        this.routes = routes;
    }

    @Override
    public String operationKind() {
        return KIND;
    }

    @Override
    public Optional<Operation> resolve(Reference reference) {
        if (!RoutePlaceCreationDraftService.ROOT_DOMAIN.equals(reference.rootDomain())
                || reference.workflowId() == null) {
            return Optional.empty();
        }
        return children.findAvailableForLifecycle(reference.workflowId()).map(this::operation);
    }

    @Override
    public Optional<ResumeQuestion> resumeQuestion(Operation operation, String currentQuestionCode) {
        if (!KIND.equals(operation.operationKind()) || operation.workflowId() == null) {
            return Optional.empty();
        }
        return children.findAvailableForLifecycle(operation.workflowId()).map(this::question);
    }

    @Override
    public Optional<Operation> parent(Operation operation) {
        if (!KIND.equals(operation.operationKind()) || operation.workflowId() == null) {
            return Optional.empty();
        }
        return children.findAvailableForLifecycle(operation.workflowId())
                .flatMap(child -> routes.findAvailableForLifecycle(child.getParentCalendarDraftId()))
                .map(route -> new Operation(
                        CalendarRouteOperationLifecycleContributor.KIND,
                        CalendarIntentDraftService.ROOT_DOMAIN,
                        route.id(),
                        null,
                        route.title(),
                        route.status() == CalendarIntentDraftService.Status.PENDING));
    }

    @Override
    public CloseOutcome close(Operation operation) {
        if (!KIND.equals(operation.operationKind()) || operation.workflowId() == null) {
            return new CloseOutcome(0, true);
        }
        return new CloseOutcome(children.cancel(operation.workflowId()) ? 1 : 0, true);
    }

    private Operation operation(RoutePlaceCreationDraft child) {
        return new Operation(
                KIND,
                RoutePlaceCreationDraftService.ROOT_DOMAIN,
                child.getId(),
                null,
                child.getRequestedAlias(),
                true);
    }

    private ResumeQuestion question(RoutePlaceCreationDraft child) {
        CalendarIntentDraftService.DraftView parent = routes
                .findAvailableForLifecycle(child.getParentCalendarDraftId())
                .orElseThrow(() -> new IllegalStateException("route parent is unavailable"));
        LifecycleContext context = new LifecycleContext(
                parent.title(),
                activeStep(child.getStep()),
                List.of("正在補足「" + child.getRequestedAlias() + "」這個地點", "原本的路線尚未建立"),
                List.of(nextFact(child.getStep())));
        return new ResumeQuestion(
                questionCode(child.getStep()),
                "route-place",
                prompt(parent.title(), child.getRequestedAlias(), child.getStep()),
                300,
                context);
    }

    private static String activeStep(RoutePlaceCreationStep step) {
        return switch (step) {
            case OFFER -> "確認是否補足地點";
            case DETAILS -> "輸入地點名稱、地址或 Google Maps 連結";
            case CONFIRM -> "確認這個地點是否只用於本次路線";
        };
    }

    private static String nextFact(RoutePlaceCreationStep step) {
        return switch (step) {
            case OFFER -> "需要確認是否補足地點";
            case DETAILS -> "需要地點名稱、地址或 Google Maps 連結";
            case CONFIRM -> "需要確認這個已解析地點";
        };
    }

    private static String questionCode(RoutePlaceCreationStep step) {
        return switch (step) {
            case OFFER -> "place.route-create-offer";
            case DETAILS -> "place.route-create-details";
            case CONFIRM -> "place.route-create-confirm";
        };
    }

    private static String prompt(String parentTitle, String alias, RoutePlaceCreationStep step) {
        return switch (step) {
            case OFFER -> "目前正在處理「" + parentTitle + "」的地點「" + alias
                    + "」。要補上這個地點嗎？";
            case DETAILS -> "目前正在處理「" + parentTitle + "」的地點「" + alias
                    + "」。請提供地點名稱、地址或 Google Maps 連結。";
            case CONFIRM -> "目前正在處理「" + parentTitle + "」的地點「" + alias
                    + "」。確認後會回到原本的路線規劃，行程尚未建立。";
        };
    }
}
