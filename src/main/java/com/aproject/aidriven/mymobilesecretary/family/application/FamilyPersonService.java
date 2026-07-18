package com.aproject.aidriven.mymobilesecretary.family.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAlias;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Key;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Visibility;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonProfile;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonProfile.Role;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyPersonAliasRepository;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyPersonAttributeRepository;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyPersonProfileRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves family terms using one actor's private vocabulary. It deliberately does not infer
 * that another family member uses the same aliases or agrees with the same relationship model.
 */
@Service
@Transactional
public class FamilyPersonService {

    private static final List<PersonDefinition> DEFAULTS = List.of(
            new PersonDefinition("wife", Role.SPOUSE, "老婆",
                    List.of("老婆", "我老婆", "妻子", "我妻子", "太太", "我太太")),
            new PersonDefinition("eldest_daughter", Role.DAUGHTER, "大女兒",
                    List.of("大女兒", "我大女兒", "女兒", "我女兒")),
            new PersonDefinition("youngest_son", Role.SON, "小兒子",
                    List.of("小兒子", "我小兒子", "兒子", "我兒子")));

    private static final Pattern NAME_TEACHING = Pattern.compile(
            "(?:我)?(老婆|妻子|太太|大女兒|女兒|小兒子|兒子)(?:的)?"
                    + "(?:姓名|名字)?(?:是|叫)([^，。！？?\\s]{1,40})");

    private final FamilyPersonProfileRepository profileRepository;
    private final FamilyPersonAliasRepository aliasRepository;
    private final FamilyPersonAttributeRepository attributeRepository;
    private final Clock clock;

    public FamilyPersonService(
            FamilyPersonProfileRepository profileRepository,
            FamilyPersonAliasRepository aliasRepository,
            FamilyPersonAttributeRepository attributeRepository,
            Clock clock) {
        this.profileRepository = profileRepository;
        this.aliasRepository = aliasRepository;
        this.attributeRepository = attributeRepository;
        this.clock = clock;
    }

    /** Handles direct recognition/name questions and explicit name teaching without an LLM. */
    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        List<Mention> mentions = mentions(text);
        if (mentions.isEmpty()) {
            return Optional.empty();
        }

        // Relationship teaching belongs to FamilyMessageService. A sentence such as
        // 「我是我女兒的家長，也是爸爸，這層關係你能理解嗎」looks like a recognition
        // question too, but answering here would prevent the relationship from being persisted.
        if (looksLikeRelationshipTeaching(text)) {
            return Optional.empty();
        }

        Matcher nameTeaching = NAME_TEACHING.matcher(text);
        if (nameTeaching.find() && !isRecognitionQuestion(text)) {
            PersonDefinition definition = definitionForAlias(nameTeaching.group(1)).orElseThrow();
            String name = bounded(nameTeaching.group(2), 80, "person name");
            beforeMutation.run();
            FamilyPersonProfile person = ensure(definition);
            rememberAttribute(person, Key.NAME, name);
            return Optional.of(IntentResult.message(IntentResult.Action.FAMILY_PERSON_UPDATED,
                    "👤 我已更新你的私人家庭人物資料。\n"
                            + "- 人物：%s\n- 姓名：%s\n- 可見範圍：只有你"
                            .formatted(person.getDisplayLabel(), name)));
        }

