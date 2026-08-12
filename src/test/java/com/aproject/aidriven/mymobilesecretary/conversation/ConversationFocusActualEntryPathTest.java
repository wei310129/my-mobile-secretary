package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusContributorRegistry;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusTargetResolver;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationFocusActualEntryPathTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intentService;
    @Autowired private TaskService taskService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private TravelItineraryDraftService itineraryDraftService;
    @Autowired private ItemService itemService;
    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationFocusContributorRegistry contributors;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void explicitTaskMutationResumesItsSuspendedResourceAcrossDomainsThenKeepsIt() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            Task task = taskService.createTask("整理報稅附件", null, TaskPriority.NORMAL, null);
            var schedule = scheduleService.createSchedule("牙醫回診",
                    Instant.parse("2026-09-10T02:00:00Z"),
                    Instant.parse("2026-09-10T03:00:00Z"), null).item();
            focusService.enterResource("TASK", "task:" + task.getId(), task.getTitle(), hmac('a'));
            focusService.switchResource("SCHEDULE", "schedule:" + schedule.getId(),
                    schedule.getTitle(), hmac('b'));

            String firstText = "把整理報稅附件改叫核對報稅附件";
            IntentResult first = handle(firstText, command(IntentCommand.Type.UPDATE_TASK,
                    "整理報稅附件", null, null, null, null,
                    IntentOptions.empty().withNewTitle("核對報稅附件"), firstText));

            assertThat(first.action()).isEqualTo(IntentResult.Action.TASK_UPDATED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("TASK");
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("task:" + task.getId());
            assertThat(focusRevision(workspaceId)).isEqualTo(3L);
            assertThat(transitionCount(workspaceId)).isEqualTo(3L);

            String secondText = "把核對報稅附件設成高優先";
            IntentResult second = handle(secondText, command(IntentCommand.Type.UPDATE_TASK,
                    "核對報稅附件", null, null, null, "HIGH",
                    IntentOptions.empty(), secondText));

            assertThat(second.action()).isEqualTo(IntentResult.Action.TASK_UPDATED);
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("task:" + task.getId());
            assertThat(focusRevision(workspaceId)).isEqualTo(3L);
            assertThat(transitionCount(workspaceId)).isEqualTo(3L);
        }
    }

    @Test
    void cancelingTheActiveScheduleInvalidatesItsFocusInTheSameEntryPath() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            var schedule = scheduleService.createSchedule("年度健檢",
                    Instant.parse("2026-10-08T01:00:00Z"),
                    Instant.parse("2026-10-08T02:00:00Z"), null).item();
            focusService.enterResource("SCHEDULE", "schedule:" + schedule.getId(),
                    schedule.getTitle(), hmac('c'));

            String text = "取消年度健檢";
            IntentResult result = handle(text, command(IntentCommand.Type.CANCEL_SCHEDULE,
                    "年度健檢", null, null, null, null, IntentOptions.empty(), text));

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CANCELED);
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);
            assertThat(transitionCount(workspaceId)).isEqualTo(2L);
            assertThat(scheduleService.getSchedule(schedule.getId()).getStatus())
                    .isEqualTo(ScheduleStatus.CANCELED);
        }
    }

    @Test
    void scheduleWithoutEndTimeClarifiesWithoutCreatingTaskOrChangingFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            Task activeTask = taskService.createTask(
                    "整理投保文件", null, TaskPriority.NORMAL, null);
            focusService.enterResource(
                    "TASK", "task:" + activeTask.getId(), activeTask.getTitle(), hmac('d'));

            String text = "下週二早上十點排供應商會議";
            IntentResult result = handle(text, command(
                    IntentCommand.Type.CREATE_SCHEDULE, "供應商會議",
                    "2026-08-18T10:00:00+08:00", null,
                    null, null, IntentOptions.empty(), text));

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.responseEnvelope().message()).contains("結束時間", "多久");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM task WHERE workspace_id = ?",
                    Long.class, workspaceId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM schedule_item WHERE workspace_id = ?",
                    Long.class, workspaceId)).isZero();
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("task:" + activeTask.getId());
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);
        }
    }

    @Test
    void firstScheduleInfoQueryCreatesConversationContextWithoutChangingBusinessOrFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        WorkspaceContext restContext = new WorkspaceContext(
                actorId, workspaceId, WorkspaceChannel.REST, "ios", "fresh-schedule-info");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(restContext)) {
            scheduleService.createSchedule("週五牙醫",
                    Instant.parse("2031-08-15T06:00:00Z"),
                    Instant.parse("2031-08-15T07:00:00Z"), null);

            String text = "牙醫行程的時間跟細節";
            interpreter.nextCommand(command(IntentCommand.Type.ASK_SCHEDULE_INFO,
                    "週五牙醫", null, null, null, null, IntentOptions.empty(), text));
            IntentResult result = RequestCorrelationContext.run(UUID.randomUUID(),
                    () -> intentService.handle(text, "REST"));

            assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_INFO);
            assertThat(result.responseEnvelope().message())
                    .contains("週五牙醫", "08/15 14:00-15:00", "單次");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM schedule_item WHERE workspace_id = ?",
                    Long.class, workspaceId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM conversation_context WHERE workspace_id = ?",
                    Long.class, workspaceId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM conversation_focus_head WHERE workspace_id = ?",
                    Long.class, workspaceId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM focus_transition WHERE workspace_id = ?",
                    Long.class, workspaceId)).isZero();
            assertThat(focusService.activeFocus()).isEmpty();
        }
    }

    @Test
    void savedKnowledgeEntersAResourceFocusReadOnlyQueryKeepsItAndScheduleSwitchesAway() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String recordText = "台灣昆蟲館團體活動要預約，幫我記得";
            IntentResult recorded = handle(recordText, command(
                    IntentCommand.Type.RECORD_VENUE_VISIT_INFO, "團體活動", null, null,
                    "台灣昆蟲館", null,
                    IntentOptions.empty(), recordText));

            assertThat(recorded.action()).isEqualTo(IntentResult.Action.VENUE_VISIT_INFO_SAVED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("KNOWLEDGE");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);

            String askText = "台灣昆蟲館有什麼要先預約";
            IntentResult queried = handle(askText, command(
                    IntentCommand.Type.ASK_VENUE_VISIT_INFO, null, null, null,
                    "台灣昆蟲館", null, IntentOptions.empty(), askText));

            assertThat(queried.action()).isEqualTo(IntentResult.Action.VENUE_VISIT_INFO);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("KNOWLEDGE");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);

            String scheduleText = "十月九日下午兩點到三點排昆蟲館參觀";
            IntentResult scheduled = handle(scheduleText, command(
                    IntentCommand.Type.CREATE_SCHEDULE, "昆蟲館參觀",
                    "2026-10-09T14:00:00+08:00", "2026-10-09T15:00:00+08:00",
                    null, null, IntentOptions.empty(), scheduleText));

            assertThat(scheduled.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("SCHEDULE");
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);
            assertThat(transitionCount(workspaceId)).isEqualTo(2L);
        }
    }

    @Test
    void showingAnItineraryDraftEntersItAndConfirmationInvalidatesThatFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            itineraryDraftService.create(new ReceiptCommand(null, null, List.of(),
                    ReceiptCommand.DocumentType.TRAVEL_ITINERARY, "關西行程表",
                    List.of(new ReceiptCommand.ItineraryEntry(
                            "10-10", "09:00", "10:00", "集合", "大阪站", "東口")),
                    List.of(), List.of("攜帶證件")));

            String showText = "顯示剛才的旅行行程表草稿";
            IntentResult shown = handle(showText, command(
                    IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT, null,
                    null, null, null, null, IntentOptions.empty(), showText));

            assertThat(shown.action()).isEqualTo(IntentResult.Action.TRAVEL_ITINERARY_DRAFTED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("DRAFT");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);

            String confirmText = "確認匯入行程表";
            IntentResult confirmed = handle(confirmText, command(
                    IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT, null,
                    null, null, null, null, IntentOptions.empty(), confirmText));

            assertThat(confirmed.action())
                    .isEqualTo(IntentResult.Action.TRAVEL_ITINERARY_CONFIRMED);
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);
            assertThat(transitionCount(workspaceId)).isEqualTo(2L);
        }
    }

    @Test
    void explicitFocusExitBypassesPendingDraftRetentionAndOnlyChangesFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            itineraryDraftService.create(new ReceiptCommand(null, null, List.of(),
                    ReceiptCommand.DocumentType.TRAVEL_ITINERARY, "北陸行程表",
                    List.of(new ReceiptCommand.ItineraryEntry(
                            "11-03", "08:00", "09:00", "集合", "金澤站", null)),
                    List.of(), List.of()));
            String showText = "再顯示一次旅遊行程表草稿";
            handle(showText, command(IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT,
                    null, null, null, null, null, IntentOptions.empty(), showText));
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("DRAFT");

            long tagsBefore = count(workspaceId, "semantic_tag");
            long bindingsBefore = count(workspaceId, "semantic_tag_binding");
            long recordsBefore = count(workspaceId, "tagged_life_record");
            String exitText = "先跳出現在這個話題，晚點再回來";
            IntentResult exited = handle(exitText, command(IntentCommand.Type.CREATE_TASK,
                    "不應建立", null, null, null, null,
                    IntentOptions.empty(), exitText));

            assertThat(exited.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);
            assertThat(transitionCount(workspaceId)).isEqualTo(2L);
            assertThat(count(workspaceId, "semantic_tag")).isEqualTo(tagsBefore);
            assertThat(count(workspaceId, "semantic_tag_binding")).isEqualTo(bindingsBefore);
            assertThat(count(workspaceId, "tagged_life_record")).isEqualTo(recordsBefore);
        }
    }

    @Test
    void travelPlanningAndPackingUseOneWorkflowWithAChangedSubfocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String planText = "十一月想去韓國玩，先幫我開始規劃";
            IntentResult planned = handle(planText, command(IntentCommand.Type.PLAN_TRIP,
                    null, null, null, null, null, IntentOptions.empty(), planText));

            assertThat(planned.action()).isEqualTo(IntentResult.Action.TRAVEL_INFO);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("TRAVEL");
            UUID workflowId = activeWorkflowId(workspaceId);
            assertThat(workflowId).isNotNull();
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(latestTransitionType(workspaceId)).isEqualTo("ENTER");

            String packingText = "換到這趟旅行的行李準備，列一份打包清單";
            IntentResult packing = handle(packingText, command(
                    IntentCommand.Type.PLAN_PACKING_LIST, null, null, null, null, null,
                    IntentOptions.empty(), packingText));

            assertThat(packing.action()).isEqualTo(IntentResult.Action.PACKING_LIST_INFO);
            assertThat(activeWorkflowId(workspaceId)).isEqualTo(workflowId);
            assertThat(activeActivityCode(workspaceId)).isEqualTo("PACKING");
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);
            assertThat(latestTransitionType(workspaceId)).isEqualTo("CHANGE_SUBFOCUS");
        }
    }

    @Test
    void taggedKnowledgeEntersFocusAndItemLookupResumesItsResource() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String relationText = "節電方案屬於居家計畫，幫我建立標籤關係";
            IntentResult relation = handle(relationText, command(
                    IntentCommand.Type.UPSERT_TAG_RELATION, "節電方案",
                    null, null, null, null,
                    IntentOptions.empty().withTagRelation(
                            "居家計畫", "RELATED_TO", "TOPIC", "TOPIC"),
                    relationText));

            assertThat(relation.action()).isEqualTo(IntentResult.Action.TAG_RELATION_SAVED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("KNOWLEDGE");
            assertThat(latestTransitionType(workspaceId)).isEqualTo("ENTER");

            String queryText = "查一下節電方案的標籤紀錄";
            IntentResult query = handle(queryText, command(
                    IntentCommand.Type.ASK_TAGGED_RECORDS, "節電方案",
                    null, null, null, null, IntentOptions.empty(), queryText));
            assertThat(query.action()).isEqualTo(IntentResult.Action.TAGGED_RECORDS_INFO);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("KNOWLEDGE");

            String inventoryText = "濾芯庫存設成兩個";
            handle(inventoryText, command(IntentCommand.Type.SET_INVENTORY,
                    null, null, null, null, null,
                    itemOptions(List.of("濾芯"), 2), inventoryText));
            String taskText = "提醒我核對濾芯型號";
            handle(taskText, command(IntentCommand.Type.CREATE_TASK,
                    "核對濾芯型號", null, null, null, null,
                    IntentOptions.empty(), taskText));
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("TASK");

            String lookupText = "回到濾芯，查它可以去哪裡買";
            IntentResult lookup = handle(lookupText, command(
                    IntentCommand.Type.ASK_ITEM_PLACES, "濾芯",
                    null, null, null, null, IntentOptions.empty(), lookupText));

            assertThat(lookup.action()).isEqualTo(IntentResult.Action.ITEM_PLACES_INFO);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("ITEM");
            assertThat(latestTransitionType(workspaceId)).isEqualTo("RESUME");
        }
    }

    @Test
    void actorWithoutLocalTripContextClarifiesWithoutCreatingTravelFocus() {
        UUID ownerId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(ownerId, workspaceId);
        seedActor(peerId);
        seedMember(peerId, workspaceId, ownerId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(ownerId, workspaceId))) {
            String planText = "十二月去新加坡，先開始規劃這趟旅行";
            handle(planText, command(IntentCommand.Type.PLAN_TRIP,
                    null, null, null, null, null, IntentOptions.empty(), planText));
            assertThat(focusService.activeFocus()).isPresent();
        }

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(peerId, workspaceId))) {
            String packingText = "幫我列剛才那趟的打包清單";
            IntentResult result = handle(packingText, command(
                    IntentCommand.Type.PLAN_PACKING_LIST, null,
                    null, null, null, null, IntentOptions.empty(), packingText));

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.responseEnvelope().message()).contains("哪一趟", "目的地");
            assertThat(focusService.activeFocus()).isEmpty();
        }
    }

    @Test
    void knowledgeAndDraftBindingsCannotBeRevalidatedByAnotherActorInTheSameWorkspace() {
        UUID ownerId = UUID.randomUUID();
        UUID otherActorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(ownerId, workspaceId);
        seedActor(otherActorId);
        ConversationFocusTargetResolver.ResourceTarget knowledge;
        ConversationFocusTargetResolver.ResourceTarget draft;
        ConversationFocusTargetResolver.ResourceTarget item;

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(ownerId, workspaceId))) {
            String recordText = "海洋館導覽要預約，幫我記得";
            handle(recordText, command(IntentCommand.Type.RECORD_VENUE_VISIT_INFO,
                    "導覽", null, null, "海洋館", null, IntentOptions.empty(), recordText));
            knowledge = new ConversationFocusTargetResolver.ResourceTarget(
                    "KNOWLEDGE", activeRoutingKey(workspaceId), "海洋館");
            itineraryDraftService.create(new ReceiptCommand(null, null, List.of(),
                    ReceiptCommand.DocumentType.TRAVEL_ITINERARY, "九州行程表",
                    List.of(new ReceiptCommand.ItineraryEntry(
                            "10-12", "08:00", "09:00", "集合", "博多站", null)),
                    List.of(), List.of()));
            String showText = "顯示剛才的旅行行程表草稿";
            handle(showText, command(IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT,
                    null, null, null, null, null, IntentOptions.empty(), showText));
            draft = new ConversationFocusTargetResolver.ResourceTarget(
                    "DRAFT", activeRoutingKey(workspaceId), "九州行程表");
            String itemText = "購物清單加安全帽";
            handle(itemText, command(IntentCommand.Type.ADD_SHOPPING_ITEMS,
                    null, null, null, null, null,
                    itemOptions(List.of("安全帽"), null), itemText));
            item = new ConversationFocusTargetResolver.ResourceTarget(
                    "ITEM", activeRoutingKey(workspaceId), "安全帽");
            assertThat(contributors.require("KNOWLEDGE").isAvailable(knowledge)).isTrue();
            assertThat(contributors.require("DRAFT").isAvailable(draft)).isTrue();
            assertThat(contributors.require("ITEM").isAvailable(item)).isTrue();
        }

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(otherActorId, workspaceId))) {
            assertThat(contributors.require("KNOWLEDGE").isAvailable(knowledge)).isFalse();
            assertThat(contributors.require("DRAFT").isAvailable(draft)).isFalse();
            assertThat(contributors.require("ITEM").isAvailable(item)).isFalse();
        }
    }

    @Test
    void cancelingTheActiveTaskInvalidatesOnlyItsConversationFocus() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            Task task = taskService.createTask("整理保險文件", null, TaskPriority.NORMAL, null);
            focusService.enterResource("TASK", "task:" + task.getId(), task.getTitle(), hmac('d'));

            String text = "取消整理保險文件";
            IntentResult result = handle(text, command(IntentCommand.Type.CANCEL_TASK,
                    "整理保險文件", null, null, null, null, IntentOptions.empty(), text));

            assertThat(result.action()).isEqualTo(IntentResult.Action.TASK_CANCELED);
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);
            assertThat(transitionCount(workspaceId)).isEqualTo(2L);
            assertThat(taskService.getTask(task.getId()).getStatus()).isEqualTo(TaskStatus.CANCELED);
        }
    }

    @Test
    void singleShoppingItemsEnterSwitchAndResumeByPersistentIdentity() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String firstText = "購物清單加充電線";
            IntentResult first = handle(firstText, command(
                    IntentCommand.Type.ADD_SHOPPING_ITEMS, null, null, null, null, null,
                    itemOptions(List.of("充電線"), null), firstText));
            Long firstId = itemService.findItem("充電線").orElseThrow().getId();

            assertThat(first.action()).isEqualTo(IntentResult.Action.SHOPPING_ITEMS_ADDED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("ITEM");
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("item:" + firstId);
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);

            String secondText = "再加防水收納袋";
            IntentResult second = handle(secondText, command(
                    IntentCommand.Type.ADD_SHOPPING_ITEMS, null, null, null, null, null,
                    itemOptions(List.of("防水收納袋"), null), secondText));
            Long secondId = itemService.findItem("防水收納袋").orElseThrow().getId();

            assertThat(second.action()).isEqualTo(IntentResult.Action.SHOPPING_ITEMS_ADDED);
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("item:" + secondId);
            assertThat(focusRevision(workspaceId)).isEqualTo(2L);

            String purchasedText = "充電線買到了";
            IntentResult purchased = handle(purchasedText, command(
                    IntentCommand.Type.MARK_SHOPPING_PURCHASED, null, null, null, null, null,
                    itemOptions(List.of("充電線"), 1), purchasedText));

            assertThat(purchased.action()).isEqualTo(IntentResult.Action.SHOPPING_ITEMS_PURCHASED);
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("item:" + firstId);
            assertThat(focusRevision(workspaceId)).isEqualTo(3L);
            assertThat(transitionCount(workspaceId)).isEqualTo(3L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM item WHERE workspace_id = ?",
                    Long.class, workspaceId)).isEqualTo(2L);
        }
    }

    @Test
    void typedTaskAndFeedbackAreNeverOverriddenByTravelNouns() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            String taskText = "提醒我整理旅行資料";
            IntentResult task = handle(taskText, command(IntentCommand.Type.CREATE_TASK,
                    "整理旅行資料", null, null, null, null, IntentOptions.empty(), taskText));

            assertThat(task.action()).isEqualTo(IntentResult.Action.TASK_CREATED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("TASK");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);

            String feedbackText = "回饋：旅行規劃回答不夠清楚";
            IntentResult feedback = handle(feedbackText, command(IntentCommand.Type.FEEDBACK,
                    null, null, null, null, null, IntentOptions.empty(), feedbackText));

            assertThat(feedback.action()).isEqualTo(IntentResult.Action.FEEDBACK_RECEIVED);
            assertThat(activeFocusRoot(workspaceId)).isEqualTo("TASK");
            assertThat(focusRevision(workspaceId)).isEqualTo(1L);
            assertThat(transitionCount(workspaceId)).isEqualTo(1L);
        }
    }

    @Test
    void taskInfoResumesItsSuspendedTaskAndExplicitExitAndClosePreserveBusinessData() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            Task task = taskService.createTask(
                    "繳機車強制險", null, TaskPriority.NORMAL, null);
            var schedule = scheduleService.createSchedule("機車定檢",
                    Instant.parse("2026-08-08T02:00:00Z"),
                    Instant.parse("2026-08-08T03:00:00Z"), null).item();
            focusService.enterResource(
                    "TASK", "task:" + task.getId(), task.getTitle(), hmac('e'));
            focusService.switchResource("SCHEDULE", "schedule:" + schedule.getId(),
                    schedule.getTitle(), hmac('f'));

            String resumeText = "回去處理強制險，那筆資料給我";
            IntentResult resumed = handle(resumeText, command(IntentCommand.Type.ASK_TASK_INFO,
                    task.getTitle(), null, null, null, null, IntentOptions.empty(), resumeText));

            assertThat(resumed.action()).isEqualTo(IntentResult.Action.TASK_INFO);
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("task:" + task.getId());
            assertThat(focusRevision(workspaceId)).isEqualTo(3L);
            assertThat(resumed.responseEnvelope().message())
                    .contains("原本的「機車定檢」先保留，現在回到「繳機車強制險」");

            String exitText = "先離開目前這件事，歇一下";
            IntentResult exited = handle(exitText, command(
                    IntentCommand.Type.valueOf("EXIT_CONVERSATION_FOCUS"),
                    null, null, null, null, null, IntentOptions.empty(), exitText));

            assertThat(exited.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(exited.responseEnvelope().message())
                    .contains("目前沒有正在處理的事項", "既有資料");
            assertThat(taskService.getTask(task.getId()).getStatus()).isEqualTo(TaskStatus.CREATED);
            assertThat(scheduleService.getSchedule(schedule.getId()).getStatus())
                    .isEqualTo(ScheduleStatus.CONFIRMED);

            IntentResult resumedAgain = handle(resumeText, command(
                    IntentCommand.Type.ASK_TASK_INFO, task.getTitle(), null, null, null,
                    null, IntentOptions.empty(), resumeText));
            assertThat(resumedAgain.action()).isEqualTo(IntentResult.Action.TASK_INFO);
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("task:" + task.getId());

            long tagsBeforeClose = count(workspaceId, "semantic_tag");
            long bindingsBeforeClose = count(workspaceId, "semantic_tag_binding");
            long recordsBeforeClose = count(workspaceId, "tagged_life_record");
            String closeText = "這個議題結束，不用再承接";
            IntentResult closed = handle(closeText, command(
                    IntentCommand.Type.valueOf("CLOSE_CONVERSATION_FOCUS"),
                    null, null, null, null, null, IntentOptions.empty(), closeText));

            assertThat(closed.action()).isEqualTo(IntentResult.Action.CONTEXT_UPDATED);
            assertThat(focusService.activeFocus()).isEmpty();
            assertThat(closed.responseEnvelope().message())
                    .contains("已結束這段對話處理", "業務資料");
            assertThat(focusRevision(workspaceId)).isEqualTo(6L);
            assertThat(transitionCount(workspaceId)).isEqualTo(6L);
            assertThat(focusStatus(workspaceId, "task:" + task.getId())).isEqualTo("CLOSED");
            assertThat(focusCloseReason(workspaceId, "task:" + task.getId()))
                    .isEqualTo("USER_CLOSED");
            assertThat(count(workspaceId, "semantic_tag")).isEqualTo(tagsBeforeClose);
            assertThat(count(workspaceId, "semantic_tag_binding"))
                    .isEqualTo(bindingsBeforeClose);
            assertThat(count(workspaceId, "tagged_life_record")).isEqualTo(recordsBeforeClose);
        }
    }

    @Test
    void cancelingASuspendedTaskInvalidatesOnlyItAndKeepsTheActiveSchedule() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(actorId, workspaceId))) {
            Task task = taskService.createTask(
                    "寄年度扣繳憑單", null, TaskPriority.NORMAL, null);
            var schedule = scheduleService.createSchedule("稅務說明會",
                    Instant.parse("2026-08-12T02:00:00Z"),
                    Instant.parse("2026-08-12T03:00:00Z"), null).item();
            focusService.enterResource(
                    "TASK", "task:" + task.getId(), task.getTitle(), hmac('a'));
            focusService.switchResource("SCHEDULE", "schedule:" + schedule.getId(),
                    schedule.getTitle(), hmac('b'));

            String text = "旁邊那筆扣繳憑單不用寄了，取消；說明會別動";
            IntentResult result = handle(text, command(IntentCommand.Type.CANCEL_TASK,
                    task.getTitle(), null, null, null, null, IntentOptions.empty(), text));

            assertThat(result.action()).isEqualTo(IntentResult.Action.TASK_CANCELED);
            assertThat(activeRoutingKey(workspaceId)).isEqualTo("schedule:" + schedule.getId());
            assertThat(focusStatus(workspaceId, "task:" + task.getId())).isEqualTo("CLOSED");
            assertThat(focusCloseReason(workspaceId, "task:" + task.getId()))
                    .isEqualTo("TARGET_INVALIDATED");
            assertThat(focusRevision(workspaceId)).isEqualTo(3L);
            assertThat(transitionCount(workspaceId)).isEqualTo(3L);
            assertThat(result.responseEnvelope().message())
                    .contains("寄年度扣繳憑單", "稅務說明會", "仍");
        }
    }

    @Test
    void taskInformationIsNotVisibleToAnotherActorInTheSameWorkspace() {
        UUID ownerId = UUID.randomUUID();
        UUID otherActorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(ownerId, workspaceId);
        seedActor(otherActorId);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(ownerId, workspaceId))) {
            taskService.createTask("買助聽器電池", null, TaskPriority.NORMAL, null);
        }

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                context(otherActorId, workspaceId))) {
            String text = "幫我看助聽器電池那件";
            IntentResult result = handle(text, command(IntentCommand.Type.ASK_TASK_INFO,
                    "買助聽器電池", null, null, null, null, IntentOptions.empty(), text));

            assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
            assertThat(result.responseEnvelope().message()).doesNotContain("買助聽器電池");
            assertThat(taskService.listTasks()).isEmpty();
            assertThat(focusService.activeFocus()).isEmpty();
        }
    }

    private IntentResult handle(String text, IntentCommand command) {
        interpreter.nextCommand(command);
        return RequestCorrelationContext.run(UUID.randomUUID(),
                () -> intentService.handle(text, "TEST"));
    }

    private static IntentCommand command(IntentCommand.Type type, String title,
                                         String startAt, String endAt, String placeName,
                                         String priority, IntentOptions options, String sourceText) {
        return new IntentCommand(type, title, null, startAt, endAt, placeName, priority,
                null, null, null, null, null, false, options, sourceText);
    }

    private static IntentOptions itemOptions(List<String> names, Integer quantity) {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                names, quantity, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST,
                "test", "focus-actual-entry");
    }

    private String activeFocusRoot(UUID workspaceId) {
        return jdbcTemplate.queryForObject("""
                SELECT root_domain FROM conversation_focus
                WHERE workspace_id = ? AND status = 'ACTIVE'
                """, String.class, workspaceId);
    }

    private String activeRoutingKey(UUID workspaceId) {
        return jdbcTemplate.queryForObject("""
                SELECT routing_key FROM conversation_focus
                WHERE workspace_id = ? AND status = 'ACTIVE'
                """, String.class, workspaceId);
    }

    private long focusRevision(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT revision FROM conversation_focus_head WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private long transitionCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM focus_transition WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private UUID activeWorkflowId(UUID workspaceId) {
        return jdbcTemplate.queryForObject("""
                SELECT workflow_id FROM conversation_focus
                WHERE workspace_id = ? AND status = 'ACTIVE'
                """, UUID.class, workspaceId);
    }

    private String activeActivityCode(UUID workspaceId) {
        return jdbcTemplate.queryForObject("""
                SELECT activity_code FROM conversation_focus
                WHERE workspace_id = ? AND status = 'ACTIVE'
                """, String.class, workspaceId);
    }

    private String latestTransitionType(UUID workspaceId) {
        return jdbcTemplate.queryForObject("""
                SELECT type FROM focus_transition
                WHERE workspace_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, workspaceId);
    }

    private long count(UUID workspaceId, String table) {
        if (!List.of("semantic_tag", "semantic_tag_binding", "tagged_life_record")
                .contains(table)) {
            throw new IllegalArgumentException("unsupported count table");
        }
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private String focusStatus(UUID workspaceId, String routingKey) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM conversation_focus
                WHERE workspace_id = ? AND routing_key = ?
                ORDER BY updated_at DESC LIMIT 1
                """, String.class, workspaceId, routingKey);
    }

    private String focusCloseReason(UUID workspaceId, String routingKey) {
        return jdbcTemplate.queryForObject("""
                SELECT close_reason FROM conversation_focus
                WHERE workspace_id = ? AND routing_key = ?
                ORDER BY updated_at DESC LIMIT 1
                """, String.class, workspaceId, routingKey);
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Focus entry user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Focus entry workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
    }

    private void seedActor(UUID actorId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Other focus actor', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
    }

    private void seedMember(UUID actorId, UUID workspaceId, UUID createdBy) {
        jdbcTemplate.update("""
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), workspaceId, actorId, createdBy);
    }

    private static String hmac(char value) {
        return String.valueOf(value).repeat(64);
    }
}
