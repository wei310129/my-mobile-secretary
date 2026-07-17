package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Third opt-in batch: longer everyday schedule messages, including the pickup safety guard. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.scheduling.enabled=false",
        "app.intent.enabled=true"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "liveIntentProgressiveEvaluation", matches = "true")
class ScheduleIntentProgressiveLiveEvaluationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T05:30:00Z");
    private static final Path REPORT = Path.of(
            "target", "schedule-intent-live-evaluation-101-150.md");

    @Autowired
    private AnthropicIntentInterpreter interpreter;

    @Test
    void evaluatesThirdFiftyMessagesInIncreasingLengthUpToOneHundredCharacters()
            throws IOException {
        List<Scenario> scenarios = scenarios().stream()
                .sorted(Comparator.comparingInt(scenario -> scenario.message().length()))
                .toList();
        assertThat(scenarios).hasSize(50);
        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.message().length()).isGreaterThanOrEqualTo(40);
            assertThat(scenario.message().length()).isLessThanOrEqualTo(100);
        });

        StringBuilder report = new StringBuilder("""
                # Schedule intent live evaluation 101-150

                Fixed interpretation time: 2026-07-17 13:30 Asia/Taipei

                Evaluation includes the deterministic school-pickup clarification guard before the model.

                | # | Chars | Message | Expected primary type | Actual interpretation | Result |
                |---:|---:|---|---|---|---|
                """);
        int matched = 0;
        for (int index = 0; index < scenarios.size(); index++) {
            Scenario scenario = scenarios.get(index);
            Evaluation evaluation = evaluate(scenario);
            if (evaluation.pass()) matched++;
            report.append("| ").append(index + 101).append(" | ")
                    .append(scenario.message().length()).append(" | ")
                    .append(cell(scenario.message())).append(" | ")
                    .append(cell(scenario.expectedLabel())).append(" | ")
                    .append(cell(evaluation.actual())).append(" | ")
                    .append(evaluation.pass() ? "PASS" : "REVIEW").append(" |\n");
            System.out.printf("LIVE_INTENT_PROGRESSIVE %03d/150 chars=%d %s %s -> %s%n",
                    index + 101, scenario.message().length(),
                    evaluation.pass() ? "PASS" : "REVIEW",
                    scenario.message(), evaluation.actual());
        }
        report.append("\nPrimary-type matches: **").append(matched).append("/50**. ")
                .append("PASS checks guard/type/count only; field-level review is still required.\n");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
    }

    private Evaluation evaluate(Scenario scenario) {
        var pickupQuestion = IntentService.schoolPickupClarification(scenario.message());
        if (pickupQuestion.isPresent()) {
            boolean pass = scenario.expectedCommandCount() == 1
                    && scenario.acceptedTypes().contains(IntentCommand.Type.UNKNOWN);
            return new Evaluation("SERVICE_GUARD UNKNOWN{reason=" + pickupQuestion.get() + "}", pass);
        }
        try {
            IntentScript script = interpreter.interpret(
                    scenario.message(), NOW, ConversationSnapshot.empty());
            return new Evaluation(summarize(script), matches(scenario, script));
        } catch (RuntimeException exception) {
            return new Evaluation("ERROR: " + exception.getClass().getSimpleName() + ": "
                    + exception.getMessage(), false);
        }
    }

    private static boolean matches(Scenario scenario, IntentScript script) {
        if (script == null || script.commands() == null
                || script.commands().size() != scenario.expectedCommandCount()) {
            return false;
        }
        IntentCommand first = script.commands().getFirst();
        return first != null && scenario.acceptedTypes().contains(first.type());
    }

    private static String summarize(IntentScript script) {
        if (script == null || script.commands() == null) return "null";
        return script.commands().stream().map(command -> {
            if (command == null) return "null-command";
            IntentOptions options = command.safeOptions();
            return "%s{title=%s,start=%s,end=%s,due=%s,place=%s,recurrence=%s,scope=%s,"
                    .formatted(command.type(), command.title(), command.startAt(), command.endAt(),
                            command.dueAt(), command.placeName(), options.recurrence(),
                            options.recurrenceScope())
                    + "lead=" + options.leadMinutes()
                    + ",duration=" + options.durationMinutes()
                    + ",shift=" + options.shiftMinutes()
                    + ",reference=" + options.referenceTitle()
                    + ",reason=" + command.reason() + "}";
        }).collect(Collectors.joining("; "));
    }

    private static String cell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }

    private static Scenario s(String message, int count, IntentCommand.Type... acceptedTypes) {
        Set<IntentCommand.Type> accepted = Set.copyOf(Arrays.asList(acceptedTypes));
        String expected = accepted.stream().map(Enum::name).sorted()
                .collect(Collectors.joining(" / "));
        if (count != 1) expected += " (" + count + " commands)";
        return new Scenario(message, accepted, count, expected);
    }

    private static List<Scenario> scenarios() {
        return List.of(
                s("明天早上九點我送女兒去英文課，老師說中午十二點下課，但目前還不知道要由誰去接她回家", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天早上九點我送女兒去英文課，中午十二點由老婆接回家，兩段都幫我放進行程並標清楚是誰接送", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("週六早上爸爸送兒子去安親班，下午外婆會接他回新店家裡，可是老師還沒通知確切的上下課時間", 1,
                        IntentCommand.Type.UNKNOWN),
                s("下週一早上八點半媽媽送女兒到學校，下午四點前夫接她去外婆家，請分開建立兩筆接送行程", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天九點校車會到家裡接兒子去上課，放學不是校車送回來，但我還沒確定是老婆還是外婆去接", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天下午兩點到三點在公司開產品會議，四點半到台大醫院回診，兩筆都建立並幫我檢查中間交通是否來得及", 3,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("我想把明天下午兩點到四點拿來做客戶訪談，請先檢查既有行程和交通時間，確認可行後再問我要不要建立", 1,
                        IntentCommand.Type.CHECK_FEASIBILITY),
                s("先不要更動任何行程，我只想知道明天下午一點到六點之間有哪些至少九十分鐘的完整空檔可以使用", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT),
                s("這週五上午十點的專案週會改到下午三點，只改本週這一次，下週起仍維持每週五上午十點", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("從八月第一週開始，把每週三晚上七點到八點設成線上英文課，先持續到今年十二月底再提醒我確認是否續上", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("每週五上午十點固定開週會，如果遇到國定假日就提前到週四，遇到颱風停班則順延到下一個上班日", 1,
                        IntentCommand.Type.UNKNOWN),
                s("如果明天下午四點氣象預報顯示會下大雨，請在兩點提醒我評估是否取消公園散步，不要替我直接取消", 1,
                        IntentCommand.Type.CREATE_WEATHER_REMINDER),
                s("明晚八點本來要去公館健身房運動，如果健身房臨時沒開就改在家跳有氧，現在先不要建立任何一個版本", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天下午的專案會議結束十五分鐘後安排一小時寫會議紀錄，地點沿用會議室，但請先確認專案會議幾點結束", 1,
                        IntentCommand.Type.UNKNOWN),
                s("等我明天運動結束後休息半小時再去洗澡，洗完提醒我準備隔天衣服；目前運動時間和洗澡時間都還沒確定", 1,
                        IntentCommand.Type.UNKNOWN),
                s("取消明天下午的牙醫預約和晚上聚餐，但後天下午三點的復健以及每週固定運動都不要更動", 2,
                        IntentCommand.Type.CANCEL_SCHEDULE),
                s("請把下週一到週五所有單次私人行程取消，公司的會議、孩子接送和任何每週固定行程都必須保留", 1,
                        IntentCommand.Type.BULK_CANCEL_SCHEDULES, IntentCommand.Type.UNKNOWN),
                s("我剛剛講錯日期了，原本說下週二下午四點剪頭髮，其實是下週三下午四點，地點仍是原來那家店", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE, IntentCommand.Type.CREATE_SCHEDULE),
                s("老師通知明天早上十點到校、中午十二點活動結束，家長和孩子都要穿防水鞋並攜帶一套換洗衣物", 1,
                        IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.UNKNOWN),
                s("老師只通知明天早上十點報到，沒有說活動名稱和結束時間，我只是轉貼內容，請先整理缺少的資訊不要建立行程", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天早上七點起床、八點出門送孩子、九點到公司上班，下午四點老婆接孩子，晚上七點全家一起吃飯", 5,
                        IntentCommand.Type.CREATE_TASK, IntentCommand.Type.CREATE_SCHEDULE),
                s("明天下班前要把報價單寄給王經理，這是待辦不是固定時段；如果下午四點還沒完成就再提醒我一次", 2,
                        IntentCommand.Type.CREATE_TASK),
                s("明天下午兩點到三點和王經理開會，地點公司五樓，開始前二十分鐘提醒我帶簡報和轉接頭", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天早上九點要到台大醫院看診，我會從新店家裡開車出發，請依平日交通估算最晚出門時間並保留十五分鐘停車", 1,
                        IntentCommand.Type.ASK_DEPARTURE_TIME),
                s("週日下午想帶兩個小孩去親子館，活動約兩小時，請避開午睡和既有固定行程，先列三個選項不要直接建立", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT),
                s("幫我檢查明天下午兩點到三點的產品會議，是否和既有行程、前後交通以及我設定的準備緩衝時間互相衝突", 1,
                        IntentCommand.Type.CHECK_FEASIBILITY, IntentCommand.Type.CHECK_SCHEDULE_CONFLICTS),
                s("告訴我下週所有行程裡哪一天最忙、最長的一筆是哪一筆，並列出那筆行程前後還各剩多少空檔", 2,
                        IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY, IntentCommand.Type.ASK_LONGEST_SCHEDULE),
                s("把這個月的行程按照地點分組列給我看，公司、家裡和沒有設定地點的行程都要顯示，固定行程也不要漏掉", 1,
                        IntentCommand.Type.GROUP_SCHEDULES_BY_PLACE),
                s("我想在週末安排兩小時親子活動，週六下午或週日上午都可以，請避開既有行程後提供三個可選時段", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT),
                s("明天下午三點開一小時會議，前面要保留二十分鐘準備、後面保留三十分鐘交通，請先檢查整段時間是否可行", 1,
                        IntentCommand.Type.CHECK_FEASIBILITY),
                s("明天午休結束後安排四十五分鐘整理報表，但我的午休結束時間可能會變動，請先問清楚再處理不要自行猜時間", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天晚上七點左右跟朋友吃飯，大概吃兩小時但時間還可能調整，先幫我保留草稿，不要直接建立正式行程", 1,
                        IntentCommand.Type.UNKNOWN),
                s("八月十五日下午兩點到四點參加社區住戶大會，年份是今年，地點在一樓交誼廳，請建立單次行程", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("下週六晚上十一點半開始整理系統資料，預計做到隔天凌晨一點，請正確跨日建立並提前十分鐘提醒", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("下週一台北時間上午九點和倫敦客戶視訊一小時，請在標題保留倫敦客戶，行程時間以我的台北時區建立", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("今年中秋節晚上六點到九點安排家族聚餐，但請先確認政府公告的放假日期，再問我要不要建立不要自行換算", 1,
                        IntentCommand.Type.UNKNOWN),
                s("今年農曆八月十五晚上六點到九點家族聚餐，請先用可靠的農曆轉換確認國曆日期，沒有把握就回問我", 1,
                        IntentCommand.Type.UNKNOWN),
                s("從下個月開始，每個月最後一個上班日下午四點做月結檢查，如果遇到假日要提前，這種規則能設定才建立", 1,
                        IntentCommand.Type.UNKNOWN),
                s("從八月開始每個月第一個星期一上午十點開營運會議，持續到年底，若系統不支援月週次規則就先告訴我", 1,
                        IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.UNKNOWN),
                s("從下週開始每隔一週的星期四晚上七點上瑜伽課，總共六次；如果只能設定每週固定就不要用錯的週期建立", 1,
                        IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.UNKNOWN),
                s("週六早上九點我送女兒到夏恩英語，中午十二點由老婆接她回新店家裡，請各建一筆並保留接送人姓名", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("週六早上九點我要送女兒到夏恩英語上課，中午十二點下課，但到底是我、老婆還是外婆接目前還沒決定", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天下午四點可能由外婆去學校接兒子，也可能臨時改成老婆去，現在請先記錄待確認，不要把任何人當成確定接送人", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天早上八點半校車到家接女兒去學校，下午四點校車送她回家，家長都不用接送，請建立兩段校車行程", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("這是一個功能回饋：看到送孩子上課時，系統一定要追問下課由誰接，不能因為我送就假設也是我接回家", 1,
                        IntentCommand.Type.FEEDBACK),
                s("明天下午兩點到三點開會，但請不要假設會議一定一小時以外的任何緩衝，也不要替我新增沒有說過的交通行程", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("把明天下午兩點的產品會議改到三點，地點也改成線上，原本提前十五分鐘的提醒仍保留不要重複建立", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("明天下午兩點會議的提前提醒從十五分鐘改成三十分鐘，只修改既有提醒，不要再建立另一筆會議或另一個重複提醒", 1,
                        IntentCommand.Type.ADD_SCHEDULE_REMINDER),
                s("明天早上送女兒上課由我負責，但老師還沒說上課時間；下午老婆接回家也還不知道下課時間，請一次問齊再建立", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天早上九點我先送女兒去英文課，接的人還沒決定；下午兩點到三點另外有產品會議，請先處理能確定的部分並追問接送", 1,
                        IntentCommand.Type.UNKNOWN));
    }

    private record Scenario(String message, Set<IntentCommand.Type> acceptedTypes,
                            int expectedCommandCount, String expectedLabel) {
    }

    private record Evaluation(String actual, boolean pass) {
    }
}
