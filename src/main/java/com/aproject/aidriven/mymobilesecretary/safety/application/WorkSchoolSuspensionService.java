package com.aproject.aidriven.mymobilesecretary.safety.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.intent.application.CalendarDatePolicy;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.safety.domain.WorkSchoolSuspensionDraft;
import com.aproject.aidriven.mymobilesecretary.safety.persistence.WorkSchoolSuspensionDraftRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps user-supplied image facts separate from deterministic official-source verification. */
@Service
@Transactional
public class WorkSchoolSuspensionService {
    static final Duration DEFAULT_CONFIRMATION_GRACE = Duration.ofMinutes(10);

    private final WorkSchoolSuspensionDraftRepository repository;
    private final SuspensionOfficialClient officialClient;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public WorkSchoolSuspensionService(WorkSchoolSuspensionDraftRepository repository,
                                       SuspensionOfficialClient officialClient,
                                       NotificationPublisher notificationPublisher,
                                       ApplicationEventPublisher eventPublisher,
                                       Clock clock) {
        this.repository = repository;
        this.officialClient = officialClient;
        this.notificationPublisher = notificationPublisher;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public IntentResult ingestImage(LocalDate date, List<RegionStatus> statuses) {
        if (date == null) throw new IllegalArgumentException("suspension notice date is required");
        List<RegionStatus> safe = statuses == null ? List.of() : statuses.stream()
                .filter(java.util.Objects::nonNull)
                .filter(status -> status.region() != null && !status.region().isBlank()
                        && status.statusText() != null && !status.statusText().isBlank())
                .limit(30).toList();
        if (safe.isEmpty()) throw new IllegalArgumentException("suspension notice has no readable region status");
        String summary = safe.stream()
                .map(status -> clean(status.region(), 80) + "：" + clean(status.statusText(), 300))
                .collect(java.util.stream.Collectors.joining("\n"));
        Instant now = Instant.now(clock);
        repository.save(WorkSchoolSuspensionDraft.pending(
                date, summary, now.plus(DEFAULT_CONFIRMATION_GRACE), now));
        eventPublisher.publishEvent(new WorkSchoolSuspensionEvent(
                date, WorkSchoolSuspensionEvent.Action.CAPTURED, now));
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "已辨識為 %s 的停班停課圖片：\n%s\n\n這只是圖片抄錄，尚未視為官方事實。"
                        .formatted(CalendarDatePolicy.format(date), summary)
                        + "需要我再到行政院人事行政總處官方頁確認嗎？回覆「不用」就不查；"
                        + "回覆「好／要／請查」會立即查，10 分鐘沒有回覆也會自動查核。");
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        var scope = WorkspaceContextHolder.requireContext();
        Optional<WorkSchoolSuspensionDraft> pending = repository
                .findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                        scope.actorId(), WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION);
        if (pending.isEmpty()) return Optional.empty();
        String current = currentMessage(text);
        String compact = current.replaceAll("\\s+", "");
        if (isNegative(compact)) {
            beforeMutation.run();
            Instant now = Instant.now(clock);
            pending.get().decline(now);
            eventPublisher.publishEvent(new WorkSchoolSuspensionEvent(
                    pending.get().getNoticeDate(), WorkSchoolSuspensionEvent.Action.DECLINED, now));
            return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    "好，這張停班停課圖片只保留為使用者提供的未查核紀錄，不會再連線官方網站。"));
        }
        if (isAffirmative(compact)) {
            beforeMutation.run();
            return Optional.of(verify(pending.get(), false));
        }
        return Optional.empty();
    }

    public int verifyDue() {
        List<WorkSchoolSuspensionDraft> due = repository
                .findByStatusAndVerifyAfterLessThanEqualOrderByVerifyAfterAsc(
                        WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION, Instant.now(clock));
        due.forEach(draft -> verify(draft, true));
        return due.size();
    }

    private IntentResult verify(WorkSchoolSuspensionDraft draft, boolean notify) {
        SuspensionOfficialClient.Verification result = officialClient.verify(
                draft.getNoticeDate(), draft.getExtractedSummary());
        Instant now = Instant.now(clock);
        WorkSchoolSuspensionEvent.Action action;
        if (!result.reachable()) {
            draft.verificationFailed(result.summary(), result.sourceUrl(), now);
            action = WorkSchoolSuspensionEvent.Action.VERIFICATION_FAILED;
        } else {
            draft.verified(result.summary(), result.sourceUrl(), result.officialMatch(), now);
            action = result.officialMatch()
                    ? WorkSchoolSuspensionEvent.Action.OFFICIAL_CONFIRMED
                    : WorkSchoolSuspensionEvent.Action.OFFICIAL_NOT_CONFIRMED;
        }
        eventPublisher.publishEvent(new WorkSchoolSuspensionEvent(
                draft.getNoticeDate(), action, now));
        String message = "停班停課官方查核（%s）：%s\n來源：%s"
                .formatted(CalendarDatePolicy.format(draft.getNoticeDate()),
                        result.summary(), result.sourceUrl());
        if (notify) {
            notificationPublisher.enqueue(new NotificationRequest(
                    draft.getCreatedByUserId(), "suspension-verification:" + draft.getId(),
                    null, null, "停班停課官方查核", message));
        }
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED, message);
    }

    private static boolean isAffirmative(String text) {
        String normalized = text.replaceAll("[，,、。！!]", "");
        return normalized.matches(
                "^(好|好的|好啊|好請查|可以|要|要查|請查|查一下|幫我查|確認|麻煩查)(了|吧)?$");
    }

    private static boolean isNegative(String text) {
        String normalized = text.replaceAll("[，,、。！!]", "");
        return normalized.matches(
                "^(不用|不用了|不用查|不用再查|不用再查了|不用確認|不要查|先不用查)(了|吧)?$");
    }

    private static String clean(String value, int max) {
        String stripped = value.strip().replaceAll("[\\r\\n]+", " ");
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    private static String currentMessage(String text) {
        if (text == null) return "";
        String marker = "【使用者目前訊息】";
        int markerIndex = text.lastIndexOf(marker);
        return markerIndex < 0 ? text : text.substring(markerIndex + marker.length()).strip();
    }

    public record RegionStatus(String region, String statusText) {
    }
}
