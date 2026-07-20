package com.aproject.aidriven.mymobilesecretary.draft.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding.DraftType;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionPreference;
import com.aproject.aidriven.mymobilesecretary.draft.persistence.DraftRetentionBindingRepository;
import com.aproject.aidriven.mymobilesecretary.draft.persistence.DraftRetentionPreferenceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ProductObservationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.payment.persistence.BankTransferDraftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Actor-private lifecycle, inheritance and delivery policy for short-lived drafts. */
@Service
@Transactional
public class DraftRetentionService {
    public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DraftRetentionPreferenceRepository preferences;
    private final DraftRetentionBindingRepository bindings;
    private final BankTransferDraftRepository bankDrafts;
    private final ProductObservationDraftRepository productDrafts;
    private final NotificationPublisher notifications;
    private final Clock clock;

    public DraftRetentionService(DraftRetentionPreferenceRepository preferences,
                                 DraftRetentionBindingRepository bindings,
                                 BankTransferDraftRepository bankDrafts,
                                 ProductObservationDraftRepository productDrafts,
                                 NotificationPublisher notifications, Clock clock) {
        this.preferences = preferences;
        this.bindings = bindings;
        this.bankDrafts = bankDrafts;
        this.productDrafts = productDrafts;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Backward-compatible initial value before the persisted actor defaults are registered. */
    public static Instant expiresAt(Instant editedAt) {
        return editedAt.atZone(TAIPEI).toLocalDate().plusDays(8)
                .atStartOfDay(TAIPEI).toInstant();
    }

    public DraftRetentionBinding register(DraftType type, Long draftId, String title) {
        Instant now = Instant.now(clock);
        DraftRetentionBinding binding = DraftRetentionBinding.create(
                type, draftId, title, preference(now), now);
        syncDomainExpiry(binding, now);
        return bindings.save(binding);
    }

    public String disclose(DraftType type, Long draftId) {
        UUID actorId = actorId();
        DraftRetentionPreference defaults = preference(Instant.now(clock));
        DraftRetentionBinding binding = bindings
                .findByCreatedByUserIdAndDraftTypeAndDraftId(actorId, type, draftId)
                .orElseThrow(() -> new IllegalStateException("draft retention binding not found"));
        String retentionSource = binding.isUsesDefaultRetention() ? "預設" : "個別自訂";
        String reminderSource = binding.isUsesDefaultReminder() ? "預設" : "個別自訂";
        String message = "這份草稿目前保留 %d 天（%s），到期日為 %s，系統會在當日 24:00 刪除；"
                .formatted(binding.getEffectiveRetentionDays(defaults), retentionSource,
                        DATE.format(expiryDay(binding.getExpiresAt())))
                + "提醒時間是 %s（%s）。".formatted(
                        DATE_TIME.format(binding.getRemindAt().atZone(TAIPEI)), reminderSource);
        if (defaults.isSettingsConfirmed()) return message;
        return message + " 這是第一次建立限期草稿，請確認預設值：保留 7 天、到期日 20:00 提醒。"
                + "可回覆「預設草稿保留7天，提醒時間晚上8點」；保留最長 30 天，提醒最晚 23:00。";
    }

    public boolean hasUnconfirmedPreference() {
        return preferences.findByCreatedByUserId(actorId())
                .map(value -> !value.isSettingsConfirmed()).orElse(false);
    }

    public String changeDefaults(Integer retentionDays, Integer reminderDaysBefore,
                                 LocalTime reminderTime) {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        if (retentionDays != null) defaults.changeRetentionDays(retentionDays, now);
        if (reminderDaysBefore != null || reminderTime != null) {
            int daysBefore = reminderDaysBefore == null
                    ? defaults.getDefaultReminderDaysBefore() : reminderDaysBefore;
            LocalTime time = reminderTime == null
                    ? defaults.getDefaultReminderTime() : reminderTime;
            defaults.changeReminder(daysBefore, time, now);
        } else {
            defaults.confirm(now);
        }
        refreshInheritedBindings(defaults, now);
        preferences.save(defaults);
        return "已更新草稿預設：保留 %d 天，到期前 %d 天的 %s 提醒。仍使用預設的草稿已同步重算。"
                .formatted(defaults.getDefaultRetentionDays(),
                        defaults.getDefaultReminderDaysBefore(),
                        defaults.getDefaultReminderTime());
    }

    public String customizeLatestRetention(int days) {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        DraftRetentionBinding binding = latest();
        binding.customizeRetention(days, defaults, now);
        syncAndSave(binding, now);
        return disclose(binding.getDraftType(), binding.getDraftId());
    }

    public String customizeLatestReminder(int daysBefore, LocalTime time) {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        DraftRetentionBinding binding = latest();
        binding.customizeReminder(daysBefore, time, defaults, now);
        syncAndSave(binding, now);
        return disclose(binding.getDraftType(), binding.getDraftId());
    }

    public String customizeLatest(Integer retentionDays, Integer reminderDaysBefore,
                                  LocalTime reminderTime) {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        DraftRetentionBinding binding = latest();
        binding.customize(retentionDays, reminderDaysBefore, reminderTime, defaults, now);
        syncAndSave(binding, now);
        return disclose(binding.getDraftType(), binding.getDraftId());
    }

    public String resetLatestRetention() {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        DraftRetentionBinding binding = latest();
        binding.useDefaultRetention(defaults, now);
        syncAndSave(binding, now);
        return disclose(binding.getDraftType(), binding.getDraftId());
    }

    public String resetLatestReminder() {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        DraftRetentionBinding binding = latest();
        binding.useDefaultReminder(defaults, now);
        syncAndSave(binding, now);
        return disclose(binding.getDraftType(), binding.getDraftId());
    }

    public String resetLatest(boolean retention, boolean reminder) {
        Instant now = Instant.now(clock);
        DraftRetentionPreference defaults = preference(now);
        DraftRetentionBinding binding = latest();
        binding.useDefaults(retention, reminder, defaults, now);
        syncAndSave(binding, now);
        return disclose(binding.getDraftType(), binding.getDraftId());
    }

    public String touch(DraftType type, Long draftId) {
        Instant now = Instant.now(clock);
        DraftRetentionBinding binding = bindings
                .findByCreatedByUserIdAndDraftTypeAndDraftId(actorId(), type, draftId)
                .orElseThrow(() -> new IllegalStateException("draft retention binding not found"));
        binding.touch(preference(now), now);
        syncAndSave(binding, now);
        return disclose(type, draftId);
    }

    public void complete(DraftType type, Long draftId) {
        bindings.findByCreatedByUserIdAndDraftTypeAndDraftId(actorId(), type, draftId)
                .ifPresent(bindings::delete);
    }

    public int notifyExpiringDrafts(Instant now) {
        int sent = 0;
        for (DraftRetentionBinding binding : bindings
                .findByCreatedByUserIdAndNotifiedAtIsNullAndRemindAtLessThanEqual(actorId(), now)) {
            String key = "draft-expiry:%s:%d:%s".formatted(binding.getDraftType(),
                    binding.getDraftId(), binding.getRemindAt());
            int enqueued = notifications.enqueue(new NotificationRequest(actorId(), key,
                    null, null, "草稿即將到期",
                    "你的草稿「%s」將在 %s 24:00 過期並刪除；若仍要保留，請在刪除前完成或編輯它。"
                            .formatted(binding.getTitle(), DATE.format(
                                    expiryDay(binding.getExpiresAt())))));
            if (enqueued > 0) {
                binding.markNotified(now);
                bindings.save(binding);
                sent++;
            }
        }
        return sent;
    }

    public int deleteExpiredDrafts(Instant now) {
        int deleted = 0;
        for (DraftRetentionBinding binding : bindings
                .findByCreatedByUserIdAndExpiresAtLessThanEqual(actorId(), now)) {
            switch (binding.getDraftType()) {
                case BANK_TRANSFER -> bankDrafts.deleteById(binding.getDraftId());
                case PRODUCT_OBSERVATION -> productDrafts.deleteById(binding.getDraftId());
            }
            bindings.delete(binding);
            deleted++;
        }
        return deleted;
    }

    public static LocalDate expiryDay(Instant expiresAt) {
        return expiresAt.atZone(TAIPEI).toLocalDate().minusDays(1);
    }

    private void refreshInheritedBindings(DraftRetentionPreference defaults, Instant now) {
        for (DraftRetentionBinding binding : bindings.findByCreatedByUserId(actorId())) {
            binding.refreshDefaults(defaults, now);
            syncAndSave(binding, now);
        }
    }

    private void syncAndSave(DraftRetentionBinding binding, Instant now) {
        syncDomainExpiry(binding, now);
        bindings.save(binding);
    }

    private void syncDomainExpiry(DraftRetentionBinding binding, Instant now) {
        switch (binding.getDraftType()) {
            case BANK_TRANSFER -> bankDrafts.findById(binding.getDraftId())
                    .ifPresent(draft -> draft.extendRetention(now, binding.getExpiresAt()));
            case PRODUCT_OBSERVATION -> productDrafts.findById(binding.getDraftId())
                    .ifPresent(draft -> draft.extendRetention(now, binding.getExpiresAt()));
        }
    }

    private DraftRetentionBinding latest() {
        return bindings.findFirstByCreatedByUserIdOrderByUpdatedAtDesc(actorId())
                .orElseThrow(() -> new IllegalStateException("no pending short-lived draft exists"));
    }

    private DraftRetentionPreference preference(Instant now) {
        return preferences.findByCreatedByUserId(actorId())
                .orElseGet(() -> preferences.save(DraftRetentionPreference.suggested(now)));
    }

    private static UUID actorId() {
        return WorkspaceContextHolder.requireContext().actorId();
    }
}
