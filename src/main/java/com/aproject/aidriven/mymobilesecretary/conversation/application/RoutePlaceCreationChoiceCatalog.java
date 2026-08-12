package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoice;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceQuestion;
import java.util.List;
import java.util.Set;

/** Single source of truth for Route→Place confirmation actions, labels, effects and answers. */
public final class RoutePlaceCreationChoiceCatalog {

    public static final String SAVE = "SAVE";
    public static final String ONE_TIME = "ONE_TIME";
    public static final String CANCEL = "CANCEL";

    private static final PublicConversationChoiceQuestion USE_PLACE =
            new PublicConversationChoiceQuestion(
                    RoutePlaceCreationDraftService.QUESTION_CODE,
                    "請選擇這個地點的使用方式：",
                    List.of(
                            new PublicConversationChoice(
                                    SAVE,
                                    "儲存為個人地點紀錄",
                                    "之後可再次使用",
                                    Set.of(
                                            "儲存並繼續",
                                            "存起來並繼續",
                                            "儲存",
                                            "存起來",
                                            "儲存地點",
                                            "儲存這個地點",
                                            "保存地點",
                                            "保存這個地點",
                                            "把地點儲存",
                                            "把這個地點儲存",
                                            "把地點保存",
                                            "把這個地點保存",
                                            "把地點存起來",
                                            "把這個地點存起來",
                                            "個人地點紀錄")),
                            new PublicConversationChoice(
                                    ONE_TIME,
                                    "作為本次一次性地點",
                                    "只用於這次路線，不保存到個人地點",
                                    Set.of(
                                            "只用這次",
                                            "這次使用",
                                            "不要儲存",
                                            "不儲存",
                                            "本次一次性地點",
                                            "一次性地點")),
                            new PublicConversationChoice(
                                    CANCEL,
                                    "取消建立地點",
                                    "保留原本路線規劃，返回選擇出發地",
                                    Set.of("取消建立地點", "取消地點"))));

    private RoutePlaceCreationChoiceCatalog() {}

    public static PublicConversationChoiceQuestion usePlace() {
        return USE_PLACE;
    }
}
