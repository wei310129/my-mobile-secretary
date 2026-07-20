package com.aproject.aidriven.mymobilesecretary.schoolmeal.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class SchoolMeal extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 180) private String schoolName;
    @Column(nullable = false, length = 180) private String normalizedSchool;
    @Column(nullable = false) private LocalDate mealDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private MealType mealType;
    @Column(nullable = false, length = 1200) private String itemsText;
    @Column(length = 255) private String sourceTitle;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected SchoolMeal() {}

    public static SchoolMeal create(String schoolName, LocalDate date, MealType type,
                                    List<String> items, String sourceTitle, Instant now) {
        SchoolMeal meal = new SchoolMeal();
        meal.schoolName = clean(schoolName, 180);
        meal.normalizedSchool = normalize(schoolName);
        meal.mealDate = java.util.Objects.requireNonNull(date);
        meal.mealType = java.util.Objects.requireNonNull(type);
        meal.replaceItems(items, sourceTitle, now);
        meal.createdAt = now;
        return meal;
    }

    public void replaceItems(List<String> items, String sourceTitle, Instant now) {
        List<String> safe = items == null ? List.of() : items.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).limit(20).toList();
        if (safe.isEmpty()) throw new IllegalArgumentException("meal items are required");
        itemsText = clean(String.join("、", safe), 1200);
        this.sourceTitle = sourceTitle == null ? null : clean(sourceTitle, 255);
        updatedAt = now;
    }

    public List<String> items() { return List.of(itemsText.split("、")); }
    public static String normalize(String value) {
        return clean(value, 180).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
    }
    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
        String clean = value.strip().replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
    public Long getId() { return id; }
    public String getSchoolName() { return schoolName; }
    public String getNormalizedSchool() { return normalizedSchool; }
    public LocalDate getMealDate() { return mealDate; }
    public MealType getMealType() { return mealType; }
    public String getItemsText() { return itemsText; }
    public enum MealType { BREAKFAST, LUNCH, SNACK, DINNER }
}
