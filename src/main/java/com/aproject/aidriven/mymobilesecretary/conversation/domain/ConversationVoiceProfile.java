package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Actor-owned conversational names and bounded response-variant cursors. */
@Entity
@Table(name = "conversation_voice_profile")
public class ConversationVoiceProfile extends WorkspaceOwnedEntity {

    private static final int MAX_DISPLAY_CODE_POINTS = 24;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assistant_self_name", length = 96)
    private String assistantSelfName;

    @Column(name = "user_address", length = 96)
    private String userAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_style", length = 40)
    private ConversationResponseStyle responseStyle;

    @Column(name = "praise_variant_cursor", nullable = false)
    private int praiseVariantCursor;

    @Column(name = "dissatisfaction_variant_cursor", nullable = false)
    private int dissatisfactionVariantCursor;

    @Column(nullable = false)
    private long revision;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ConversationVoiceProfile() {
    }

    public static ConversationVoiceProfile create(Instant now) {
        ConversationVoiceProfile profile = new ConversationVoiceProfile();
        profile.praiseVariantCursor = -1;
        profile.dissatisfactionVariantCursor = -1;
        profile.revision = 1;
        profile.createdAt = Objects.requireNonNull(now, "now");
        profile.updatedAt = now;
        return profile;
    }

    public void changeNames(String assistantName, String address, Instant now) {
        String validatedAssistant = normalizeDisplayValue(assistantName, "assistant self name");
        String validatedAddress = normalizeDisplayValue(address, "user address");
        if (Objects.equals(assistantSelfName, validatedAssistant)
                && Objects.equals(userAddress, validatedAddress)) {
            return;
        }
        assistantSelfName = validatedAssistant;
        userAddress = validatedAddress;
        changed(now);
    }

    public void changeAssistantSelfName(String value, Instant now) {
        String validated = normalizeDisplayValue(value, "assistant self name");
        if (!Objects.equals(assistantSelfName, validated)) {
            assistantSelfName = validated;
            changed(now);
        }
    }

    public void changeUserAddress(String value, Instant now) {
        String validated = normalizeDisplayValue(value, "user address");
        if (!Objects.equals(userAddress, validated)) {
            userAddress = validated;
            changed(now);
        }
    }

    public void changeResponseStyle(ConversationResponseStyle value, Instant now) {
        if (!Objects.equals(responseStyle, value)) {
            responseStyle = value;
            changed(now);
        }
    }

    public int nextFeedbackVariant(FeedbackPolarity polarity, int variantCount, Instant now) {
        Objects.requireNonNull(polarity, "polarity");
        if (variantCount < 2) {
            throw new IllegalArgumentException("feedback variant count must be at least two");
        }
        int next;
        if (polarity == FeedbackPolarity.PRAISE) {
            next = Math.floorMod(praiseVariantCursor + 1, variantCount);
            praiseVariantCursor = next;
        } else {
            next = Math.floorMod(dissatisfactionVariantCursor + 1, variantCount);
            dissatisfactionVariantCursor = next;
        }
        changed(now);
        return next;
    }

    private void changed(Instant now) {
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public static String normalizeDisplayValue(String value, String label) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if ((normalized.startsWith("「") && normalized.endsWith("」"))
                || (normalized.startsWith("\"") && normalized.endsWith("\""))) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean unsafe = normalized.isBlank()
                || normalized.codePointCount(0, normalized.length()) > MAX_DISPLAY_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl)
                || lower.contains("://")
                || lower.startsWith("www.");
        if (unsafe) {
            throw new IllegalArgumentException(label + " is not a safe display value");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public String getAssistantSelfName() {
        return assistantSelfName;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public ConversationResponseStyle getResponseStyle() {
        return responseStyle;
    }

    public int getPraiseVariantCursor() {
        return praiseVariantCursor;
    }

    public int getDissatisfactionVariantCursor() {
        return dissatisfactionVariantCursor;
    }

    public long getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
