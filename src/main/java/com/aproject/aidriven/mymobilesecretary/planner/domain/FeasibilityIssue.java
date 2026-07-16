package com.aproject.aidriven.mymobilesecretary.planner.domain;

/**
 * 一個可行性問題。
 *
 * @param type              問題類型
 * @param message           人類可讀說明(含建議動作)
 * @param relatedScheduleId 相關的既有行程(TIME_OVERLAP/TRAVEL_* 時);目前位置類為 null
 */
public record FeasibilityIssue(Type type, String message, Long relatedScheduleId) {

    public enum Type {
        /** 與既有已確認行程時間重疊。 */
        TIME_OVERLAP,
        /** 單次行程完整位於固定行程內，需詢問是否視為固定行程的子項目。 */
        NESTED_IN_RECURRING_SCHEDULE,
        /** 從前一個行程地點趕不到。 */
        TRAVEL_FROM_PREVIOUS,
        /** 結束後趕不上下一個行程。 */
        TRAVEL_TO_NEXT,
        /** 從目前位置趕不到(例:人在高雄、預約在台北)。 */
        TRAVEL_FROM_CURRENT_LOCATION
    }
}
