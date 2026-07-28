package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeIntentTargetResolver;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarCategoryConversationService {

    private final CalendarKnowledgeIntentTargetResolver targets;
    private final CalendarApplicationService calendars;

    public CalendarCategoryConversationService(
            CalendarKnowledgeIntentTargetResolver targets,
            CalendarApplicationService calendars) {
        this.targets = targets;
        this.calendars = calendars;
    }

    public IntentResult change(IntentCommand command) {
        var target = targets.resolve(
                command.safeOptions().referenceTitle(), "PLAN", null);
        if (target.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "找不到唯一的本人行程；請補充完整行程名稱。這次沒有變更資料。");
        }
        if (command.title() == null || command.title().isBlank()) {
            return IntentResult.clarificationNeeded(
                    "請說明要設定的行程分類。這次沒有變更資料。");
        }
        CalendarPlanIdentityView current =
                calendars.lockActivePlanById(target.get().planId());
        CalendarPlanIdentityView changed = calendars.changePlanCategoryById(
                target.get().planId(), command.title(), current.revision());
        return IntentResult.message(
                IntentResult.Action.CALENDAR_CATEGORY_CHANGED_COLOR_UNSUPPORTED,
                "已把行程分類設為「%s」。顏色功能尚未交付，因此沒有改成紅色；也沒有改動重要性、權限或參加狀態。"
                        .formatted(changed.category()));
    }
}
