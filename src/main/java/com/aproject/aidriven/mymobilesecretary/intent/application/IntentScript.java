package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;

/**
 * LLM 解析出的一句話 → 一串依序執行的 command。
 *
 * 使用者常一句話講多件事(「取消買排骨,醬油也取消,拿包裹改成今天11點」),
 * 單一 command 裝不下就會整句退回,等於丟資料——所以解析輸出天生是清單,
 * 一件事就是長度 1。
 *
 * @param commands 依講述順序排列;IntentService 逐一驗證執行
 */
public record IntentScript(List<IntentCommand> commands) {

    /** 單一操作的便利包裝(測試與 fallback 用)。 */
    public static IntentScript of(IntentCommand command) {
        return new IntentScript(List.of(command));
    }
}
