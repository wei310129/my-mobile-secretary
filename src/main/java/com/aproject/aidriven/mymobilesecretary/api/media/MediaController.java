package com.aproject.aidriven.mymobilesecretary.api.media;

import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptService;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.MediaKind;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.SourceType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Private App API for original uploads, recent metadata and actor-authorized content retrieval. */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaStorageService storageService;
    private final ReceiptService receiptService;

    public MediaController(MediaStorageService storageService, ReceiptService receiptService) {
        this.storageService = storageService;
        this.receiptService = receiptService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaMetadataResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "displayName", required = false) String displayName)
            throws IOException {
        StoredMedia saved = storeAppUpload(file, displayName);
        return ResponseEntity.status(HttpStatus.CREATED).body(MediaMetadataResponse.from(saved));
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaAnalysisResponse> uploadAndAnalyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "displayName", required = false) String displayName)
            throws IOException {
        byte[] bytes = file.getBytes();
        StoredMedia saved = storageService.store(SourceType.APP, null,
                uploadDisplayName(displayName, file.getOriginalFilename()),
                file.getOriginalFilename(), file.getContentType(), bytes);
        if (saved.getMediaKind() != MediaKind.IMAGE) {
            return ResponseEntity.status(HttpStatus.CREATED).body(new MediaAnalysisResponse(
                    MediaMetadataResponse.from(saved), "DOCUMENT_STORED_UNINTERPRETED",
                    "文件原檔已安全保存；依規則不開啟或解讀非圖片文件內容。"));
        }
        ReceiptService.ReceiptResult result = receiptService.handleImage(bytes, saved.getMediaType());
        saved = storageService.label(saved.getId(), result.action());
        return ResponseEntity.status(HttpStatus.CREATED).body(new MediaAnalysisResponse(
                MediaMetadataResponse.from(saved), result.action(), result.message()));
    }

    @GetMapping
    public List<MediaMetadataResponse> listRecent() {
        return storageService.listRecent().stream().map(MediaMetadataResponse::from).toList();
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<ByteArrayResource> content(@PathVariable Long id) {
        MediaStorageService.StoredContent content = storageService.read(id);
        StoredMedia metadata = content.metadata();
        String filename = metadata.getOriginalFilename() == null
                ? "media-" + metadata.getId() : metadata.getOriginalFilename();
        ContentDisposition disposition = metadata.getMediaKind() == MediaKind.IMAGE
                ? ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getMediaType()))
                .contentLength(content.bytes().length)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(content.bytes()));
    }

    /** App file manager only. No chat intent routes to this destructive operation. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        storageService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private StoredMedia storeAppUpload(MultipartFile file, String displayName) throws IOException {
        return storageService.store(SourceType.APP, null,
                uploadDisplayName(displayName, file.getOriginalFilename()),
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    private static String uploadDisplayName(String displayName, String originalFilename) {
        return displayName == null || displayName.isBlank() ? originalFilename : displayName;
    }

    public record MediaMetadataResponse(
            Long id,
            String displayName,
            String originalFilename,
            String mediaType,
            String mediaKind,
            String sourceType,
            long sizeBytes,
            Instant createdAt,
            String contentUrl
    ) {
        static MediaMetadataResponse from(StoredMedia media) {
            return new MediaMetadataResponse(
                    media.getId(), media.getDisplayName(), media.getOriginalFilename(),
                    media.getMediaType(), media.getMediaKind().name(), media.getSourceType().name(),
                    media.getSizeBytes(), media.getCreatedAt(),
                    "/api/media/%d/content".formatted(media.getId()));
        }
    }

    public record MediaAnalysisResponse(
            MediaMetadataResponse media,
            String action,
            String message
    ) {
    }
}
