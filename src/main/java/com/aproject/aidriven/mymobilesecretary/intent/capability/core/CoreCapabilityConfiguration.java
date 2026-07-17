package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDescriptor;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDomain;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityHandler;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityId;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRisk;
import com.aproject.aidriven.mymobilesecretary.intent.capability.ContextRequirement;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** First typed, validation-only capability set; no side effects are exposed here. */
@Configuration(proxyBeanMethods = false)
public class CoreCapabilityConfiguration {

    @Bean
    public CapabilityHandler<CreateTaskPayload> createTaskCapability() {
        return handler(descriptor(
                CoreCapabilityIds.CREATE_TASK,
                CapabilityDomain.TASK,
                CapabilityRisk.MUTATION,
                CreateTaskPayload.class,
                "建立有期限或單一提醒時間的待辦；不代表占用一段時間的行程",
                Set.of(ContextRequirement.OPEN_TASKS, ContextRequirement.PLACES),
                List.of("提醒我明晚倒垃圾", "新增待辦買牛奶", "明天記得繳費"),
                Set.of("待辦", "提醒", "記得", "要做")),
                CreateTaskPayload.class,
                ignored -> { });
    }

    @Bean
    public CapabilityHandler<CreateSchedulePayload> createScheduleCapability() {
        return handler(descriptor(
                CoreCapabilityIds.CREATE_SCHEDULE,
                CapabilityDomain.SCHEDULE,
                CapabilityRisk.MUTATION,
                CreateSchedulePayload.class,
                "建立有明確開始與結束、會占用時間區間的行程；只有單一時點時應建立待辦",
                Set.of(ContextRequirement.SCHEDULE_WINDOW, ContextRequirement.PLACES),
                List.of("明天下午兩點到三點開會", "週五晚上七點到九點聚餐", "安排十點到十一點看醫生"),
                Set.of("行程", "到", "會議", "預約")),
                CreateSchedulePayload.class,
                CoreCapabilityConfiguration::validateSchedule);
    }

    @Bean
    public CapabilityHandler<AskTaskInfoPayload> askTaskInfoCapability() {
        return handler(descriptor(
                CoreCapabilityIds.ASK_TASK_INFO,
                CapabilityDomain.TASK,
                CapabilityRisk.QUERY,
                AskTaskInfoPayload.class,
                "查詢某個待辦的期限、分類或狀態；不是查詢商品價格",
                Set.of(ContextRequirement.OPEN_TASKS, ContextRequirement.CONVERSATION_HISTORY),
                List.of("繳費待辦的期限是什麼時候", "那個待辦目前什麼狀態", "第二個待辦的詳細資料"),
                Set.of("待辦", "期限", "狀態", "分類", "詳細")),
                AskTaskInfoPayload.class,
                value -> requireTarget(value.title(), value.ordinal()));
    }

    @Bean
    public CapabilityHandler<AskPriceHistoryPayload> askPriceHistoryCapability() {
        return handler(descriptor(
                CoreCapabilityIds.ASK_PRICE_HISTORY,
                CapabilityDomain.KNOWLEDGE,
                CapabilityRisk.QUERY,
                AskPriceHistoryPayload.class,
                "查詢商品過去的購買價格紀錄；不是查詢同名待辦資料",
                Set.of(ContextRequirement.PRICE_HISTORY),
                List.of("牛奶之前買多少錢", "查咖啡的價格紀錄", "衛生紙歷史價格是多少"),
                Set.of("價格", "多少錢", "買過", "歷史")),
                AskPriceHistoryPayload.class,
                ignored -> { });
    }

    @Bean
    public CapabilityHandler<CancelTaskPayload> cancelTaskCapability() {
        return handler(descriptor(
                CoreCapabilityIds.CANCEL_TASK,
                CapabilityDomain.TASK,
                CapabilityRisk.DESTRUCTIVE,
                CancelTaskPayload.class,
                "取消待辦本身；若只要移除地點提醒，不可使用此能力",
                Set.of(ContextRequirement.OPEN_TASKS, ContextRequirement.CONVERSATION_HISTORY),
                List.of("取消買牛奶這個待辦", "第一個待辦不要做了", "刪掉繳費待辦"),
                Set.of("取消", "刪掉", "不要做", "待辦")),
                CancelTaskPayload.class,
                value -> requireTarget(value.title(), value.ordinal()));
    }

    @Bean
    public CapabilityHandler<RemoveTaskPlacePayload> removeTaskPlaceCapability() {
        return handler(descriptor(
                CoreCapabilityIds.REMOVE_TASK_PLACE,
                CapabilityDomain.PLACE,
                CapabilityRisk.DESTRUCTIVE,
                RemoveTaskPlacePayload.class,
                "只移除待辦與地點的提醒綁定，待辦本身必須保留",
                Set.of(ContextRequirement.OPEN_TASKS, ContextRequirement.PLACES,
                        ContextRequirement.CONVERSATION_HISTORY),
                List.of("移除買牛奶在全聯的地點提醒", "不要到公司提醒交報告", "刪除第一個待辦的家裡提醒"),
                Set.of("移除", "地點提醒", "不要到", "綁定")),
                RemoveTaskPlacePayload.class,
                value -> requireTarget(value.taskTitle(), value.ordinal()));
    }