        if (!isRecognitionQuestion(text)) {
            return Optional.empty();
        }
        beforeMutation.run();
        List<FamilyPersonProfile> profiles = mentions.stream()
                .map(Mention::definition)
                .distinct()
                .map(this::ensure)
                .toList();
        String people = profiles.stream().map(this::personDescription)
                .map(value -> "- " + value)
                .collect(Collectors.joining("\n"));
        return Optional.of(IntentResult.message(IntentResult.Action.FAMILY_PERSON_RECOGNIZED,
                "👨‍👩‍👧‍👦 我可以依照你的個人稱呼辨認。\n" + people
                        + "\n\n🔒 這是你的私人認知資料，不會因未來開啟家庭功能就自動共享。"));
    }

    /** Lazily creates only the profiles actually mentioned by this actor. */
    public void observeMentions(String text, Runnable beforeMutation) {
        List<PersonDefinition> definitions = mentions(text).stream()
                .map(Mention::definition).distinct().toList();
        if (definitions.isEmpty()) {
            return;
        }
        UUID actorId = actorId();
        List<PersonDefinition> missing = definitions.stream()
                .filter(definition -> profileRepository
                        .findByCreatedByUserIdAndCanonicalKey(actorId, definition.canonicalKey())
                        .isEmpty())
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        beforeMutation.run();
        missing.forEach(this::ensure);
    }

    /** Remembers a school explicitly supplied while correcting a family notice title. */
    public boolean rememberSchoolForMention(String text, String school, Runnable beforeMutation) {
        Optional<PersonDefinition> definition = mentions(text).stream()
                .map(Mention::definition)
                .findFirst();
        if (definition.isEmpty() || school == null || school.isBlank()) {
            return false;
        }
        beforeMutation.run();
        FamilyPersonProfile person = ensure(definition.get());
        rememberAttribute(person, Key.SCHOOL, bounded(school, 100, "school"));
        return true;
    }

    /** Resolves a previously saved school only when this sentence names one known family role. */
    @Transactional(readOnly = true)
    public Optional<String> schoolForMention(String text) {
        Optional<PersonDefinition> definition = mentions(text).stream()
                .map(Mention::definition)
                .findFirst();
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        UUID actorId = actorId();
        return profileRepository
                .findByCreatedByUserIdAndCanonicalKey(actorId, definition.get().canonicalKey())
                .flatMap(person -> attributeRepository
                        .findByCreatedByUserIdAndPersonIdAndKey(
                                actorId, person.getId(), Key.SCHOOL))
                .map(FamilyPersonAttribute::getValue);
    }

    /** Adds a small resolution note only when the current sentence contains a known family term. */
    @Transactional(readOnly = true)
    public String enrichForIntent(String text) {
        List<Mention> found = mentions(text);
        if (found.isEmpty()) {
            return text;
        }
        Map<String, String> resolutions = new LinkedHashMap<>();
        for (Mention mention : found) {
            resolutions.put(mention.surface(), mention.definition().displayLabel());
        }
        String note = resolutions.entrySet().stream()
                .map(entry -> entry.getKey() + "→" + entry.getValue())
                .collect(Collectors.joining("、"));
        return text + "\n\n[私人家庭稱呼解析：" + note + "；僅代表此使用者的稱呼]";
    }

    private FamilyPersonProfile ensure(PersonDefinition definition) {
        UUID actorId = actorId();
        FamilyPersonProfile profile = profileRepository
                .findByCreatedByUserIdAndCanonicalKey(actorId, definition.canonicalKey())
                .orElseGet(() -> profileRepository.save(FamilyPersonProfile.create(
                        definition.canonicalKey(), definition.role(), definition.displayLabel(),
                        Instant.now(clock))));
        for (String alias : definition.aliases()) {
            String normalized = normalize(alias);
            if (aliasRepository.findByCreatedByUserIdAndNormalizedAlias(actorId, normalized)
                    .isEmpty()) {
                aliasRepository.save(FamilyPersonAlias.create(
                        profile.getId(), alias, normalized, Instant.now(clock)));
            }
        }
        return profile;
    }

    private void rememberAttribute(FamilyPersonProfile person, Key key, String value) {
        UUID actorId = actorId();
        Instant now = Instant.now(clock);
        FamilyPersonAttribute attribute = attributeRepository
                .findByCreatedByUserIdAndPersonIdAndKey(actorId, person.getId(), key)
                .orElseGet(() -> FamilyPersonAttribute.create(
                        person.getId(), key, value, Visibility.PRIVATE, now));
        if (attribute.getId() != null) {
            attribute.update(value, Visibility.PRIVATE, now);
        }
        attributeRepository.save(attribute);
    }

    private String personDescription(FamilyPersonProfile person) {
        Optional<FamilyPersonAttribute> name = attributeRepository
                .findByCreatedByUserIdAndPersonIdAndKey(actorId(), person.getId(), Key.NAME);
        String identity = name.map(attribute -> "%s（姓名：%s）".formatted(
                        person.getDisplayLabel(), attribute.getValue()))
                .orElseGet(() -> person.getDisplayLabel() + "（姓名尚未提供）");
        return attributeRepository
                .findByCreatedByUserIdAndPersonIdAndKey(actorId(), person.getId(), Key.SCHOOL)
                .map(attribute -> identity + "／學校：" + attribute.getValue())
                .orElse(identity);
    }

    private static List<Mention> mentions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Mention> result = new ArrayList<>();
        for (PersonDefinition definition : DEFAULTS) {
            String surface = definition.aliases().stream()
                    .filter(text::contains)
                    .max(java.util.Comparator.comparingInt(String::length))
                    .orElse(null);
            if (surface != null) {
                result.add(new Mention(surface, definition));
            }
        }
        return List.copyOf(result);
    }

    private static Optional<PersonDefinition> definitionForAlias(String alias) {
        return DEFAULTS.stream()
                .filter(definition -> definition.aliases().contains(alias)
                        || definition.aliases().contains("我" + alias))
                .findFirst();
    }

    private static boolean isRecognitionQuestion(String text) {
        boolean question = text.contains("?") || text.contains("？") || text.contains("嗎")
                || text.contains("誰") || text.contains("什麼");
        return question && containsAny(text, "認得", "辨認", "分辨", "知道", "理解",
                "是誰", "叫什麼", "名字");
    }

    private static boolean looksLikeRelationshipTeaching(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.startsWith("我是我") && compact.contains("的家長")
                && (compact.contains("爸爸") || compact.contains("媽媽"));
    }

    private static UUID actorId() {
        return WorkspaceContextHolder.requireContext().actorId();
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\p{P}\\p{S}]+", "")
                .strip();
    }

    private static String bounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return stripped;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private record PersonDefinition(String canonicalKey, Role role, String displayLabel,
                                    List<String> aliases) {
    }

    private record Mention(String surface, PersonDefinition definition) {
    }
}
