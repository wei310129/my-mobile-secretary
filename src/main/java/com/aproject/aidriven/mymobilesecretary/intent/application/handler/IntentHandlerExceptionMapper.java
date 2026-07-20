package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;

/** Preserves the legacy lifestyle-command clarification contract during handler migration. */
final class IntentHandlerExceptionMapper {

    private IntentHandlerExceptionMapper() {
    }

    static IntentResult clarification(IllegalArgumentException exception) {
        String detail = exception.getMessage();
        if (detail != null && detail.contains("current location")) {
            return IntentResult.clarificationNeeded("我還不知道你目前的位置,先傳位置給我才能估算。");
        }
        if (detail != null && detail.contains("unknown destination")) {
            return IntentResult.clarificationNeeded("我找不到目的地,請說完整地點名稱或先建立地點。");
        }
        if (detail != null && detail.contains("not unique")) {
            return IntentResult.clarificationNeeded("有不只一筆符合,請再補日期、時間或完整名稱,我才不會改錯。");
        }
        if (detail != null && detail.contains("context")) {
            return IntentResult.clarificationNeeded("目前沒有可承接的上一筆內容,請直接說待辦或行程名稱。");
        }
        return IntentResult.clarificationNeeded("這句還缺少可執行的資訊,請補上名稱、日期時間或地點。");
    }
}
