package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring AI + Claude 的意圖解析器。
 *
 * 鐵律(architecture.md §8):LLM 只做「理解」——把話轉成結構化 command。
 * 可行性驗算、排程、綁定全部在確定性 Java 程式,LLM 的輸出僅供 IntentService 驗證後執行。
 *
 * 提示詞快取考量:system prompt 固定不變;會變的(現在時間、地點清單、使用者原文)全放 user message。
 */
@Component
@ConditionalOnProperty(prefix = "app.intent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnthropicIntentInterpreter implements IntentInterpreter {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private static final String SYSTEM_PROMPT = """
            你是個人行程秘書的意圖解析器。把使用者的一句話解析成結構化意圖,只輸出符合 schema 的 JSON。

            判斷規則:
            - 有明確「開始時段」的活動(剪頭髮、開會、聚餐)→ CREATE_SCHEDULE,startAt 必填;
              使用者沒說結束時間就依活動常識估 endAt(剪頭髮約 1 小時、會議約 1 小時)。
            - 待辦事項(買東西、繳費、聯絡某人)→ CREATE_TASK;有截止時間才填 dueAt。
            - 回報待辦已完成(「牛奶買到了」「電費繳完了」)→ COMPLETE_TASK,title 放該任務的關鍵字(如「牛奶」)。
            - 查詢待辦清單(「還有什麼要做」「我有哪些待辦」)→ LIST_TASKS。
            - 查詢行程(「今天有什麼行程」「接下來要幹嘛」)→ LIST_SCHEDULES。
            - 問待會/接下來可以「順便、順路」做什麼(「待會有什麼可以順便做」)→ SUGGEST_NEARBY。
            - 回報剛結束行程的實際結果(「準時結束」「會開晚了半小時」「路上塞車遲到20分」)→ RECORD_OUTCOME。
            - 聽不懂、或缺關鍵資訊無法決定 → UNKNOWN,reason 用繁體中文說明缺什麼。

            欄位規則:
            - title:動作本體,去掉時間與地點詞(「明天11點在台北剪頭髮」→「剪頭髮」)。
            - 時間一律輸出 ISO-8601 含時區,例如 2026-07-13T11:00:00+08:00;相對時間(明天、下週六)以 user 提供的現在時間換算。
            - placeName:使用者明講的地點才填;若與已知地點清單中某項明顯是同一個,用清單中的名稱。不要猜。
            - priority:聽得出急迫(趕快、務必、很急)才填 HIGH;否則 NORMAL。
            - RECORD_OUTCOME:onTime 準時為 true;超時填 overrunMinutes(分鐘,「半小時」=30);
              outcomeReason 聽得出原因才填:會議/活動拖延=MEETING_OVERRUN、交通事故/意外=TRAFFIC_INCIDENT、
              上下班尖峰塞車=RUSH_HOUR、其他=OTHER。
            """;

    private final ChatClient chatClient;
    private final PlaceRepository placeRepository;

    public AnthropicIntentInterpreter(ChatModel chatModel, PlaceRepository placeRepository) {
        this.chatClient = ChatClient.create(chatModel);
        this.placeRepository = placeRepository;
    }

    @Override
    public IntentCommand interpret(String text, Instant now) {
        // 已知地點清單給 LLM 做名稱正規化(「萬家福」vs「新店萬家福」)
        String knownPlaces = placeRepository.findAll().stream()
                .map(Place::getName)
                .collect(Collectors.joining("、"));
        String nowTaipei = ZonedDateTime.ofInstant(now, TAIPEI)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("""
                        現在時間(台北):%s
                        已知地點:%s

                        使用者說:%s
                        """.formatted(nowTaipei, knownPlaces.isBlank() ? "(無)" : knownPlaces, text))
                .call()
                .entity(IntentCommand.class);
    }
}
