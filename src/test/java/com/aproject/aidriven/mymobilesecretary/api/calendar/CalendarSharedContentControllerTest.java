package com.aproject.aidriven.mymobilesecretary.api.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareContentGrantService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.SharedAttachmentContent;
import com.aproject.aidriven.mymobilesecretary.calendar.share.SharedKnowledgeExcerptView;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class CalendarSharedContentControllerTest {

    private final CalendarShareContentGrantService grants =
            mock(CalendarShareContentGrantService.class);
    private final CalendarSharedContentController controller =
            new CalendarSharedContentController(grants);

    @Test
    void imageUsesSanitizedBindingNameAndStrictPrivateHeaders() {
        UUID grantId = UUID.randomUUID();
        byte[] bytes = "private-image".getBytes(StandardCharsets.UTF_8);
        when(grants.readAttachment(grantId))
                .thenReturn(new SharedAttachmentContent(
                        "船票\r\nX-Evil: yes/secret",
                        "image/png",
                        true,
                        bytes));

        var response = controller.attachment(grantId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
        assertThat(response.getHeaders().getCacheControl())
                .contains("private", "no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cross-Origin-Resource-Policy"))
                .isEqualTo("same-origin");
        assertThat(response.getHeaders().getFirst("Referrer-Policy"))
                .isEqualTo("no-referrer");
        String disposition =
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition)
                .startsWith("inline")
                .contains(".png")
                .doesNotContain("\r", "\n", "X-Evil:");
        assertThat(response.getBody().getByteArray()).containsExactly(bytes);
        assertThat(response.toString())
                .doesNotContain(grantId.toString(), "storage", "originalFilename");
    }

    @Test
    void documentIsAttachmentAndUnknownMimeFailsClosed() {
        UUID documentGrant = UUID.randomUUID();
        when(grants.readAttachment(documentGrant))
                .thenReturn(new SharedAttachmentContent(
                        "保險文件",
                        "application/pdf",
                        false,
                        "%PDF".getBytes(StandardCharsets.UTF_8)));
        UUID unknownGrant = UUID.randomUUID();
        when(grants.readAttachment(unknownGrant))
                .thenReturn(new SharedAttachmentContent(
                        "未知內容",
                        "text/html",
                        false,
                        "<script>".getBytes(StandardCharsets.UTF_8)));

        assertThat(controller
                        .attachment(documentGrant)
                        .getHeaders()
                        .getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment")
                .contains(".pdf");
        assertThatThrownBy(() -> controller.attachment(unknownGrant))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void knowledgeResponseContainsOnlyApprovedSnapshotFields() {
        UUID grantId = UUID.randomUUID();
        when(grants.readKnowledgeExcerpt(grantId))
                .thenReturn(new SharedKnowledgeExcerptView(
                        3, "登船摘要", "提前四十分鐘抵達"));

        var response = controller.knowledgeExcerpt(grantId);

        assertThat(response.getBody())
                .isEqualTo(new CalendarSharedContentController
                        .SharedKnowledgeExcerptResponse(
                        3, "登船摘要", "提前四十分鐘抵達"));
        assertThat(response.getHeaders().getCacheControl())
                .contains("private", "no-store");
        assertThat(response.toString())
                .doesNotContain(
                        grantId.toString(),
                        "workspace",
                        "owner",
                        "binding",
                        "sourceId");
    }
}
