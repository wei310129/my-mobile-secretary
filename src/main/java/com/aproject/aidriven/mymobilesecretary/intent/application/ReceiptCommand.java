package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;

/**
 * LLM 從收據照片解析出的結構化結果(AI 五層第 1 層的輸出)。
 * 與 IntentCommand 相同原則:LLM 輸出不直接信任,ReceiptService 逐欄驗證後才落地。
 *
 * @param storeName   店名(看得出來才填)
 * @param purchasedAt 消費日期 yyyy-MM-dd(看得出來才填;沒有就當今天)
 * @param items       品項清單(單價為台幣整數;讀不出價格的品項不要編)
 */
public record ReceiptCommand(
        String storeName,
        String purchasedAt,
        List<Line> items
) {

    /**
     * 收據上的一行品項。
     *
     * @param name     品項名
     * @param price    單價(台幣,整數)
     * @param quantity 數量(沒印就 1)
     */
    public record Line(String name, Integer price, Integer quantity) {
    }
}
