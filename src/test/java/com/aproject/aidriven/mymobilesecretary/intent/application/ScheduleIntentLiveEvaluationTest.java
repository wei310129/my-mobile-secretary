package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in, non-mutating evaluation of natural Chinese schedule language against the real model.
 *
 * <p>Run with {@code .\mvnw.cmd -DliveIntentEvaluation=true
 * -Dtest=ScheduleIntentLiveEvaluationTest test}. It calls only the interpretation boundary, so it
 * never creates, changes, or cancels user data. Results are written below {@code target/} for
 * manual field-level review. This is deliberately excluded from ordinary CI because model output
 * and network availability are non-deterministic.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.scheduling.enabled=false",
        "app.intent.enabled=true"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "liveIntentEvaluation", matches = "true")
class ScheduleIntentLiveEvaluationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T05:30:00Z");
    private static final Path REPORT = Path.of("target", "schedule-intent-live-evaluation.md");

    @Autowired
    private AnthropicIntentInterpreter interpreter;

    @Test
    void evaluatesFiftyEverydayScheduleMessagesWithoutMutatingData() throws IOException {
        List<Scenario> scenarios = scenarios();
        StringBuilder report = new StringBuilder("""
                # Schedule intent live evaluation

                Fixed interpretation time: 2026-07-17 13:30 Asia/Taipei

                | # | Category | Message | Expected primary type | Actual interpretation | Result |
                |---:|---|---|---|---|---|
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
            report.append("| ").append(index + 1).append(" | ")
                    .append(cell(scenario.category())).append(" | ")
                    .append(cell(scenario.message())).append(" | ")
                    .append(cell(scenario.expectedLabel())).append(" | ")
                    .append(cell(actual)).append(" | ")
                    .append(pass ? "PASS" : "REVIEW").append(" |\n");
            System.out.printf("LIVE_INTENT_EVAL %02d/50 %s %s -> %s%n",
                    index + 1, pass ? "PASS" : "REVIEW", scenario.message(), actual);
        }
        report.append("\nPrimary-type matches: **").append(matched).append("/50**. ")
                .append("PASS checks command type/count only; dates, duration, title, place, ")
                .append("recurrence scope, and safety still require manual review.\n");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);

        assertThat(scenarios).hasSize(50);
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
            return "%s{title=%s,start=%s,end=%s,place=%s,recurring=%s,recurrence=%s,scope=%s,"
                    .formatted(command.type(), command.title(), command.startAt(), command.endAt(),
                            command.placeName(), command.recurring(), options.recurrence(),
                            options.recurrenceScope())
                    + "duration=" + options.durationMinutes()
                    + ",shift=" + options.shiftMinutes()
                    + ",reference=" + options.referenceTitle()
                    + ",reason=" + command.reason() + "}";
        }).collect(Collectors.joining("; "));
    }

    private static String cell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }

    private static Scenario s(String category, String message, int count,
                              IntentCommand.Type... acceptedTypes) {
        Set<IntentCommand.Type> accepted = Set.copyOf(Arrays.asList(acceptedTypes));
        String expected = accepted.stream().map(Enum::name).sorted()
                .collect(Collectors.joining(" / "));
        if (count != 1) expected += " (" + count + " commands)";
        return new Scenario(category, message, accepted, count, expected);
    }

    private static List<Scenario> scenarios() {
        return List.of(
                s("明確建立", "明天下午2點到3點跟王經理開會", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明確建立", "明晚八點去公館World Gym運動一小時", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明確建立", "7/20 15:00-16:30帶豆豆看牙，地點台大醫院", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("相對時間", "今天下班後去接小孩", 1,
                        IntentCommand.Type.CREATE_RELATIVE_SCHEDULE, IntentCommand.Type.UNKNOWN),
                s("固定行程", "每週六早上十點到十二點送女兒上英文課", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("固定行程", "每個上班日早上七點到晚上七點十五分上班", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("明確建立", "這週五下午三點剪頭髮，抓一個半小時", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("資訊不足", "明天中午跟呂曼菲爸爸吃飯，時間還沒定", 1,
                        IntentCommand.Type.UNKNOWN),
                s("明確建立", "週三晚上七點半線上家長會，大概一小時", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("家庭活動", "明天十點到十二點滬江幼兒園父親節活動", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),

                s("口語錯字", "明早9點開ㄍ會 1hr", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("口語省略", "禮拜五兩點半跟客戶碰一下，先抓四十分鐘", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("缺少指代", "下午三點那個幫我排進去", 1,
                        IntentCommand.Type.UNKNOWN),
                s("缺少指代", "剛剛那個改四點", 1,
                        IntentCommand.Type.UNKNOWN),
                s("口語建立", "明天晚上8點健身啦，應該一個鐘頭", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("資訊不足", "後天早上帶佑佑回診，時間醫院還沒給", 1,
                        IntentCommand.Type.UNKNOWN),
                s("彈性安排", "這週末找個下午去親子樂園", 1,
                        IntentCommand.Type.SUGGEST_FREE_SLOT, IntentCommand.Type.UNKNOWN),
                s("口語補地點", "星期一10-12專案討論，啊地點公司", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("中英混用", "7/25早上九點半到十點半跟小陳1on1", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("相對時間", "明晚吃飯後去倒垃圾", 1,
                        IntentCommand.Type.CREATE_RELATIVE_SCHEDULE, IntentCommand.Type.UNKNOWN),

                s("相對改期", "把簡報排練往後挪到專案週會後面", 1,
                        IntentCommand.Type.CREATE_RELATIVE_SCHEDULE),
                s("相對改期", "明天的健身延後半小時", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("單次改期", "週會改到下午兩點，只有這週", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("整系列改期", "週會之後每週都改星期四十點", 1,
                        IntentCommand.Type.RESCHEDULE_SCHEDULE),
                s("取消單一", "父親節活動取消", 1,
                        IntentCommand.Type.CANCEL_SCHEDULE),
                s("批次取消", "明天所有不是固定的行程都刪掉", 1,
                        IntentCommand.Type.BULK_CANCEL_SCHEDULES),
                s("批次取消", "下週行程清空，固定的留著", 1,
                        IntentCommand.Type.BULK_CANCEL_SCHEDULES),
                s("調整長度", "簡報排練拉長到兩小時", 1,
                        IntentCommand.Type.RESIZE_SCHEDULE),
                s("清單指代", "把剛剛第二個改到公司", 1,
                        IntentCommand.Type.SET_CONTEXT_PLACE, IntentCommand.Type.UNKNOWN),
                s("否決提案", "不要併入上班固定行程", 1,
                        IntentCommand.Type.CANCEL_CONTEXT),

                s("老師通知", "明日是大女兒幼兒園父親節活動，老師提醒明天10:00報到，請穿防水鞋", 1,
                        IntentCommand.Type.UNKNOWN),
                s("老師通知", "明日是大女兒幼兒園父親節活動，老師提醒10:00報到、12:00結束，請穿防水鞋", 1,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("通知確認", "確認老師通知", 1,
                        IntentCommand.Type.ACCEPT_CONTEXT),
                s("通知補充", "父親節活動是12點結束", 1,
                        IntentCommand.Type.UNKNOWN),
                s("通知狀態", "你有把老師的提醒幫我加到行程了嗎？", 1,
                        IntentCommand.Type.ASK_SCHEDULE_INFO),
                s("需求邊界", "這是一個開發需求：排完行程後要重發更新後的總覽", 1,
                        IntentCommand.Type.FEEDBACK),
                s("需求邊界", "你剛剛把需求當行程了，這是功能改善，不要建立行程", 1,
                        IntentCommand.Type.FEEDBACK),
                s("追問缺漏", "怎麼沒有父親節活動", 1,
                        IntentCommand.Type.ASK_SCHEDULE_INFO, IntentCommand.Type.FEEDBACK),
                s("日期查詢", "我明天的行程", 1,
                        IntentCommand.Type.LIST_SCHEDULES_ON_DATE),
                s("日期查詢", "上禮拜五的行程", 1,
                        IntentCommand.Type.LIST_SCHEDULES_ON_DATE),

                s("空檔查詢", "我今天上午有空嗎", 1,
                        IntentCommand.Type.ASK_AVAILABILITY),
                s("衝突查詢", "明天下午三點到五點有沒有撞到", 1,
                        IntentCommand.Type.ASK_AVAILABILITY,
                        IntentCommand.Type.CHECK_SCHEDULE_CONFLICTS),
                s("衝突查詢", "專案週會跟簡報排練是不是重疊", 1,
                        IntentCommand.Type.CHECK_SCHEDULE_CONFLICTS),
                s("負荷查詢", "明天行程排太滿嗎", 1,
                        IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY),
                s("下一行程", "下一個行程是什麼", 1,
                        IntentCommand.Type.ASK_NEXT_SCHEDULE),
                s("空檔查詢", "午餐前有空檔嗎", 1,
                        IntentCommand.Type.ASK_SCHEDULE_GAP),
                s("行程提醒", "運動前30分鐘提醒我", 1,
                        IntentCommand.Type.ADD_SCHEDULE_REMINDER),
                s("一句多件", "明天八點運動，九點洗澡，兩個都幫我排", 2,
                        IntentCommand.Type.CREATE_SCHEDULE),
                s("複雜固定規則", "週五十點開週會，若放假就改週四，颱風停班就順延到下個上班日", 1,
                        IntentCommand.Type.UNKNOWN),
                s("農曆日期", "農曆八月十五晚上跟家人吃飯", 1,
                        IntentCommand.Type.UNKNOWN));
    }

    private record Scenario(String category, String message,
                            Set<IntentCommand.Type> acceptedTypes,
                            int expectedCommandCount, String expectedLabel) {
    }
}
