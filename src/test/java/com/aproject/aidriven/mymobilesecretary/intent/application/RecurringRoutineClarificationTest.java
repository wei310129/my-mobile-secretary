package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecurringRoutineClarificationTest {
    @Test
    void recordedWorkdayRoutineGetsSpecificQuestionsInsteadOfFeedback() {
        String text = "我每個上班日都要上班，早上七點起床，送小孩後去公司，這樣能幫我記得我的日常行程安排嗎？";

        assertThat(IntentService.recurringRoutineClarification(text))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("不會把這段當成一般回饋", "週一到週五", "最晚幾點要到", "下雨"));
        assertThat(IntentService.recurringRoutineClarification("明天十點開會")).isEmpty();
    }
}
