package com.aproject.aidriven.mymobilesecretary.intent.application;

/**
 * 收據照片 → 結構化收據。實作:AnthropicReceiptInterpreter(多模態);
 * 測試用 stub 取代,不打真實 LLM。
 */
public interface ReceiptInterpreter {

    /**
     * 解析收據照片。
     *
     * @param imageBytes 圖片二進位
     * @param mimeType   圖片 MIME type(如 image/jpeg)
     */
    ReceiptCommand interpret(byte[] imageBytes, String mimeType);
}
