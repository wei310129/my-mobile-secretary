package com.aproject.aidriven.mymobilesecretary.planning.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.planning.domain.PlanningItemType;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;

/** 將既有 aggregate 映射成單一的使用者可感知類別。 */
public final class PlanningItemClassifier {

    private PlanningItemClassifier() {
    }

    public static PlanningItemType classify(Task task) {
        return task.getDueAt() == null
                ? PlanningItemType.TODO
                : PlanningItemType.SCHEDULE_REMINDER;
    }

    public static PlanningItemType classify(ScheduleItem schedule) {
        return schedule.isCountsForActorBusy()
                ? PlanningItemType.SCHEDULE
                : PlanningItemType.SCHEDULE_REMINDER;
    }

    public static PlanningItemType classify(ObjectAnnotation annotation) {
        java.util.Objects.requireNonNull(annotation, "annotation");
        return PlanningItemType.KNOWLEDGE;
    }
}