    @Bean
    public CapabilityHandler<SetInventoryPayload> setInventoryCapability() {
        return handler(descriptor(
                CoreCapabilityIds.SET_INVENTORY,
                CapabilityDomain.INVENTORY,
                CapabilityRisk.MUTATION,
                SetInventoryPayload.class,
                "把商品庫存直接設定為指定總數；不是在目前數量上增減",
                Set.of(ContextRequirement.ITEMS),
                List.of("牛奶庫存設為三瓶", "現在有五包衛生紙", "咖啡豆數量改成零"),
                Set.of("庫存設為", "現在有", "總數", "改成")),
                SetInventoryPayload.class,
                ignored -> { });
    }

    @Bean
    public CapabilityHandler<AdjustInventoryPayload> adjustInventoryCapability() {
        return handler(descriptor(
                CoreCapabilityIds.ADJUST_INVENTORY,
                CapabilityDomain.INVENTORY,
                CapabilityRisk.MUTATION,
                AdjustInventoryPayload.class,
                "依目前庫存增加或減少非零數量；不是指定新的庫存總數",
                Set.of(ContextRequirement.ITEMS),
                List.of("牛奶用掉一瓶", "衛生紙增加兩包", "咖啡豆少三包"),
                Set.of("增加", "減少", "用掉", "多", "少")),
                AdjustInventoryPayload.class,
                value -> {
                    if (value.delta() == 0) {
                        throw new IllegalArgumentException("inventory delta must not be zero");
                    }
                });
    }

    @Bean
    public CapabilityHandler<ListSchedulesOnDatePayload> listSchedulesOnDateCapability() {
        return handler(descriptor(
                CoreCapabilityIds.LIST_SCHEDULES_ON_DATE,
                CapabilityDomain.SCHEDULE,
                CapabilityRisk.QUERY,
                ListSchedulesOnDatePayload.class,
                "查詢已解析成特定日期的完整行程總覽，不建立或修改行程",
                Set.of(ContextRequirement.SCHEDULE_WINDOW),
                List.of("昨天有什麼行程", "上週五的行程", "查七月十六日行程"),
                Set.of("行程", "昨天", "哪天", "有什麼")),
                ListSchedulesOnDatePayload.class,
                ignored -> { });
    }

    @Bean
    public CapabilityHandler<ExplainLastFailurePayload> explainLastFailureCapability() {
        return handler(descriptor(
                CoreCapabilityIds.EXPLAIN_LAST_FAILURE,
                CapabilityDomain.CONVERSATION,
                CapabilityRisk.QUERY,
                ExplainLastFailurePayload.class,
                "說明同一對話中上一筆 AI 解析或 Java 驗證失敗的原因",
                Set.of(ContextRequirement.LAST_INTENT_FAILURE),
                List.of("為什麼失敗", "剛剛為什麼沒成功", "上一個指令哪裡有問題"),
                Set.of("為什麼失敗", "沒成功", "剛剛", "原因")),
                ExplainLastFailurePayload.class,
                ignored -> { });
    }

    private static CapabilityDescriptor descriptor(
            CapabilityId id,
            CapabilityDomain domain,
            CapabilityRisk risk,
            Class<?> inputType,
            String description,
            Set<ContextRequirement> contextRequirements,
            List<String> phrases,
            Set<String> keywords) {
        return new CapabilityDescriptor(id, 1, domain, risk, inputType, description,
                contextRequirements, phrases, keywords);
    }

    private static <P> CapabilityHandler<P> handler(
            CapabilityDescriptor descriptor,
            Class<P> inputType,
            Consumer<P> validation) {
        return new ValidationOnlyCapabilityHandler<>(descriptor, inputType, validation);
    }

    private static void validateSchedule(CreateSchedulePayload value) {
        if (!value.endAt().toInstant().isAfter(value.startAt().toInstant())) {
            throw new IllegalArgumentException("schedule endAt must be after startAt");
        }
        boolean recurring = value.recurrence() != null && value.recurrence() != ScheduleItem.Recurrence.NONE;
        if (value.recurrenceUntil() != null && !recurring) {
            throw new IllegalArgumentException("recurrenceUntil requires a recurring schedule");
        }
        if (value.recurrenceUntil() != null
                && value.recurrenceUntil().isBefore(value.startAt().toLocalDate())) {
            throw new IllegalArgumentException("recurrenceUntil must not be before the first occurrence");
        }
    }

    private static void requireTarget(String title, Integer ordinal) {
        if ((title == null || title.isBlank()) && ordinal == null) {
            throw new IllegalArgumentException("task title or ordinal is required");
        }
    }

    private record ValidationOnlyCapabilityHandler<P>(
            CapabilityDescriptor descriptor,
            Class<P> inputType,
            Consumer<P> validation) implements CapabilityHandler<P> {

        @Override
        public void validate(P arguments) {
            validation.accept(arguments);
        }
    }
}
