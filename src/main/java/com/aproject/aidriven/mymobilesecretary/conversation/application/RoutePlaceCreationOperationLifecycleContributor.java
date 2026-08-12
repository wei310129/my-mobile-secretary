package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationStep;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Lifecycle owner for the Place child of one unfinished route operation. */
@Component
public final class RoutePlaceCreationOperationLifecycleContributor
        implements ConversationOperationLifecycleContributor {

    static final String KIND = "route-place";
    private final RoutePlaceCreationDraftService children;
    private final CalendarIntentDraftService calendarDrafts;

    public RoutePlaceCreationOperationLifecycleContributor(
            RoutePlaceCreationDraftService children,
            CalendarIntentDraftService calendarDrafts) {
        this.children = children;
        this.calendarDrafts = calendarDrafts;
    }

    @Override
    public String operationKind() {
        return KIND;
    }

    @Override
    public Optional<Operation> resolve(Reference reference) {
        if (reference == null || reference.workflowId() == null) return Optional.empty();
        return children.find(reference.workflowId())
                .filter(child -> child.getStatus() == RoutePlaceCreationDraftStatus.PENDING)
                .flatMap(this::operation);
    }

    @Override
    public Optional<Operation> findCurrentUnfinished() {
        return children.current().flatMap(this::operation);
    }

    @Override
    public Optional<ResumeQuestion> resumeQuestion(
            Operation operation, String currentQuestionCode) {
        RoutePlaceCreationDraft child = children.find(operation.workflowId())
                .filter(value -> value.getStatus() == RoutePlaceCreationDraftStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("route place child is unavailable"));
        String topic = parentTitle(child);
        if (child.getStep() == RoutePlaceCreationStep.OFFER) {
            String activeStep = "確認是否建立出發地點「%s」".formatted(child.getRequestedAlias());
            String prompt = "我找不到已儲存的地點「%s」。您可以回覆「建立地點」、直接提供地點名稱／地址／Google Maps 連結、改用另一個已儲存地點，或回覆「取消」。若要改做其他事情，請說「保留，開始新的操作」。這次要怎麼處理「%s」？"
                    .formatted(child.getRequestedAlias(), child.getRequestedAlias());
            return Optional.of(ResumeQuestion.withContext(
                    childContext(
                            topic,
                            activeStep,
                            child,
                            "尚未決定是否為「%s」建立新地點".formatted(child.getRequestedAlias())),
                    RoutePlaceCreationDraftService.OFFER_QUESTION_CODE,
                    "place.route-create-offer",
                    prompt,
                    80));
        }
        if (child.getStep() == RoutePlaceCreationStep.DETAILS) {
            String activeStep = "建立出發地點「%s」".formatted(child.getRequestedAlias());
            String prompt = "請提供「%s」的地點名稱、完整地址或 Google Maps 連結，例如「內湖富邦大樓」。若要改做其他事情，請說「保留，開始新的操作」。"
                    .formatted(child.getRequestedAlias());
            return Optional.of(ResumeQuestion.withContext(
                    childContext(
                            topic,
                            activeStep,
                            child,
                            "尚未提供可驗證的地點名稱、地址或 Google Maps 連結"),
                    RoutePlaceCreationDraftService.DETAILS_QUESTION_CODE,
                    "place.route-create-details",
                    prompt,
                    80));
        }
        String activeStep = "決定「%s」的地點紀錄類別".formatted(child.getRequestedAlias());
        return Optional.of(ResumeQuestion.choiceWithContext(
                childContext(
                        topic,
                        activeStep,
                        child,
                        "尚未決定要儲存為個人地點紀錄、只使用這次，或取消"),
                "place.route-use",
                RoutePlaceCreationChoiceCatalog.usePlace(),
                20));
    }

    @Override
    public Optional<Operation> parent(Operation operation) {
        RoutePlaceCreationDraft child = children.find(operation.workflowId()).orElse(null);
        if (child == null) return Optional.empty();
        return calendarDrafts.findAvailableForLifecycle(child.getParentCalendarDraftId())
                .map(parent -> new Operation(
                        RouteConversationOperationLifecycleContributor.KIND,
                        CalendarIntentDraftConversationService.FOCUS_DOMAIN,
                        parent.id(),
                        null,
                        parent.title(),
                        parent.status() == CalendarIntentDraftService.Status.PENDING));
    }

    @Override
    public CloseOutcome close(Operation operation) {
        return new CloseOutcome(children.cancel(operation.workflowId()) ? 1 : 0, true);
    }

    private Optional<Operation> operation(RoutePlaceCreationDraft child) {
        if (child.getStatus() != RoutePlaceCreationDraftStatus.PENDING) return Optional.empty();
        return Optional.of(new Operation(
                KIND,
                "route_place_creation",
                child.getId(),
                null,
                parentTitle(child),
                true));
    }

    private String parentTitle(RoutePlaceCreationDraft child) {
        return calendarDrafts.findAvailableForLifecycle(child.getParentCalendarDraftId())
                .map(CalendarIntentDraftService.DraftView::title)
                .orElseThrow(() -> new IllegalStateException("route parent is unavailable"));
    }

    private static LifecycleContext childContext(
            String topic,
            String activeStep,
            RoutePlaceCreationDraft child,
            String unresolvedFact) {
        java.util.ArrayList<String> preserved = new java.util.ArrayList<>();
        preserved.add("父流程「%s」仍保留".formatted(topic));
        preserved.add("要作為出發地的名稱是「%s」".formatted(child.getRequestedAlias()));
        if (child.getCandidateName() != null && !child.getCandidateName().isBlank()) {
            preserved.add("已確認候選地點「%s」".formatted(child.getCandidateName()));
        }
        preserved.add("路線尚未查詢，行程尚未完成建立");
        return new LifecycleContext(
                topic, activeStep, List.copyOf(preserved), List.of(unresolvedFact));
    }
}
