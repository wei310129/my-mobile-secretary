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
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft;
import com.aproject.aidriven.mymobilesecretary.travel.persistence.TravelItineraryDraftRepository;
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
    @Autowired
    private TravelItineraryDraftRepository itineraryDraftRepository;

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

    /** 行程表照片只建立待確認草稿；使用者明確確認後才改為 CONFIRMED。 */
    @Test
    void itineraryImageIsClassifiedDraftedAndExplicitlyConfirmed() throws Exception {
        stub.nextCommand(new ReceiptCommand(null, null, List.of(),
                ReceiptCommand.DocumentType.TRAVEL_ITINERARY, "整合測試郵輪行程",
                List.of(new ReceiptCommand.ItineraryEntry(
                        "11-18", "08:00", "09:00", "靠港", "那霸港", "集合下船")),
                List.of("岸上觀光抽獎"), List.of("護照須隨身攜帶")));

        ReceiptService.ReceiptResult result = receiptService.handleImage(IMAGE, "image/jpeg");

        assertThat(result.action()).isEqualTo("TRAVEL_ITINERARY_DRAFTED");
        assertThat(result.message()).contains("整合測試郵輪行程", "那霸港", "確認匯入行程表");
        assertThat(itineraryDraftRepository.findAll())
                .anyMatch(draft -> draft.getTitle().equals("整合測試郵輪行程")
                        && draft.getStatus() == TravelItineraryDraft.Status.PENDING);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/intent")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"確認匯入行程表\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TRAVEL_ITINERARY_CONFIRMED"));

        assertThat(itineraryDraftRepository.findAll())
                .anyMatch(draft -> draft.getTitle().equals("整合測試郵輪行程")
                        && draft.getStatus() == TravelItineraryDraft.Status.CONFIRMED);
    }
}
