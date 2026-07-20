package com.aproject.aidriven.mymobilesecretary.schoolmeal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchoolMealTest {
    @Test
    void storesOnlyExplicitMealItems() {
        SchoolMeal meal = SchoolMeal.create("滬江幼兒園 115年7月菜單",
                LocalDate.of(2026, 7, 20), SchoolMeal.MealType.BREAKFAST,
                List.of("鮮奶", "小餐包"), "7月菜單", Instant.parse("2026-07-18T00:00:00Z"));

        assertThat(meal.getItemsText()).isEqualTo("鮮奶、小餐包");
        assertThat(meal.items()).containsExactly("鮮奶", "小餐包");
    }
}
