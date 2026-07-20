package com.aproject.aidriven.mymobilesecretary.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia.MediaKind;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MediaTypeSnifferTest {

    private final MediaTypeSniffer sniffer = new MediaTypeSniffer();

    @Test
    void imageTypeComesFromMagicBytesNotFilenameOrDeclaredHeader() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

        var detected = sniffer.detect(png, "application/octet-stream");

        assertThat(detected.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(detected.mediaType()).isEqualTo("image/png");
        assertThat(detected.interpretableImage()).isTrue();
    }

    @Test
    void fakePngExtensionCannotTurnTextIntoImage() {
        byte[] text = "not really an image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> sniffer.detect(text, "image/png"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void officeContainerIsAcceptedAsOpaqueDocumentAndNeverMarkedInterpretable() {
        byte[] zipHeader = new byte[] {0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4};

        var detected = sniffer.detect(zipHeader,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(detected.kind()).isEqualTo(MediaKind.DOCUMENT);
        assertThat(detected.interpretableImage()).isFalse();
    }
}
