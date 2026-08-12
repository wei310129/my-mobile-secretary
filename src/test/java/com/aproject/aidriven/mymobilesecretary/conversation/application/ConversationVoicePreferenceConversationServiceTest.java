package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceTarget;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicReplyEvidence;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationVoicePreferenceConversationServiceTest {

    private ConversationVoicePreferenceDraftService drafts;
    private ConversationVoiceProfileService profiles;
    private ConversationVoicePreferenceConversationService service;
    private AtomicInteger mutations;

    @BeforeEach
    void setUp() {
        drafts = mock(ConversationVoicePreferenceDraftService.class);
        profiles = mock(ConversationVoiceProfileService.class);
        service = new ConversationVoicePreferenceConversationService(drafts, profiles);
        mutations = new AtomicInteger();
    }

    @Test
    void oneTurnInstructionAbsorbsBothNamesAndDoesNotAskAgain() {
        when(drafts.current()).thenReturn(Optional.empty());
        when(profiles.setBoth("小賈", "老闆"))
                .thenReturn(new ConversationVoiceProfileService.Settings("小賈", "老闆", 2));

        IntentResult result = service.answer(
                "以後你叫做小賈，你要稱呼我為老闆", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.message())
                .contains("服務名稱已設定為「小賈」", "您的稱呼已設定為「老闆」");
        assertThat(result.nextQuestion()).isNull();
        assertThat(result.publicReplyEvidence()).contains(PublicReplyEvidence.PREFERENCE_COMMITTED);
        assertThat(mutations).hasValue(1);
        verify(profiles).setBoth("小賈", "老闆");
        verify(drafts, never()).start();
    }

    @Test
    void genericChangeRequestStartsOneTypedQuestion() {
        when(drafts.current()).thenReturn(Optional.empty());
        when(drafts.start()).thenReturn(mock(ConversationVoicePreferenceDraft.class));

        IntentResult result = service.answer("可以改稱呼嗎？", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.nextQuestion()).isNotNull();
        assertThat(result.nextQuestion().code()).isEqualTo("voice.target");
        assertThat(result.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
        assertThat(mutations).hasValue(1);
    }

    @Test
    void pendingBothFlowAsksOnlyForTheStillMissingUserAddress() {
        ConversationVoicePreferenceDraft current = mock(ConversationVoicePreferenceDraft.class);
        ConversationVoicePreferenceDraft updated = mock(ConversationVoicePreferenceDraft.class);
        when(current.getTarget()).thenReturn(ConversationVoicePreferenceTarget.BOTH);
        when(current.getAssistantSelfName()).thenReturn(null);
        when(drafts.current()).thenReturn(Optional.of(current));
        when(drafts.answerAssistant(current, "小賈")).thenReturn(updated);
        when(updated.getTarget()).thenReturn(ConversationVoicePreferenceTarget.BOTH);
        when(updated.getAssistantSelfName()).thenReturn("小賈");
        when(updated.getUserAddress()).thenReturn(null);

        IntentResult result = service.answer("小賈", mutations::incrementAndGet).orElseThrow();

        assertThat(result.message()).contains("接著", "怎麼稱呼您");
        assertThat(result.message()).doesNotContain("希望我叫什麼名字");
        assertThat(result.nextQuestion().code()).isEqualTo("voice.user-address");
    }

    @Test
    void queryReadsTheCommittedProfileWithoutMutation() {
        when(drafts.current()).thenReturn(Optional.empty());
        when(profiles.current())
                .thenReturn(new ConversationVoiceProfileService.Settings("小賈", "老闆", 2));

        IntentResult result = service.answer("目前的稱呼設定是什麼？", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.message()).contains("服務名稱是「小賈」", "稱呼設定是「老闆」");
        assertThat(mutations).hasValue(0);
    }

    @Test
    void pendingDraftAbsorbsBothValuesWhenUserProvidesThemTogether() {
        ConversationVoicePreferenceDraft current = mock(ConversationVoicePreferenceDraft.class);
        when(drafts.current()).thenReturn(Optional.of(current));
        when(drafts.answerBothAndComplete(current, "小賈", "老闆"))
                .thenReturn(new ConversationVoiceProfileService.Settings("小賈", "老闆", 2));

        IntentResult result = service.answer(
                        "以後你叫做小賈，你要稱呼我為老闆", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.message())
                .contains("服務名稱已設定為「小賈」", "您的稱呼已設定為「老闆」");
        assertThat(result.nextQuestion()).isNull();
        assertThat(mutations).hasValue(1);
        verify(drafts).answerBothAndComplete(current, "小賈", "老闆");
        verify(profiles, never()).setBoth("小賈", "老闆");
    }

    @Test
    void resetAssistantNameUsesTheCommittedNameInsteadOfOneHardCodedExample() {
        when(drafts.current()).thenReturn(Optional.empty());
        when(profiles.current())
                .thenReturn(new ConversationVoiceProfileService.Settings("小秘", "老闆", 2));
        when(profiles.resetAssistantName())
                .thenReturn(new ConversationVoiceProfileService.Settings(null, "老闆", 3));

        IntentResult result = service.answer("不要再叫小秘", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.message()).contains("服務名稱已移除", "自稱已恢復為「我」");
        assertThat(result.publicReplyEvidence()).contains(PublicReplyEvidence.PREFERENCE_COMMITTED);
        assertThat(mutations).hasValue(1);
        verify(profiles).resetAssistantName();
    }

    @Test
    void explicitInstructionPersistsTheSupportedSecretaryResponseStyle() {
        when(drafts.current()).thenReturn(Optional.empty());

        IntentResult result = service.answer(
                        "以後都用這種方式回答", mutations::incrementAndGet)
                .orElseThrow();

        assertThat(result.message()).contains("回答偏好已保存", "簡短", "自然", "秘書口吻");
        assertThat(result.publicReplyEvidence()).contains(PublicReplyEvidence.PREFERENCE_COMMITTED);
        assertThat(mutations).hasValue(1);
    }
}
