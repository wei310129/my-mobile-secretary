package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationRepairDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationVoiceProfileService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairAspect;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairScope;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FeedbackPolarity;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import java.util.EnumSet;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Turns product feedback into a concrete same-turn repair or one typed repair question. */
@Service
public final class ConversationRepairService {

    private static final EnumSet<IntentResult.Action> READ_ONLY_AGENDA_ACTIONS = EnumSet.of(
            IntentResult.Action.TASKS_LISTED,
            IntentResult.Action.SCHEDULES_LISTED,
            IntentResult.Action.AGENDA_LISTED,
            IntentResult.Action.AGENDA_SUMMARY);

    private final ConversationContextService contextService;
    private final IntentHandlerRegistry handlers;
    private ConversationRepairDraftService drafts;
    private ConversationVoiceProfileService voiceProfiles;

    public ConversationRepairService(ConversationContextService contextService,
                                     IntentHandlerRegistry handlers) {
        this.contextService = contextService;
        this.handlers = handlers;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDrafts(ConversationRepairDraftService drafts) {
        this.drafts = drafts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVoiceProfiles(ConversationVoiceProfileService voiceProfiles) {
        this.voiceProfiles = voiceProfiles;
    }

    public Optional<IntentResult> answer(String text) {
        return answer(text, () -> { });
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        Optional<IntentResult> pending = answerPending(text, beforeMutation);
        if (pending.isPresent()) return pending;
        Optional<FeedbackPolarity> polarity = ProductFeedbackBoundary.feedbackPolarity(text);
        if (polarity.filter(value -> value == FeedbackPolarity.PRAISE).isPresent()) {
            if (voiceProfiles == null) {
                return ProductFeedbackBoundary.answer(text);
            }
            beforeMutation.run();
            ConversationVoiceProfileService.Variant variant = voiceProfiles.nextFeedbackVariant(
                    FeedbackPolarity.PRAISE, SecretaryFeedbackVariantPolicy.VARIANT_COUNT);
            return Optional.of(IntentResult.plainMessage(
                    IntentResult.Action.FEEDBACK_RECEIVED,
                    SecretaryFeedbackVariantPolicy.praise(
                            variant.index(), variant.settings()),
                    PublicReplyEvidence.ACKNOWLEDGED_ONLY));
        }
        Optional<ConversationRepairType> classified = ProductFeedbackBoundary.repairType(text);
        if (classified.isEmpty()) return Optional.empty();
        ConversationRepairType type = classified.orElseThrow();
        ConversationSnapshot snapshot = contextService.snapshot();
        if (type == ConversationRepairType.CROSS_DOMAIN_RELATIONSHIP
                && isRecoverableAgenda(snapshot.lastAction())) {
            String filter = SecretaryTurnRouter.route(snapshot.lastUserText())
                    .map(command -> command.safeOptions().filter())
                    .orElse("UPCOMING");
            IntentCommand command = new IntentCommand(
                    IntentCommand.Type.LIST_AGENDA, null, null, null, null, null, null, null,
                    null, null, null, null, null, IntentOptions.empty().withFilter(filter), text);
            IntentResult repaired = handlers.dispatch(text, command);
            return Optional.of(IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    "你指出得對：我剛才把待辦與行程分開回答，漏了兩者的時間關聯。"
                            + "這則回饋沒有建立或修改資料；我已依現有資料重新整理。\n\n"
                            + repaired.message())
                    .withEvidence(PublicReplyEvidence.REPAIR_COMPLETED,
                            PublicReplyEvidence.ZERO_MUTATION_VERIFIED));
        }
        if (type == ConversationRepairType.PRODUCT_RULE) {
            return ProductFeedbackBoundary.answer(text);
        }
        SecretaryFeedbackVariantPolicy.Reply dissatisfaction = null;
        if (type == ConversationRepairType.DISSATISFACTION) {
            if (voiceProfiles == null) {
                int index = Math.floorMod(text.hashCode(), SecretaryFeedbackVariantPolicy.VARIANT_COUNT);
                dissatisfaction = SecretaryFeedbackVariantPolicy.dissatisfaction(
                        index, ConversationVoiceProfileService.Settings.defaults());
            } else {
                beforeMutation.run();
                ConversationVoiceProfileService.Variant variant = voiceProfiles.nextFeedbackVariant(
                        FeedbackPolarity.DISSATISFACTION,
                        SecretaryFeedbackVariantPolicy.VARIANT_COUNT);
                dissatisfaction = SecretaryFeedbackVariantPolicy.dissatisfaction(
                        variant.index(), variant.settings());
            }
        }
        String fact = switch (type) {
            case CROSS_DOMAIN_RELATIONSHIP ->
                    "你指出得對：我剛才沒有把待辦與行程的時間關聯一起處理。這則回饋沒有建立或修改資料。";
            case REPEATED_QUESTION ->
                    "你指出得對：我重問了已經提供或確認過的資訊。這則回饋沒有建立或修改資料。";
            case FORMAT ->
                    "你指出得對：我剛才的回覆格式沒有把重點清楚分開。這則回饋沒有建立或修改資料。";
            case WRONG_ANSWER -> ProductFeedbackBoundary.correctionAcknowledgement(text);
            case DISSATISFACTION -> dissatisfaction.fact();
            case PRODUCT_RULE -> throw new IllegalStateException("product rule handled above");
        };
        String question = type == ConversationRepairType.DISSATISFACTION
                ? dissatisfaction.question()
                : "您要我先更正待辦、行程，還是上一則回答的其他部分？";
        ClarificationStep step = ClarificationStep.blocking(
                "conversation-repair.target", "repair.target",
                question, 10);
        if (drafts != null) {
            beforeMutation.run();
            drafts.start(type, snapshot);
        }
        return Optional.of(IntentResult.feedbackNeedsInput(fact, step));
    }

    private Optional<IntentResult> answerPending(String text, Runnable beforeMutation) {
        if (drafts == null || text == null || text.isBlank()) return Optional.empty();
        Optional<ConversationRepairDraft> current = drafts.current();
        if (current.isEmpty()) return Optional.empty();
        ConversationRepairDraft draft = current.orElseThrow();
        String compact = text.replaceAll("\\s+", "");
        if (containsAny(compact, "不用", "算了", "取消更正", "先不要")) {
            drafts.cancel(draft.getId());
            contextService.preserveReferencesForInterjection();
            return Optional.of(IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    "好，這次先不更正；原本的待辦與行程都沒有變動。"));
        }
        if (containsAny(compact,
                "新增", "建立", "取消", "刪除", "修改", "改期", "改成", "完成", "做完", "提醒")) {
            return Optional.empty();
        }
        RepairTarget target = target(compact);
        if (target == null) {
            ConversationRepairAspect aspect = aspect(compact);
            if (aspect == null) return Optional.empty();
            beforeMutation.run();
            drafts.refine(draft.getId(), aspect);
            contextService.preserveReferencesForInterjection();
            return Optional.of(aspectQuestion(aspect));
        }
        IntentCommand.Type type = switch (target) {
            case TASKS -> IntentCommand.Type.LIST_TASKS;
            case SCHEDULES -> IntentCommand.Type.LIST_SCHEDULES;
            case BOTH -> IntentCommand.Type.LIST_AGENDA;
        };
        IntentCommand command = new IntentCommand(
                type, null, null, null, null, null, null, null,
                null, null, null, null, null,
                IntentOptions.empty().withFilter(filter(draft.getTimeScope())), text);
        IntentResult repaired = handlers.dispatch(text, command);
        drafts.complete(draft.getId());
        contextService.preserveReferencesForInterjection();
        return Optional.of(IntentResult.message(repaired.action(),
                "我已按您的更正重新整理，沒有建立或修改資料。\n\n" + repaired.message())
                .withEvidence(PublicReplyEvidence.REPAIR_COMPLETED,
                        PublicReplyEvidence.ZERO_MUTATION_VERIFIED));
    }

