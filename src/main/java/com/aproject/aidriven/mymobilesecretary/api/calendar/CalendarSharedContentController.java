package com.aproject.aidriven.mymobilesecretary.api.calendar;

import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareContentGrantService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.SharedAttachmentContent;
import com.aproject.aidriven.mymobilesecretary.calendar.share.SharedKnowledgeExcerptView;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar/shared-content")
public class CalendarSharedContentController {

    private static final String CORP = "Cross-Origin-Resource-Policy";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final Map<String, MediaProfile> MEDIA_PROFILES = Map.of(
            "image/jpeg", new MediaProfile("jpg", true),
            "image/png", new MediaProfile("png", true),
            "image/gif", new MediaProfile("gif", true),
            "image/webp", new MediaProfile("webp", true),
            "application/pdf", new MediaProfile("pdf", false),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    new MediaProfile("docx", false),
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    new MediaProfile("pptx", false),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    new MediaProfile("xlsx", false));

    private final CalendarShareContentGrantService grants;

    public CalendarSharedContentController(
            CalendarShareContentGrantService grants) {
        this.grants = grants;
    }

    @GetMapping("/{grantId}/attachment")
    public ResponseEntity<ByteArrayResource> attachment(
            @PathVariable UUID grantId) {
        SharedAttachmentContent content = grants.readAttachment(grantId);
        MediaProfile profile = MEDIA_PROFILES.get(content.mediaType());
        if (profile == null || profile.inline() != content.image()) {
            throw new NotFoundException(
                    "Shared calendar attachment", "requested content");
        }
        byte[] bytes = content.bytes();
        String filename = safeFilename(
                content.displayName(), profile.extension());
        ContentDisposition disposition = profile.inline()
                ? ContentDisposition.inline()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                : ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .contentLength(bytes.length)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(CORP, "same-origin")
                .header(REFERRER_POLICY, "no-referrer")
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/{grantId}/knowledge-excerpt")
    public ResponseEntity<SharedKnowledgeExcerptResponse> knowledgeExcerpt(
            @PathVariable UUID grantId) {
        SharedKnowledgeExcerptView excerpt =
                grants.readKnowledgeExcerpt(grantId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(CORP, "same-origin")
                .header(REFERRER_POLICY, "no-referrer")
                .body(new SharedKnowledgeExcerptResponse(
                        excerpt.version(), excerpt.title(), excerpt.text()));
    }

    private static String safeFilename(String displayName, String extension) {
        String safe =
                displayName == null
                        ? ""
                        : displayName.replaceAll("[^\\p{L}\\p{N} ._-]+", "_").strip();
        if (safe.isBlank()) {
            safe = "calendar-attachment";
        }
        if (safe.length() > 120) {
            safe = safe.substring(0, 120);
        }
        String suffix = "." + extension;
        return safe.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)
                ? safe
                : safe + suffix;
    }

    public record SharedKnowledgeExcerptResponse(
            int version, String title, String text) {}

    private record MediaProfile(String extension, boolean inline) {}
}
