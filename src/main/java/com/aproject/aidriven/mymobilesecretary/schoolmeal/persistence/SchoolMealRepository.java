package com.aproject.aidriven.mymobilesecretary.schoolmeal.persistence;

import com.aproject.aidriven.mymobilesecretary.schoolmeal.domain.SchoolMeal;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.domain.SchoolMeal.MealType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolMealRepository extends JpaRepository<SchoolMeal, Long> {
    Optional<SchoolMeal> findByCreatedByUserIdAndNormalizedSchoolAndMealDateAndMealType(
            UUID actorId, String school, LocalDate date, MealType type);
    List<SchoolMeal> findByCreatedByUserIdAndMealDateBetweenOrderByMealDateAscMealTypeAsc(
            UUID actorId, LocalDate from, LocalDate to);
}
