package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ProductExperienceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ProductExperienceService.ExperienceKind;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProductExperienceIntentHandler implements IntentHandler {
    private final ProductExperienceService service;

    public ProductExperienceIntentHandler(ProductExperienceService service) {
        this.service = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return Set.of(IntentCommand.Type.RECORD_PRODUCT_USAGE,
                IntentCommand.Type.RECORD_PRODUCT_RECOMMENDATION,
                IntentCommand.Type.RECORD_PRODUCT_CAUTION,
                IntentCommand.Type.RECORD_PRODUCT_ANNOTATION);
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        if (command.type() == IntentCommand.Type.RECORD_PRODUCT_ANNOTATION) {
            return recordGenericAnnotation(text, command);
        }
        ExperienceKind kind = switch (command.type()) {
            case RECORD_PRODUCT_USAGE -> ExperienceKind.USAGE;
            case RECORD_PRODUCT_RECOMMENDATION -> ExperienceKind.RECOMMENDATION;
            case RECORD_PRODUCT_CAUTION -> ExperienceKind.CAUTION;
            default -> throw new IllegalArgumentException("unsupported product experience intent");
        };
        try {
            var recorded = service.recordLatest(kind, text);
            String purpose = switch (kind) {
                case USAGE -> "使用用途";
                case RECOMMENDATION -> "朋友推薦";
                case CAUTION -> "使用後不適警示";
            };
            String followUp = kind == ExperienceKind.CAUTION
                    ? "已依你的明確要求連到購物提醒；這不是醫療診斷。"
                    : "之後可透過商品、品牌或你提供的標籤查詢。";
            return IntentResult.message(IntentResult.Action.PRODUCT_EXPERIENCE_RECORDED,
                    "已把「%s」記為%s。%s".formatted(
                            recorded.draft().displayName(), purpose, followUp));
        } catch (IllegalStateException e) {
            return IntentResult.clarificationNeeded(
                    "目前沒有待註記的商品圖片；請先上傳原圖，再告訴我要加上的註記或標籤。");
        } catch (IllegalArgumentException e) {
            return IntentResult.clarificationNeeded("請明確告訴我這張商品圖片要加上的註記或標籤。");
        }
    }

    private IntentResult recordGenericAnnotation(String text, IntentCommand command) {
        List<String> labels = command.safeOptions().itemNames() == null
                ? List.of() : command.safeOptions().itemNames();
        boolean purchaseReminder = "PURCHASE_REMINDER".equalsIgnoreCase(
                command.safeOptions().referenceKind());
        try {
            var recorded = service.recordLatestAnnotation(labels, text, purchaseReminder);
            String reminder = purchaseReminder
                    ? "並已依你的明確要求連到購物提醒。"
                    : "沒有預設連到購物提醒；之後仍可再新增其他標籤或關係。";
            return IntentResult.message(IntentResult.Action.PRODUCT_EXPERIENCE_RECORDED,
                    "已替「%s」加上標籤：%s；%s".formatted(
                            recorded.draft().displayName(), String.join("、", labels), reminder));
        } catch (IllegalStateException exception) {
            return IntentResult.clarificationNeeded(
                    "目前沒有待註記的商品圖片；請先上傳原圖，再告訴我要加上的註記或標籤。");
        } catch (IllegalArgumentException exception) {
            return IntentResult.clarificationNeeded(
                    "請至少提供一個註記標籤；若要連到購物清單提醒，也請明確說要在購買時提醒。");
        }
    }
}