    private static ConversationRepairAspect aspect(String compact) {
        if (containsAny(compact,
                "回答方式", "回覆方式", "答覆方式", "語氣", "格式", "制式", "口吻", "用詞",
                "簡短", "自然", "口語", "條列", "版面")) {
            return ConversationRepairAspect.PRESENTATION;
        }
        if (containsAny(compact, "資料", "數字", "日期", "時間", "地點")) {
            return ConversationRepairAspect.DATA;
        }
        if (containsAny(compact, "內容", "重點", "意思", "理解")) {
            return ConversationRepairAspect.CONTENT;
        }
        return null;
    }

    private static IntentResult aspectQuestion(ConversationRepairAspect aspect) {
        String fact;
        String code;
        String slot;
        String question;
        switch (aspect) {
            case PRESENTATION -> {
                fact = "了解，問題在上一則的回答方式；目前還沒有重新處理。";
                code = "conversation-repair.presentation-detail";
                slot = "repair.presentationDetail";
                question = "您希望我先確認用詞、長度，還是版面格式？";
            }
            case DATA -> {
                fact = "了解，您要先確認上一則使用的資料；目前還沒有重新處理。";
                code = "conversation-repair.data-detail";
                slot = "repair.dataDetail";
                question = "您希望我先確認待辦、行程，還是其他資料？";
            }
            case CONTENT -> {
                fact = "了解，問題在上一則的內容；目前還沒有重新處理。";
                code = "conversation-repair.content-detail";
                slot = "repair.contentDetail";
                question = "您希望我先確認哪個內容重點？";
            }
            case UNSPECIFIED -> throw new IllegalArgumentException("repair aspect is required");
            default -> throw new IllegalStateException("unsupported repair aspect: " + aspect);
        }
        return IntentResult.feedbackNeedsInput(
                fact, ClarificationStep.blocking(code, slot, question, 10));
    }

    private static RepairTarget target(String compact) {
        boolean tasks = containsAny(compact, "待辦", "任務");
        boolean schedules = containsAny(compact, "行程", "日曆");
        if (tasks && schedules || containsAny(compact, "兩者", "一起", "全部", "都要")) {
            return RepairTarget.BOTH;
        }
        if (tasks) return RepairTarget.TASKS;
        if (schedules) return RepairTarget.SCHEDULES;
        return null;
    }

    private static String filter(ConversationRepairScope scope) {
        return scope.name();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static boolean isRecoverableAgenda(String lastAction) {
        if (lastAction == null) return false;
        try {
            return READ_ONLY_AGENDA_ACTIONS.contains(IntentResult.Action.valueOf(lastAction));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private enum RepairTarget { TASKS, SCHEDULES, BOTH }
}
