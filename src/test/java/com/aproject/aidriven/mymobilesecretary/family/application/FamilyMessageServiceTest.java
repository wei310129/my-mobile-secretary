package com.aproject.aidriven.mymobilesecretary.family.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyNoticeDraft;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyNoticeDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FamilyMessageServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T06:00:00Z");
    private final FamilyNoticeDraftRepository repository = mock(FamilyNoticeDraftRepository.class);
    private final UserKnowledgeService knowledgeService = mock(UserKnowledgeService.class);
    private final PlaceAliasService placeAliasService = mock(PlaceAliasService.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final TaskService taskService = mock(TaskService.class);
    private WorkspaceContextHolder.Scope scope;
    private FamilyMessageService service;

    @BeforeEach
    void setUp() {
        scope = WorkspaceContextHolder.open(new WorkspaceContext(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("10000000-0000-0000-0000-000000000101"),
                WorkspaceChannel.LINE));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new FamilyMessageService(repository, knowledgeService, placeAliasService,
                scheduleService, taskService, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
        when(repository.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        scope.close();
    }

    @Test
    void teacherNoticeCreatesConfirmationDraftWithoutCallingAiOrCreatingSchedule() {
        String notice = """
                明日是大女兒幼兒園的父親節活動，以下是老師的提醒訊息：

                @All 親愛的家長，您好
                1.明天小朋友穿運動服
                2.請家長們明天10:00報到
                3.明天早上彩虹門會開喔！
                4.滬江高中停車位有限，請儘量搭乘大眾運輸工具
                5.明天早上的活動風雨無阻
                6.明天大人小孩都要帶換洗衣物，請穿防水鞋
                7.今天帶被子、室內鞋、漱口杯及牙刷回去清洗，星期一帶回來
                """;
        AtomicInteger mutations = new AtomicInteger();

        IntentResult result = service.answer(notice, mutations::incrementAndGet).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FAMILY_NOTICE_DRAFTED);
        assertThat(result.message())
                .contains("父親節活動")
                .contains("2026-07-18")
                .contains("10:00")
                .contains("活動結束時間")
                .contains("換洗衣物")
                .contains("大眾運輸");
        assertThat(mutations).hasValue(1);
        verify(repository).save(any(FamilyNoticeDraft.class));
        verify(scheduleService, never()).createSchedule(any(), any(), any(), any());
        verify(taskService, never()).createTask(any(), any(), any(), any());
    }

    @Test
    void plainTeacherNotificationAlsoCreatesConfirmationDraft() {
        String notice = "老師通知明天十點到校、十二點結束，穿防水鞋並帶換洗衣物";

        IntentResult result = service.answer(notice, () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FAMILY_NOTICE_DRAFTED);
        assertThat(result.message()).contains("10:00").contains("12:00").contains("換洗衣物");
        verify(repository).save(any(FamilyNoticeDraft.class));
        verify(scheduleService, never()).createSchedule(any(), any(), any(), any());
    }

    @Test
    void explicitRelationshipIsRememberedAndAcknowledged() {
        String text = "我是我女兒的家長，也是我女兒的爸爸，這層關係你能理解嗎？";

        IntentResult result = service.answer(text, () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.KNOWLEDGE_SAVED);
        assertThat(result.message()).contains("家長").contains("爸爸");
        verify(knowledgeService).remember(eq(Category.RELATIONSHIP),
                eq("我與女兒的關係"), any());
    }

    @Test
    void gateGuidanceAndTeacherInterpretationPreferenceAreBothRemembered() {
        Place school = Place.create("滬江幼兒園", null, 24.98967, 121.53958,
                "學校", NOW);
        when(placeAliasService.resolve("滬江")).thenReturn(Optional.of(school));
        String text = """
                滬江的彩虹門是位於「景美便堤」的後門，專門給開車接送家長使用。
                如果你聽不懂老師的訊息你要問我，我會告訴你。

                老師說大人小孩都要帶換洗衣物，以及穿防水鞋，你有理解嗎？
                """;

        IntentResult result = service.answer(text, () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FAMILY_NOTICE_DRAFTED);
        assertThat(result.message()).contains("接送入口").contains("先問你").contains("換洗衣物");
        verify(knowledgeService).remember(eq(Category.PLACE_GUIDANCE),
                eq("滬江幼兒園"), any());
        verify(knowledgeService).remember(eq(Category.INTERPRETATION_PREFERENCE),
                eq("老師通知理解方式"), any());
    }

    @Test
    void askingWhetherNoticeWasAddedAnswersFromDraftStateWithoutAi() {
        IntentResult result = service.answer("你有把老師的提醒幫我加到行程了嗎？",
                () -> { }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.FAMILY_NOTICE_STATUS);
        assertThat(result.message()).contains("沒有等待確認").contains("原始訊息再貼一次");
    }
}
