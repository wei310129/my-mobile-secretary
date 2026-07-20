package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.application.SchoolMealService;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.domain.SchoolMeal.MealType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SchoolMealIntentHandler implements IntentHandler {
    private final SchoolMealService service;
    public SchoolMealIntentHandler(SchoolMealService service) { this.service = service; }
    @Override public Set<IntentCommand.Type> supportedTypes() {
        return Set.of(IntentCommand.Type.ASK_SCHOOL_MEAL);
    }
    @Override public IntentResult handle(String text, IntentCommand command) {
        LocalDate from = date(command.startAt());
        LocalDate to = command.endAt() == null ? from : date(command.endAt());
        if (from == null) return IntentResult.message(IntentResult.Action.CLARIFICATION_NEEDED,
                "請告訴我要查哪一天或哪一週的學校餐點。");
        MealType type = null;
        String filter = command.safeOptions().filter();
        try { if (filter != null) type = MealType.valueOf(filter.toUpperCase()); }
        catch (IllegalArgumentException ignored) { }
        var meals = service.search(from, to, type, command.title(), command.placeName());
        if (meals.isEmpty()) return IntentResult.message(IntentResult.Action.SCHOOL_MEAL_INFO,
                "這個日期範圍沒有找到符合條件的學校餐點紀錄。");
        String lines = meals.stream().limit(20).map(meal -> "- %s（%s）：%s".formatted(
                meal.getMealDate(), meal.getMealType().name(), meal.getItemsText()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SCHOOL_MEAL_INFO, lines);
    }
    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toLocalDate(); }
        catch (java.time.DateTimeException e) {
            try { return LocalDate.parse(value); } catch (java.time.DateTimeException ignored) { return null; }
        }
    }
}
