package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import org.springframework.stereotype.Service;

/** Explains the external-calendar anti-corruption boundary without mutation. */
@Service
public class CalendarExternalSyncConversationService {

    public IntentResult explain(IntentCommand command) {
        if (command == null
                || command.type()
                        != IntentCommand.Type
                                .EXPLAIN_CALENDAR_EXTERNAL_SYNC) {
            throw new IllegalArgumentException(
                    "External Calendar explanation command is required");
        }
        return new IntentResult(
                IntentResult.Action.CALENDAR_EXTERNAL_SYNC_EXPLAINED,
                "目前可以產生或匯入一次性的 ICS 檔，但尚未直接寫入 iOS 的 Apple Calendar。"
                        + "EventKit 需要未來的 iOS client 取得裝置授權；"
                        + "後端 Calendar 仍是行程的主資料來源，也不會宣稱已完成雙向同步。",
                null,
                null);
    }
}
