package com.aproject.aidriven.mymobilesecretary.schoolmeal.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand.MenuEntry;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.domain.SchoolMeal;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.domain.SchoolMeal.MealType;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.persistence.SchoolMealRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SchoolMealService {
    private final SchoolMealRepository repository;
    private final SemanticTagGraphService graph;
    private final UniversalLifeRecordService life;
    private final Clock clock;

    public SchoolMealService(SchoolMealRepository repository, SemanticTagGraphService graph,
                             UniversalLifeRecordService life, Clock clock) {
        this.repository = repository;
        this.graph = graph;
        this.life = life;
        this.clock = clock;
    }

    public int importMenu(String documentTitle, List<MenuEntry> entries) {
        String school = documentTitle == null || documentTitle.isBlank() ? "學校菜單" : documentTitle.strip();
        String normalized = SchoolMeal.normalize(school);
        Instant now = Instant.now(clock);
        int saved = 0;
        for (MenuEntry entry : entries == null ? List.<MenuEntry>of() : entries) {
            try {
                LocalDate date = LocalDate.parse(entry.date());
                MealType type = MealType.valueOf(entry.mealType().strip().toUpperCase(Locale.ROOT));
                SchoolMeal meal = repository
                        .findByCreatedByUserIdAndNormalizedSchoolAndMealDateAndMealType(
                                WorkspaceContextHolder.requireContext().actorId(), normalized, date, type)
                        .orElseGet(() -> SchoolMeal.create(school, date, type, entry.items(), documentTitle, now));
                meal.replaceItems(entry.items(), documentTitle, now);
                SchoolMeal persisted = repository.save(meal);
                index(persisted);
                saved++;
            } catch (RuntimeException ignored) {
                // One unreadable table cell must not discard the remaining valid menu rows.
            }
        }
        if (saved > 0) {
            life.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                    "匯入一份學校菜單", now, List.of("學校菜單", "餐點"));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SchoolMeal> search(LocalDate from, LocalDate to, MealType type,
                                   String itemKeyword, String schoolKeyword) {
        if (from == null || to == null || to.isBefore(from) || from.plusMonths(3).isBefore(to)) {
            throw new IllegalArgumentException("valid menu date range is required");
        }
        String item = normalize(itemKeyword);
        String school = normalize(schoolKeyword);
        return repository.findByCreatedByUserIdAndMealDateBetweenOrderByMealDateAscMealTypeAsc(
                        WorkspaceContextHolder.requireContext().actorId(), from, to).stream()
                .filter(meal -> type == null || meal.getMealType() == type)
                .filter(meal -> item == null || normalize(meal.getItemsText()).contains(item))
                .filter(meal -> school == null || normalize(meal.getSchoolName()).contains(school))
                .toList();
    }

    private void index(SchoolMeal meal) {
        List<SemanticTagGraphService.TagSpec> tags = new ArrayList<>();
        tags.add(tag("學校菜單", SemanticTag.Kind.TOPIC));
        tags.add(tag(meal.getSchoolName(), SemanticTag.Kind.ORGANIZATION));
        tags.add(tag(meal.getMealType().name(), SemanticTag.Kind.ACTIVITY));
        meal.items().forEach(item -> tags.add(tag(item, SemanticTag.Kind.PRODUCT)));
        graph.indexSchoolMeal(meal.getId(), tags);
    }

    private static SemanticTagGraphService.TagSpec tag(String name, SemanticTag.Kind kind) {
        return new SemanticTagGraphService.TagSpec(name, kind, SemanticTagEdge.SourceType.IMPORT);
    }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null
                : value.strip().toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
