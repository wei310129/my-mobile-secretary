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

/** Second opt-in batch: 50 schedule utterances sorted by increasing length, capped at 100 chars. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.scheduling.enabled=false",
        "app.intent.enabled=true"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "liveIntentLengthEvaluation", matches = "true")
class ScheduleIntentLengthLiveEvaluationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T05:30:00Z");
    private static final Path REPORT = Path.of(
            "target", "schedule-intent-live-evaluation-051-100.md");

    @Autowired
    private AnthropicIntentInterpreter interpreter;

    @Test
    void evaluatesNextFiftyMessagesInIncreasingLengthUpToOneHundredCharacters()
            throws IOException {
        List<Scenario> scenarios = scenarios().stream()
                .sorted(Comparator.comparingInt(scenario -> scenario.message().length()))
                .toList();
        assertThat(scenarios).hasSize(50);
        assertThat(scenarios).allSatisfy(scenario ->
                assertThat(scenario.message().length()).isLessThanOrEqualTo(100));

        StringBuilder report = new StringBuilder("""
                # Schedule intent live evaluation 051-100

                Fixed interpretation time: 2026-07-17 13:30 Asia/Taipei

                | # | Chars | Message | Expected primary type | Actual interpretation | Result |
                |---:|---:|---|---|---|---|
                """);
        int matched = 0;
        for (int index = 0; index < scenarios.size(); index++) {
            Scenario scenario = scenarios.get(index);
            String actual;
            boolean pass;
            try {
                IntentScript script = interpreter.interpret(
                        scenario.message(), NOW, ConversationSnapshot.empty());
                actual = summarize(script);
                pass = matches(scenario, script);
            } catch (RuntimeException exception) {
                actual = "ERROR: " + exception.getClass().getSimpleName() + ": "
                        + exception.getMessage();
                pass = false;
            }
            if (pass) matched++;
            report.append("| ").append(index + 51).append(" | ")
                    .append(scenario.message().length()).append(" | ")
                    .append(cell(scenario.message())).append(" | ")
                    .append(cell(scenario.expectedLabel())).append(" | ")
                    .append(cell(actual)).append(" | ")
                    .append(pass ? "PASS" : "REVIEW").append(" |\n");
            System.out.printf("LIVE_INTENT_LENGTH %03d/100 chars=%d %s %s -> %s%n",
                    index + 51, scenario.message().length(), pass ? "PASS" : "REVIEW",
                    scenario.message(), actual);
        }
        report.append("\nPrimary-type matches: **").append(matched).append("/50**. ")
                .append("PASS checks command type/count only; field-level review is still required.\n");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
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
                s("明晚七點吃飯", 1, IntentCommand.Type.CREATE_SCHEDULE),
                s("週日十點看牙一小時", 1, IntentCommand.Type.CREATE_SCHEDULE),
                s("下週二下午三點開會", 1, IntentCommand.Type.CREATE_SCHEDULE),
                s("明天九點送女兒上課", 1, IntentCommand.Type.UNKNOWN),
                s("明天九點送兒子去安親班", 1, IntentCommand.Type.UNKNOWN),
                s("明晚八點運動，場地還沒決定", 1,
                        IntentCommand.Type.CREATE_SCHEDULE, IntentCommand.Type.UNKNOWN),
                s("週五下午兩點跟醫生視訊看報告約三十分鐘", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("下週三十點到十一點專案週會，這次線上不用去公司", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("7/28早上九點帶豆豆打預防針，醫院是台大兒童醫院", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天晚上七點跟老婆吃飯，吃完順便去全聯買水果", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("週六十點我送女兒上課，十二點老婆接", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天校車九點接孩子上課，下午四點送回家", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天下午兩點到三點半在公司跟產品組開需求會議", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天下午有空的時候幫我排一小時整理房間", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT, IntentCommand.Type.UNKNOWN),
                s("週六十點我送女兒去英文課，十二點還不知道誰接", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明早八點半送女兒到學校，下午四點由外婆接回新店家裡", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("每週六十點爸爸送女兒去英文課，十二點媽媽接回家", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明天三點開會，若前一場延誤就往後順延半小時", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天十點到十一點開會，開始前十五分鐘提醒我準備資料", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("下週一上午十點客戶會議改到下午兩點，時長仍是一小時", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("這週五的專案週會只改這一次，下週開始仍照每週五十點", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("從下個月開始，每週三晚上七點到八點固定上線上英文課", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("每週一到週五早上八點半站會，做到今年十二月三十一日", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("幫我看明天下午兩點到五點能不能排一個兩小時的訪談", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT,
                        IntentCommand.Type.CHECK_FEASIBILITY),
                s("明天先不要排會議，我只是想知道下午三點到四點有沒有空", 1,
                        IntentCommand.Type.ASK_AVAILABILITY),
                s("我剛剛說錯了，不是星期二，是星期三下午四點去剪頭髮", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("把明天下午的健身改到晚上八點，地點改成公館World Gym", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("取消明天的牙醫，但後天下午三點的復健不要動，還是照常", 1,
                        IntentCommand.Type.CANCEL_SCHEDULE),
                s("下週所有私人行程取消，公司會議和每週固定行程都保留", 1,
                        IntentCommand.Type.BULK_CANCEL_SCHEDULES),
                s("我明天上午有哪些行程，請把固定行程和單次行程一起列出", 1,
                        IntentCommand.Type.LIST_SCHEDULES_ON_DATE),
                s("告訴我明天下午最長的行程，以及它前後各有多少空檔", 1,
                        IntentCommand.Type.ASK_LONGEST_SCHEDULE),
                s("專案週會結束後排一小時簡報練習，地點跟週會一樣", 1,
                        IntentCommand.Type.CREATE_RELATIVE_SCHEDULE),
                s("運動結束後半小時洗澡，洗完再提醒我準備明天上班衣服", 2,
                        IntentCommand.Type.CREATE_RELATIVE_SCHEDULE),
                s("明天九點運動一小時，十點洗澡半小時，十一點去全聯買菜", 3,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("如果明天下雨就提醒我取消公園散步，但不要直接取消行程", 1,
                        IntentCommand.Type.CREATE_WEATHER_REMINDER),
                s("明天早上九點從家裡出發去台大醫院，幫我算最晚幾點要出門", 1,
                        IntentCommand.Type.ASK_DEPARTURE_TIME),
                s("週六下午帶小孩去親子館，時間未定，先找兩個可行的一小時空檔", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT),
                s("這是一個功能需求：有接送小孩的行程時，不能假設送和接都是同一個人", 1,
                        IntentCommand.Type.FEEDBACK),
                s("老師通知明天十點到校、十二點結束，穿防水鞋並帶換洗衣物", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("老師只說明天十點報到，沒有說活動幾點結束，先不要幫我建立行程", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天父親節活動十二點結束，結束後可能和同學家長吃飯但時間還沒定", 2,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE, IntentCommand.Type.UNKNOWN),
                s("明天七點起床，八點送小孩，九點上班；送完後由老婆下午四點接回", 4,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("週五十點固定週會，國定假日改週四，颱風停班則順延到下個上班日", 1,
                        IntentCommand.Type.UNKNOWN),
                s("農曆八月十五晚上家族聚餐，年份是今年，時間先抓晚上六點到九點", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天兩點到三點開會，三點到四點簡報，請檢查是否和既有行程衝突", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("幫我把下週一下午兩點的會議延後一小時，但結束時間也要一起順延", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("明天早上送女兒上課，我送去，放學由老婆接；請先問我確切上下課時間", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天晚上八點運動，若公館World Gym沒開就改在家跳有氧，不要建立兩個行程", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明天下午兩點到三點和王經理開會，地點公司五樓，提前二十分鐘提醒我帶簡報", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("我想安排週末親子活動，但週六或週日都可以，先避開固定行程再給我三個兩小時選項", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT));
    }

    private record Scenario(String message, Set<IntentCommand.Type> acceptedTypes,
                            int expectedCommandCount, String expectedLabel) {
    }
}
