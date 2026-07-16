package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderPreferenceRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
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
            輸出是 commands 陣列:一句話只講一件事就輸出 1 個 command;
            一句話包含多個操作(「取消A,B也取消,C改到11點」)就依講述順序輸出多個 command,不可漏掉任何一個。

            判斷規則:
            - 有明確「開始時段」的活動(剪頭髮、開會、聚餐)→ CREATE_SCHEDULE,startAt 必填;
              使用者沒說結束時間就依活動常識估 endAt(剪頭髮約 1 小時、會議約 1 小時);
              聽得出是每週固定(「每週三」「固定行程」)→ recurring 填 true。
            - 接送/陪同類行程要主動拆成完整的配套:「送女兒10點到12點上課」不是一個 10-12 的行程,
              而是兩個 command:CREATE_SCHEDULE「送女兒上課」(到達時間前約 20 分鐘出發到抵達)+
              CREATE_SCHEDULE「接女兒下課」(結束時間起約 20 分鐘),中間時段留空讓使用者能排其他事。
            - 待辦事項(買東西、繳費、聯絡某人)→ CREATE_TASK;有截止時間才填 dueAt。
            - 回報待辦已完成(「牛奶買到了」「電費繳完了」)→ COMPLETE_TASK,title 放該任務的關鍵字(如「牛奶」)。
            - 取消待辦(「取消買排骨」「醬油不用買了」)→ CANCEL_TASK,title 放關鍵字。
            - 一次取消全部待辦(「全部待辦都取消」「清空待辦」)→ CANCEL_ALL_TASKS。
            - 改待辦的期限(「拿包裹改成今天11點」)→ RESCHEDULE_TASK,title 放關鍵字,dueAt 放新期限。
            - 建立地點(「建立地點:X」「幫我把X存起來」)→ CREATE_PLACE,placeName 放地點名。
            - 說某待辦要在哪裡做(「拿包裹是要到蝦皮店到店中興二店」)→ BIND_TASK_PLACE,
              title 放待辦關鍵字,placeName 放地點名;這不是建新待辦!
            - 問某待辦要去哪裡做(「我要去哪取蝦皮?」「包裹在哪拿」)→ ASK_TASK_PLACE,title 放待辦關鍵字。
            - 取消既有行程(「明天的會議取消」)→ CANCEL_SCHEDULE,title 放行程關鍵字。
            - 說某行程是每週固定/取消固定(「送女兒上課是每週固定的」)→ SET_SCHEDULE_RECURRING,
              title 放關鍵字,recurring 填 true(取消固定填 false)。
            - 問某行程的細節(「送女兒上課是固定行程嗎?」「會議是幾點?」)→ ASK_SCHEDULE_INFO,title 放關鍵字。
            - 查某品項買過的價格明細(「列出買奶粉的明細」「鮮奶上次多少錢」)→ ASK_PRICE_HISTORY,
              title 放品項關鍵字(如「奶粉」)。
            - 改既有行程時間(「週會改到下午兩點」)→ RESCHEDULE_SCHEDULE,title 放行程關鍵字,
              startAt 放新開始時間;使用者明講結束時間或時長才填 endAt,否則留空保留原時長。
            - 問某個已知地點的資訊(「全聯是指哪一間?」)→ ASK_PLACE,placeName 放地點名。
            - 查詢待辦清單(「還有什麼要做」「我有哪些待辦」)→ LIST_TASKS。
            - 查詢行程(「今天有什麼行程」「接下來要幹嘛」)→ LIST_SCHEDULES。
            - 問待會/接下來可以「順便、順路」做什麼(「待會有什麼可以順便做」)→ SUGGEST_NEARBY;
              使用者明講時間長度(「看2小時」「未來一小時」)才填 windowHours(小時整數),沒講就留空、不要猜。
            - 回報剛結束行程的實際結果(「準時結束」「會開晚了半小時」「路上塞車遲到20分」)→ RECORD_OUTCOME。
            - 對系統本身的抱怨、質疑、建議(「你是不是重複建立了」「你沒問我地點」)→ FEEDBACK,
              不要回 UNKNOWN,這些話要記錄給開發者。
            - 聽不懂、或缺關鍵資訊無法決定 → UNKNOWN,reason 用繁體中文說明缺什麼。

            欄位規則:
            - title:動作本體,去掉時間與地點詞(「明天11點在台北剪頭髮」→「剪頭髮」)。
            - 完成/取消/改期的 title 關鍵字必須保留原文語言與拼寫,不可翻譯:
              使用者的待辦叫「Buy soy sauce」,關鍵字就是「soy sauce」,不是「醬油」。
            - 時間一律輸出 ISO-8601 含時區,例如 2026-07-13T11:00:00+08:00;相對時間(明天、下週六)以 user 提供的現在時間換算。
            - placeName:使用者明講的地點才填;若與已知地點清單中某項明顯是同一個,用清單中的名稱。
              若與清單某項「相近但不確定」(可能是筆誤,如「夏印尼」vs「夏恩英語」)→ 不要硬猜,
              回 UNKNOWN 且 reason 寫「你是說『夏恩英語』嗎?」這種確認句。
            - CREATE_PLACE 的 placeName 可包含分店/地區資訊(「夏恩英語 新店七張分校」),查得更準。
            - priority:CREATE_TASK 聽得出急迫(趕快、務必、很急)才填 HIGH,否則 NORMAL;
              UPDATE_TASK 只有使用者明講要改優先級時才填,沒講必須留空。
            - RECORD_OUTCOME:onTime 準時為 true;超時填 overrunMinutes(分鐘,「半小時」=30);
              outcomeReason 聽得出原因才填:會議/活動拖延=MEETING_OVERRUN、交通事故/意外=TRAFFIC_INCIDENT、
              上下班尖峰塞車=RUSH_HOUR、其他=OTHER。
            """;

    private static final String LIFESTYLE_RULES = """

            生活化對話擴充規則:
            - 你會收到短期上下文、未完成待辦、近期行程、已知地點與購物品項。只有資料能唯一指向時,
              才能解析「上一個、第二個、那件事、她」;否則輸出 UNKNOWN 回問。
            - options 可填:filter、ordinal、durationMinutes、leadMinutes、radiusMeters、triggerType、
              recurrence、category、itemNames、quantity、referenceTitle、referenceKind、timeOfDay、
              keepTime、shiftMinutes、condition、fromPlaceName、bufferMinutes、clarificationQuestion、alias。
              第二波欄位還有 newTitle、description、quietStart、quietEnd、allowHighPriority。
            - CREATE_TASK 可同時填 dueAt、placeName 與 options。重複提醒填 options.recurrence;
              天氣條件提醒用 CREATE_WEATHER_REMINDER,不要把「如果」忽略。
            - 「今天有什麼事」是 LIST_AGENDA+filter TODAY,不能退化成列全部未完成待辦。
            - 序號操作一定填 options.ordinal;省略名稱的承接操作不要自行虛構 title。
            - 純致謝、結束語輸出 SOCIAL;抱怨輸出 FEEDBACK,絕不可 fallback 建成待辦。
            - 修改待辦名稱／備註／分類／優先級用 UPDATE_TASK。只有使用者明講優先級時才填 priority;
              改名填 options.newTitle,備註填 options.description,不可誤建新待辦。
            - 固定提醒可用 PAUSE_RECURRING_TASK、RESUME_RECURRING_TASK、SKIP_RECURRING_OCCURRENCE;
              「暫停」不是取消整條規則,「這次跳過」也不是取消。
            - 已買到購物品項用 MARK_SHOPPING_PURCHASED;明確說全部買完或清空才用 CLEAR_SHOPPING_LIST。
            - 庫存絕對值用 SET_INVENTORY;「用掉／補進」是 ADJUST_INVENTORY,title 放品項,
              options.quantity 放帶正負號的變化量。買到且明講數量時 MARK_SHOPPING_PURCHASED 的 quantity 是增加量。
            - 問品項哪裡買用 ASK_ITEM_PLACES;記住品項與店家關係用 BIND_ITEM_PLACE,
              這和把待辦綁 geofence 的 BIND_TASK_PLACE 不同。
            - 「快用完」只根據已盤點且仍大於 0 的數量;LIST_INVENTORY/RESTOCK_LOW_INVENTORY
              用 options.quantity 當上限,不可把從未盤點的 0 猜成缺貨。
            - 每日固定勿擾用 SET_QUIET_HOURS,quietStart/quietEnd 填 HH:mm;
              臨時暫停到某時用 MUTE_REMINDERS 並把恢復時間放 dueAt。RESUME_REMINDERS 只取消臨時靜音,
              CLEAR_QUIET_HOURS 才取消每日固定時段。allowHighPriority 依使用者是否允許緊急提醒。
            - 查某地會觸發哪些待辦用 ASK_PLACE_TASKS;查單一待辦完整地點規則用 ASK_TASK_GEOFENCE。
              改半徑／ENTER／EXIT 用 UPDATE_TASK_GEOFENCE;只移除地點提醒、保留待辦用 REMOVE_TASK_PLACE,
              絕不可誤判成 CANCEL_TASK。沒有唯一規則時輸出 UNKNOWN 回問方向。
            - 問下一個行程與倒數用 ASK_NEXT_SCHEDULE;問兩行程純時間間隔用 ASK_SCHEDULE_GAP。
              按日期整理用 GROUP_SCHEDULES_BY_DAY;找已確認行程重疊用 CHECK_SCHEDULE_CONFLICTS。
              LIST_SCHEDULES 可搭配 WEEKEND、MORNING、AFTERNOON、EVENING、WITH_PLACE、NO_PLACE filter。
            - 問「現在先做什麼／最急的是什麼」用 SUGGEST_NEXT_TASK;只問某分類時填 options.category。
              按分類整理或統計用 GROUP_TASKS_BY_CATEGORY;問今日／本週完成進度用 ASK_TASK_PROGRESS,
              今日填 filter=TODAY、本週填 filter=WEEK。LIST_TASKS 可搭配 HIGH_AND_DUE、RECURRING、
              PAUSED_RECURRING、STALE、MONTH、NEXT_MONTH filter;這些都是查詢,不可改動待辦。
            - 問待辦依期限分布用 GROUP_TASKS_BY_DUE;問今天或未來三天還有幾件用 ASK_TASK_LOAD,
              未來三天填 filter=NEXT_3_DAYS。問未來七天哪天到期待辦最多用 ASK_BUSY_TASK_DAY。
            - 行程只改長度／結束時間用 RESIZE_SCHEDULE;durationMinutes 是新總時長,
              shiftMinutes 是結束時間增減分鐘(縮短可為負數)。
            - 下方能力目錄是規範性 few-shot。A+B 代表輸出兩個 command,不是不存在的 type;
              RECEIPT_IMAGE 表示文字 intent 不處理圖片;FOLLOW_UP 表示依上下文輸出實際待補的 command。
            """;

    private final ChatClient chatClient;
    private final PlaceRepository placeRepository;
    private final TaskRepository taskRepository;
    private final ScheduleItemRepository scheduleRepository;
    private final ItemRepository itemRepository;
    private final ReminderPreferenceRepository reminderPreferenceRepository;
    private final String capabilityCatalog;

    public AnthropicIntentInterpreter(ChatModel chatModel, PlaceRepository placeRepository,
                                      TaskRepository taskRepository,
                                      ScheduleItemRepository scheduleRepository,
                                      ItemRepository itemRepository,
                                      ReminderPreferenceRepository reminderPreferenceRepository) {
        this.chatClient = ChatClient.create(chatModel);
        this.placeRepository = placeRepository;
        this.taskRepository = taskRepository;
        this.scheduleRepository = scheduleRepository;
        this.itemRepository = itemRepository;
        this.reminderPreferenceRepository = reminderPreferenceRepository;
        this.capabilityCatalog = readCapabilityCatalog();
    }

    @Override
    public IntentScript interpret(String text, Instant now) {
        return interpret(text, now, ConversationSnapshot.empty());
    }

    @Override
    public IntentScript interpret(String text, Instant now, ConversationSnapshot context) {
        // 已知地點清單給 LLM 做名稱正規化(「萬家福」vs「新店萬家福」)
        String knownPlaces = placeRepository.findAll().stream()
                .map(Place::getName)
                .collect(Collectors.joining("、"));
        String nowTaipei = ZonedDateTime.ofInstant(now, TAIPEI)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));
        String openTasks = taskRepository.findByStatusIn(java.util.EnumSet.of(
                        TaskStatus.CREATED, TaskStatus.SCHEDULED, TaskStatus.REMINDED, TaskStatus.ESCALATED))
                .stream().map(task -> "%d:%s%s".formatted(task.getId(), task.getTitle(),
                        task.getDueAt() == null ? "" : "@" + task.getDueAt()))
                .collect(Collectors.joining("、"));
        String schedules = scheduleRepository.findByStatusInOrderByStartAtAsc(java.util.EnumSet.of(
                        ScheduleStatus.PROPOSED, ScheduleStatus.CONFIRMED, ScheduleStatus.PENDING))
                .stream().limit(30).map(item -> "%d:%s@%s".formatted(
                        item.getId(), item.getTitle(), item.getStartAt()))
                .collect(Collectors.joining("、"));
        String shopping = itemRepository.findAll().stream()
                .filter(item -> item.isShoppingNeeded() || item.getInventoryQuantity() > 0)
                .map(item -> "%s(庫存%d%s)".formatted(item.getName(), item.getInventoryQuantity(),
                        item.isShoppingNeeded() ? ",待買" : ""))
                .collect(Collectors.joining("、"));
        String reminderPreference = reminderPreferenceRepository.findById(1)
                .map(p -> "勿擾=%s-%s,緊急例外=%s,靜音到=%s".formatted(
                        p.getQuietStart(), p.getQuietEnd(), p.isAllowHighPriority(), p.getMutedUntil()))
                .orElse("(無)");

        return chatClient.prompt()
                .system(SYSTEM_PROMPT + LIFESTYLE_RULES + "\n能力目錄:\n" + capabilityCatalog)
                .user("""
                        現在時間(台北):%s
                        已知地點:%s
                        未完成待辦:%s
                        可操作行程:%s
                        物品狀態:%s
                        提醒偏好:%s
                        短期上下文:%s

                        使用者說:%s
                        """.formatted(nowTaipei, knownPlaces.isBlank() ? "(無)" : knownPlaces,
                        openTasks.isBlank() ? "(無)" : openTasks,
                        schedules.isBlank() ? "(無)" : schedules,
                        shopping.isBlank() ? "(無)" : shopping,
                        reminderPreference, context, text))
                .call()
                .entity(IntentScript.class);
    }

    private static String readCapabilityCatalog() {
        try {
            return new ClassPathResource("conversation-capabilities.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("conversation capability catalog missing", e);
        }
    }
}
