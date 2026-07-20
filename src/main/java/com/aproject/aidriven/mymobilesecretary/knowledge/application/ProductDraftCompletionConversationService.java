package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Explicitly converts a pending product-image draft into durable private knowledge. */
@Service
public class ProductDraftCompletionConversationService {
    private final ProductExperienceService productExperienceService;

    public ProductDraftCompletionConversationService(ProductExperienceService productExperienceService) {
        this.productExperienceService = productExperienceService;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (!requestsCompletion(text)) return Optional.empty();
        try {
            beforeMutation.run();
            var recorded = productExperienceService.completeLatestDraft();
            return Optional.of(IntentResult.message(IntentResult.Action.PRODUCT_EXPERIENCE_RECORDED,
                    "已完成草稿，並把「%s」轉成可長期查詢的知識紀錄；原草稿不再等待保留期或提醒設定。"
                            .formatted(recorded.draft().displayName())));
        } catch (IllegalStateException exception) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "目前沒有待完成的商品圖片草稿；請先上傳商品原圖，或重新查詢已存知識紀錄。"));
        }
    }

    private static boolean requestsCompletion(String text) {
        if (text == null || text.isBlank() || !text.contains("草稿")) return false;
        return text.contains("完成") || text.contains("存成知識")
                || text.contains("轉成知識") || text.contains("保存為知識");
    }
}
