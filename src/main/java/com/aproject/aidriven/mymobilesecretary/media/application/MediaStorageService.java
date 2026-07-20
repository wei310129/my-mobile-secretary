package com.aproject.aidriven.mymobilesecretary.media.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.SourceType;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.Status;
import com.aproject.aidriven.mymobilesecretary.media.persistence.StoredMediaRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Saves originals once, enforces actor quota and resolves bytes only inside actor scope. */
@Service
@Transactional
public class MediaStorageService {

    private static final int MAX_LIST_SIZE = 100;

    private final StoredMediaRepository repository;
    private final MediaObjectStorage objectStorage;
    private final MediaTypeSniffer typeSniffer;
    private final MediaStorageProperties properties;
    private final MediaTagRecorder tagRecorder;
    private final Clock clock;

    public MediaStorageService(StoredMediaRepository repository,
                               MediaObjectStorage objectStorage,
                               MediaTypeSniffer typeSniffer,
                               MediaStorageProperties properties,
                               MediaTagRecorder tagRecorder,
                               Clock clock) {
        this.repository = repository;
        this.objectStorage = objectStorage;
        this.typeSniffer = typeSniffer;
        this.properties = properties;
        this.tagRecorder = tagRecorder;
        this.clock = clock;
    }

    public StoredMedia store(SourceType sourceType, String sourceReference,
                             String displayName, String originalFilename,
                             String declaredMediaType, byte[] bytes) {
        var context = WorkspaceContextHolder.requireContext();
        if (sourceType == null) {
            throw new IllegalArgumentException("media source is required");
        }
        if (sourceReference != null && !sourceReference.isBlank()) {
            Optional<StoredMedia> duplicate = repository
                    .findBySourceTypeAndSourceReferenceAndCreatedByUserIdAndStatus(
                            sourceType, sourceReference.strip(), context.actorId(), Status.AVAILABLE);
            if (duplicate.isPresent()) return duplicate.get();
        }
        MediaTypeSniffer.DetectedMedia detected = typeSniffer.detect(bytes, declaredMediaType);
        enforceLimits(context.actorId(), bytes.length);

        UUID objectId = UUID.randomUUID();
        String storageKey = objectId.toString().substring(0, 2) + "/" + objectId;
        StoredMedia media = StoredMedia.create(
                sourceType, detected.kind(), safeDisplayName(displayName, detected.kind().name()),
                safeFilename(originalFilename), detected.mediaType(), bytes.length, sha256(bytes),
                storageKey, blankToNull(sourceReference), Instant.now(clock));
        StoredMedia saved = repository.saveAndFlush(media);
        objectStorage.put(storageKey, bytes);
        deleteObjectIfTransactionRollsBack(storageKey);
        tagRecorder.stored(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StoredMedia> listRecent() {
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        return repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                actorId, Status.AVAILABLE, PageRequest.of(0, MAX_LIST_SIZE));
    }

    @Transactional(readOnly = true)
    public StoredContent read(Long id) {
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        StoredMedia media = repository.findByIdAndCreatedByUserIdAndStatus(
                        id, actorId, Status.AVAILABLE)
                .orElseThrow(() -> new NotFoundException("StoredMedia", id));
        return new StoredContent(media, objectStorage.read(media.getStorageKey()));
    }

    public StoredMedia label(Long id, String classification) {
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        StoredMedia media = repository.findByIdAndCreatedByUserIdAndStatus(
                        id, actorId, Status.AVAILABLE)
                .orElseThrow(() -> new NotFoundException("StoredMedia", id));
        String label = classificationLabel(classification);
        media.rename("%s｜%s".formatted(media.getDisplayName(), label));
        StoredMedia saved = repository.save(media);
        tagRecorder.classified(saved, label);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StoredMedia> searchRecent(String keyword, String filter) {
        String needle = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        String kind = filter == null ? "" : filter.strip().toUpperCase(Locale.ROOT);
        return listRecent().stream()
                .filter(media -> !"IMAGE".equals(kind) || media.getMediaKind() == StoredMedia.MediaKind.IMAGE)
                .filter(media -> !"DOCUMENT".equals(kind)
                        || media.getMediaKind() == StoredMedia.MediaKind.DOCUMENT)
                .filter(media -> needle.isBlank()
                        || media.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)
                        || media.getOriginalFilename() != null
                        && media.getOriginalFilename().toLowerCase(Locale.ROOT).contains(needle))
                .limit(10)
                .toList();
    }

    public void delete(Long id) {
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        StoredMedia media = repository.findByIdAndCreatedByUserIdAndStatus(
                        id, actorId, Status.AVAILABLE)
                .orElseThrow(() -> new NotFoundException("StoredMedia", id));
        Instant now = Instant.now(clock);
        media.markDeleted(now);
        repository.saveAndFlush(media);
        tagRecorder.deleted(media, now);
        deleteObjectAfterCommit(media.getStorageKey());
    }

    private void enforceLimits(UUID actorId, long incomingBytes) {
        long maxFileBytes = properties.maxFileSize().toBytes();
        if (incomingBytes <= 0 || incomingBytes > maxFileBytes) {
            throw new BusinessException("MEDIA_FILE_TOO_LARGE",
                    "檔案超過單檔安全上限 %d bytes。".formatted(maxFileBytes));
        }
        long quota = properties.defaultActorQuota().toBytes();
        if (quota <= 0) return;
        long used = repository.sumSizeBytesByActorIdAndStatus(actorId, Status.AVAILABLE);
        if (incomingBytes > quota - used) {
            throw new BusinessException("MEDIA_QUOTA_EXCEEDED",
                    "個人原始檔案儲存空間已達上限，請先管理既有檔案或調整方案。");
        }
    }

    private void deleteObjectIfTransactionRollsBack(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        objectStorage.delete(storageKey);
                    } catch (RuntimeException ignored) {
                        // Orphan cleanup can be retried by a future storage reconciliation job.
                    }
                }
            }
        });
    }

    private void deleteObjectAfterCommit(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            objectStorage.delete(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorage.delete(storageKey);
                } catch (RuntimeException ignored) {
                    // Metadata already prevents access and quota accounting; reconciliation removes orphans.
                }
            }
        });
    }

    private static String safeDisplayName(String value, String fallback) {
        String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (safe.isBlank()) safe = fallback;
        return truncate(safe, 255);
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "_").strip();
        return basename.isBlank() ? null : truncate(basename, 255);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : truncate(value.strip(), 255);
    }

    static String classificationLabel(String action) {
        if (action == null) return "未分類圖片";
        if (action.startsWith("TRAVEL_ITINERARY")) return "旅行行程表";
        if (action.startsWith("EVENT_REGISTRATION")) return "活動報名成功";
        if (action.startsWith("EVENT_POSTER")) return "活動海報";
        if (action.startsWith("VENUE_VISIT_INFO")) return "場館參觀資訊";
        if (action.startsWith("MEDICAL_APPOINTMENT")) return "醫療預約文件";
        if (action.startsWith("PAINT_PRODUCT")) return "油漆產品";
        if (action.startsWith("PAYMENT_NOTICE")) return "繳費通知";
        if (action.startsWith("WORK_SCHOOL_SUSPENSION")) return "停班停課圖卡";
        if (action.startsWith("RECEIPT")) return "收據";
        return "未分類圖片";
    }

    private static String truncate(String value, int maximum) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (value.length() <= maximum && encoded.length > 0) return value;
        return value.substring(0, Math.min(maximum, value.length()));
    }

    public record StoredContent(StoredMedia metadata, byte[] bytes) {
    }
}
