package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Instant;

/**
 * 自然語言 → 結構化意圖的介面。
 * 正式實作走 Spring AI + Claude;測試用 stub 取代,整合測試不打真實 LLM。
 */
public interface IntentInterpreter {

    /**
     * 解析使用者的一句話;一句多操作依序回傳多個 command(單操作 = 長度 1)。
     *
     * @param text 使用者原文(打字/之後的 Siri/LINE 轉傳)
     * @param now  現在時間(「明天 11 點」這類相對時間的解析基準)
     * @throws RuntimeException 解析失敗(呼叫端必須 fallback,不得讓核心不可用)
     */
    IntentScript interpret(String text, Instant now);
}
