package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubReceiptInterpreter;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.PriceRecordRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 收據閉環整合測試(stub 解析器 + 真實 DB):
 * 照片 → 解析 → 價格入庫(自動連結品項知識庫)→ 價格歷史 API 可查。
 */
class ReceiptFlowTest extends IntegrationTestBase {

    private static final byte[] IMAGE = "fake-image".getBytes();

    @Autowired
    private StubReceiptInterpreter stub;
    @Autowired
    private ReceiptService receiptService;
    @Autowired
    private PriceRecordRepository priceRecordRepository;
    @Autowired
    private ItemRepository itemRepository;

    /** 名稱吻合的品項自動連結;查詢 API 以品名模糊比對撈得到。 */
    @Test
    void receiptIsParsedStoredLinkedAndQueryable() throws Exception {
        Item milk = itemRepository.save(Item.create("鮮奶", Set.of(), Instant.now()));
        stub.nextCommand(new ReceiptCommand("全聯", "2026-07-12", List.of(
                new ReceiptCommand.Line("鮮奶", 95, 1),
                new ReceiptCommand.Line("收據流程雞蛋", 75, 1))));

        ReceiptService.ReceiptResult result = receiptService.handleImage(IMAGE, "image/jpeg");

        assertThat(result.savedCount()).isEqualTo(2);

        List<PriceRecord> milkRecords =
                priceRecordRepository.findByItemNameContainingOrderByPurchasedAtDescIdDesc("鮮奶");
        assertThat(milkRecords).isNotEmpty();
        // 名稱完全吻合 → 連上品項知識庫
        assertThat(milkRecords.get(0).getItemId()).isEqualTo(milk.getId());

        mockMvc.perform(get("/api/price-records").param("itemName", "收據流程雞蛋"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemName").value("收據流程雞蛋"))
                .andExpect(jsonPath("$[0].priceTwd").value(75))
                .andExpect(jsonPath("$[0].storeName").value("全聯"))
                // 知識庫沒有這個品項 → 不連結,但價格照存
                .andExpect(jsonPath("$[0].itemId").doesNotExist());
    }

    /** stub 沒塞回覆 = 模擬 LLM 失敗 → 回覆引導訊息,不入庫、不拋例外。 */
    @Test
    void interpreterFailureStoresNothing() {
        long before = priceRecordRepository.count();

        ReceiptService.ReceiptResult result = receiptService.handleImage(IMAGE, "image/jpeg");

        assertThat(result.savedCount()).isZero();
        assertThat(priceRecordRepository.count()).isEqualTo(before);
    }
}
