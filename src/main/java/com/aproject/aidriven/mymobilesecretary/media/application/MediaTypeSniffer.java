package com.aproject.aidriven.mymobilesecretary.media.application;

import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.MediaKind;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates file signatures before image interpretation; opaque Office documents are never opened. */
@Component
public class MediaTypeSniffer {

    private static final Set<String> OFFICE_MEDIA_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public DetectedMedia detect(byte[] bytes, String declaredMediaType) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("EMPTY_MEDIA_FILE", "上傳檔案沒有內容。");
        }
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) {
            return image("image/jpeg");
        }
        if (startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return image("image/png");
        }
        if (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a")) {
            return image("image/gif");
        }
        if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
            return image("image/webp");
        }
        if (ascii(bytes, 0, "%PDF-")) {
            return new DetectedMedia(MediaKind.DOCUMENT, "application/pdf", false);
        }

        String declared = declaredMediaType == null
                ? "" : declaredMediaType.strip().toLowerCase(Locale.ROOT);
        if (startsWith(bytes, 0x50, 0x4b, 0x03, 0x04) && OFFICE_MEDIA_TYPES.contains(declared)) {
            // ZIP is only a container check. We deliberately do not unzip or interpret Office files.
            return new DetectedMedia(MediaKind.DOCUMENT, declared, false);
        }
        throw new BusinessException("UNSUPPORTED_MEDIA_TYPE",
                "檔案內容不是支援的 JPEG、PNG、GIF、WebP、PDF、Word、PowerPoint 或 Excel 格式。");
    }

    private static DetectedMedia image(String mediaType) {
        return new DetectedMedia(MediaKind.IMAGE, mediaType, true);
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        byte[] signature = expected.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (bytes[offset + i] != signature[i]) return false;
        }
        return true;
    }

    public record DetectedMedia(MediaKind kind, String mediaType, boolean interpretableImage) {
    }
}
