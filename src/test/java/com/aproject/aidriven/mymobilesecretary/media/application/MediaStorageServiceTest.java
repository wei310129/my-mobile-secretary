package com.aproject.aidriven.mymobilesecretary.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.SourceType;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.Status;
import com.aproject.aidriven.mymobilesecretary.media.persistence.StoredMediaRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class MediaStorageServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    @Test
    void validImageIsHashedAndWrittenBehindGeneratedStorageKey() {
        StoredMediaRepository repository = mock(StoredMediaRepository.class);
        MediaObjectStorage storage = mock(MediaObjectStorage.class);
        when(repository.findBySourceTypeAndSourceReferenceAndCreatedByUserIdAndStatus(
                SourceType.LINE, "line-message-1", ACTOR, Status.AVAILABLE))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(StoredMedia.class))).thenAnswer(call -> call.getArgument(0));
        MediaStorageService service = service(repository, storage, DataSize.ofMegabytes(1));

        StoredMedia saved = inScope(() -> service.store(
                SourceType.LINE, "line-message-1", "LINE 圖片", "../unsafe.png",
                "application/octet-stream", PNG));

        assertThat(saved.getMediaType()).isEqualTo("image/png");
        assertThat(saved.getOriginalFilename()).isEqualTo("unsafe.png");
        assertThat(saved.getSha256()).hasSize(64);
        assertThat(saved.getStorageKey()).matches("[a-f0-9]{2}/[a-f0-9-]{36}");
        verify(storage).put(saved.getStorageKey(), PNG);
    }

    @Test
    void actorQuotaIsCheckedBeforeMetadataOrBytesAreWritten() {
        StoredMediaRepository repository = mock(StoredMediaRepository.class);
        MediaObjectStorage storage = mock(MediaObjectStorage.class);
        when(repository.sumSizeBytesByActorIdAndStatus(ACTOR, Status.AVAILABLE)).thenReturn(5L);
        MediaStorageService service = service(repository, storage, DataSize.ofBytes(10));

        assertThatThrownBy(() -> inScope(() -> service.store(
                SourceType.APP, null, "quota", "quota.png", "image/png", PNG)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MEDIA_QUOTA_EXCEEDED");
        verify(repository, never()).saveAndFlush(any());
        verify(storage, never()).put(anyString(), any());
    }

    @Test
    void paymentNoticeClassificationBecomesSearchableMediaTag() {
        assertThat(MediaStorageService.classificationLabel("PAYMENT_NOTICE_CAPTURED"))
                .isEqualTo("繳費通知");
    }

    private static MediaStorageService service(StoredMediaRepository repository,
                                               MediaObjectStorage storage,
                                               DataSize quota) {
        return new MediaStorageService(
                repository, storage, new MediaTypeSniffer(),
                new MediaStorageProperties("ignored", DataSize.ofMegabytes(1), quota),
                mock(MediaTagRecorder.class),
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));
    }

    private static <T> T inScope(java.util.concurrent.Callable<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST))) {
            try {
                return action.call();
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new IllegalStateException(checked);
            }
        }
    }
}
