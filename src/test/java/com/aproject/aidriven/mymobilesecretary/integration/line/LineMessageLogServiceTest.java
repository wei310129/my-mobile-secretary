package com.aproject.aidriven.mymobilesecretary.integration.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LineMessageLogServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID WORKSPACE_ID = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Mock
    private LineMessageLogRepository repository;
    @Mock
    private LineMessageRetentionProperties properties;

    private LineMessageLogService service;

    @BeforeEach
    void setUp() {
        service = new LineMessageLogService(repository, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void recentHistoryIsLimitedToCurrentWorkspaceAndActor() {
        when(repository.findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                WORKSPACE_ID, ACTOR_ID, PageRequest.of(0, 200))).thenReturn(List.of());

        List<LineMessageLog> result = inScope(() -> service.listRecent(999));

        assertThat(result).isEmpty();
        verify(repository).findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                WORKSPACE_ID, ACTOR_ID, PageRequest.of(0, 200));
    }

    @Test
    void pinMasksAnEntryOwnedByAnotherActorAsNotFound() {
        when(repository.findByIdAndWorkspaceIdAndCreatedByUserId(17L, WORKSPACE_ID, ACTOR_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> inScope(() -> service.setPinned(17L, true)))
                .isInstanceOf(NotFoundException.class);

        verify(repository).findByIdAndWorkspaceIdAndCreatedByUserId(17L, WORKSPACE_ID, ACTOR_ID);
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void deleteOnlyRemovesAnEntryResolvedInsideActorScope() {
        LineMessageLog owned = LineMessageLog.of(
                LineMessageLog.Direction.IN, "TEXT", "hello", NOW);
        when(repository.findByIdAndWorkspaceIdAndCreatedByUserId(23L, WORKSPACE_ID, ACTOR_ID))
                .thenReturn(Optional.of(owned));

        inScope(() -> {
            service.delete(23L);
            return null;
        });

        verify(repository).delete(owned);
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void retentionPurgeUsesWorkspaceScopeWithoutBorrowingOneActorsVisibility() {
        when(repository.deleteByWorkspaceIdAndPinnedFalseAndExpiresAtBefore(WORKSPACE_ID, NOW))
                .thenReturn(4L);

        assertThat(inScope(service::purgeExpired)).isEqualTo(4L);

        verify(repository).deleteByWorkspaceIdAndPinnedFalseAndExpiresAtBefore(WORKSPACE_ID, NOW);
    }

    @Test
    void quotedMessageAndRecentHistoryBecomeBoundedInterpreterContext() {
        LineMessageLog quoted = LineMessageLog.of(
                LineMessageLog.Direction.OUT, "TEXT", "活動草稿缺少日期", NOW);
        LineMessageLog recent = LineMessageLog.of(
                LineMessageLog.Direction.IN, "IMAGE", "[圖片]", NOW.minusSeconds(10));
        when(repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                WORKSPACE_ID, ACTOR_ID, "quoted-1")).thenReturn(Optional.of(quoted));
        when(repository.findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                WORKSPACE_ID, ACTOR_ID, PageRequest.of(0, 6))).thenReturn(List.of(recent));

        String context = inScope(() -> service.contextualize("7/9", "quoted-1"));

        assertThat(context).contains("【LINE 明確引用】活動草稿缺少日期",
                "【近期對話】", "【使用者目前訊息】7/9");
    }

    @Test
    void imageInterpretationSummaryIsRetainedForLaterQuoteQuestions() {
        LineMessageLog image = LineMessageLog.of(
                LineMessageLog.Direction.IN, "IMAGE", "[圖片]",
                "image-1", null, NOW, NOW.plusSeconds(3600));
        when(repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                WORKSPACE_ID, ACTOR_ID, "image-1")).thenReturn(Optional.of(image));
        when(repository.findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                WORKSPACE_ID, ACTOR_ID, PageRequest.of(0, 6))).thenReturn(List.of(image));

        inScope(() -> {
            service.enrichImageContextSafely("image-1",
                    "已記下升級至 Windows 10/11 專業版，購買日期 2024-10-01，金額 2,999 元。");
            return null;
        });
        String context = inScope(() -> service.contextualize("我什麼時候買的？", "image-1"));

        assertThat(context).contains("【LINE 明確引用】[圖片解析結果]",
                "Windows 10/11 專業版", "2024-10-01", "2,999 元");
    }

    @Test
    void historicalBareImageQuoteFallsBackToItsFollowingAssistantReply() {
        LineMessageLog image = LineMessageLog.of(
                LineMessageLog.Direction.IN, "IMAGE", "[圖片]",
                "legacy-image", null, NOW, NOW.plusSeconds(3600));
        ReflectionTestUtils.setField(image, "id", 41L);
        LineMessageLog reply = LineMessageLog.of(
                LineMessageLog.Direction.OUT, "TEXT",
                "已記下升級至 Windows 10/11 專業版。", NOW.plusSeconds(1));
        when(repository.findFirstByWorkspaceIdAndCreatedByUserIdAndExternalMessageId(
                WORKSPACE_ID, ACTOR_ID, "legacy-image")).thenReturn(Optional.of(image));
        when(repository
                .findFirstByWorkspaceIdAndCreatedByUserIdAndDirectionAndIdGreaterThanOrderByIdAsc(
                        WORKSPACE_ID, ACTOR_ID, LineMessageLog.Direction.OUT, 41L))
                .thenReturn(Optional.of(reply));
        when(repository.findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                WORKSPACE_ID, ACTOR_ID, PageRequest.of(0, 6))).thenReturn(List.of(reply, image));

        String context = inScope(() -> service.contextualize(
                "我什麼時候買的？", "legacy-image"));

        assertThat(context).contains("【LINE 明確引用】[圖片解析結果]",
                "Windows 10/11 專業版");
    }

    private static <T> T inScope(java.util.concurrent.Callable<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.LINE))) {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
