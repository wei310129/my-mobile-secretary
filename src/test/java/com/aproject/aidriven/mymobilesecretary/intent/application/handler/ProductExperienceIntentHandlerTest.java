package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ProductExperienceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductExperienceIntentHandlerTest {
    @Test
    void explicitCautionCreatesReminderKnowledgeWithoutDiagnosis() {
        ProductExperienceService service = mock(ProductExperienceService.class);
        ProductObservationDraft draft = ProductObservationDraft.create(
                "水泥漆", "得利", "百合白", null, Instant.EPOCH, Instant.MAX);
        String text = "這個油漆害我過敏，以後買油漆提醒我";
        when(service.recordLatest(ProductExperienceService.ExperienceKind.CAUTION, text))
                .thenReturn(new ProductExperienceService.RecordedExperience(draft,
                        mock(ObjectAnnotation.class), ProductExperienceService.ExperienceKind.CAUTION));
        ProductExperienceIntentHandler handler = new ProductExperienceIntentHandler(service);

        IntentResult result = handler.handle(text, command(IntentCommand.Type.RECORD_PRODUCT_CAUTION));

        assertThat(result.action()).isEqualTo(IntentResult.Action.PRODUCT_EXPERIENCE_RECORDED);
        assertThat(result.message()).contains("明確要求").contains("不是醫療診斷");
    }

    @Test
    void arbitraryLabelsDoNotBecomeShoppingReminderByDefault() {
        ProductExperienceService service = mock(ProductExperienceService.class);
        ProductObservationDraft draft = ProductObservationDraft.create(
                "水泥漆", "青葉", "百合白", null, Instant.EPOCH, Instant.MAX);
        List<String> labels = List.of("客廳工程", "第二批色差");
        when(service.recordLatestAnnotation(labels, "這批是客廳第二批色差", false))
                .thenReturn(new ProductExperienceService.RecordedExperience(
                        draft, mock(ObjectAnnotation.class), null));
        IntentOptions options = IntentOptions.empty().withLifeRecord(null, labels, null);
        IntentCommand command = new IntentCommand(IntentCommand.Type.RECORD_PRODUCT_ANNOTATION,
                null, null, null, null, null, null, null, null, null, null, null, null, options);

        IntentResult result = new ProductExperienceIntentHandler(service)
                .handle("這批是客廳第二批色差", command);

        assertThat(result.message()).contains("客廳工程、第二批色差")
                .contains("沒有預設連到購物提醒");
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
