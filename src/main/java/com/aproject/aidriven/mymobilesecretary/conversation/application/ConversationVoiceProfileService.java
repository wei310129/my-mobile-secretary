package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationResponseStyle;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoiceProfile;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FeedbackPolarity;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationVoiceProfileRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable actor/workspace conversational names and deterministic feedback-variant rotation. */
@Service
public class ConversationVoiceProfileService {

    private final ConversationVoiceProfileRepository profiles;
    private final Clock clock;

    public ConversationVoiceProfileService(
            ConversationVoiceProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        return profiles.findByWorkspaceIdAndCreatedByUserId(
                        context.workspaceId(), context.actorId())
                .map(ConversationVoiceProfileService::settings)
                .orElse(Settings.defaults());
    }

    @Transactional
    public Settings setBoth(String assistantName, String userAddress) {
        ConversationVoiceProfile profile = profileForUpdate();
        profile.changeNames(assistantName, userAddress, Instant.now(clock));
        return settings(profiles.saveAndFlush(profile));
    }

    @Transactional
    public Settings setAssistantName(String value) {
        ConversationVoiceProfile profile = profileForUpdate();
        profile.changeAssistantSelfName(value, Instant.now(clock));
        return settings(profiles.saveAndFlush(profile));
    }

    @Transactional
    public Settings setUserAddress(String value) {
        ConversationVoiceProfile profile = profileForUpdate();
        profile.changeUserAddress(value, Instant.now(clock));
        return settings(profiles.saveAndFlush(profile));
    }

    @Transactional
    public Settings resetAssistantName() {
        return setAssistantName(null);
    }

    @Transactional
    public Settings resetUserAddress() {
        return setUserAddress(null);
    }

    @Transactional
    public Settings resetAll() {
        return setBoth(null, null);
    }

    @Transactional
    public Settings setResponseStyle(ConversationResponseStyle value) {
        ConversationVoiceProfile profile = profileForUpdate();
        profile.changeResponseStyle(value, Instant.now(clock));
        return settings(profiles.saveAndFlush(profile));
    }

    @Transactional
    public Settings resetResponseStyle() {
        return setResponseStyle(null);
    }

    @Transactional
    public Variant nextFeedbackVariant(FeedbackPolarity polarity, int variantCount) {
        ConversationVoiceProfile profile = profileForUpdate();
        int index = profile.nextFeedbackVariant(polarity, variantCount, Instant.now(clock));
        profiles.saveAndFlush(profile);
        return new Variant(index, settings(profile));
    }

    private ConversationVoiceProfile profileForUpdate() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        return profiles.findWithLockByWorkspaceIdAndCreatedByUserId(
                        context.workspaceId(), context.actorId())
                .orElseGet(() -> ConversationVoiceProfile.create(Instant.now(clock)));
    }

    private static Settings settings(ConversationVoiceProfile profile) {
        return new Settings(profile.getAssistantSelfName(), profile.getUserAddress(),
                profile.getResponseStyle(), profile.getRevision());
    }

    public record Settings(
            String assistantSelfName,
            String userAddress,
            ConversationResponseStyle responseStyle,
            long revision) {

        public Settings(String assistantSelfName, String userAddress, long revision) {
            this(assistantSelfName, userAddress, null, revision);
        }

        public static Settings defaults() {
            return new Settings(null, null, null, 0);
        }

        public String assistantReference() {
            return assistantSelfName == null ? "我" : assistantSelfName;
        }

        public String respectfulUserReference() {
            return userAddress == null ? "您" : userAddress;
        }
    }

    public record Variant(int index, Settings settings) {
    }
}
