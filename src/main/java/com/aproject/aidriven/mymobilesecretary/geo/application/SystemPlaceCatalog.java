package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.SystemPlaceCategory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Loads the immutable public-place snapshot bundled with the application. */
@Component
public final class SystemPlaceCatalog {

    static final String HEADER =
            "key\tcategory\tname\tlocation\tlatitude\tlongitude\taliases\tsource";

    private final Map<String, List<SystemPlace>> byAlias;
    private final Map<String, LogicalPlace> logicalPlacesByAlias;
    private final Map<String, Set<String>> searchTermsByKey;
    private final List<SystemPlace> places;
    private final Map<SystemPlaceCategory, Long> categoryCounts;
    private final int size;

    @Autowired
    public SystemPlaceCatalog(
            @Value("classpath:system-place-catalog.tsv") Resource catalogResource,
            @Value("classpath:system-place-logical-groups.tsv") Resource logicalGroupResource) {
        CatalogSnapshot snapshot = load(catalogResource);
        this.byAlias = snapshot.byAlias();
        this.searchTermsByKey = snapshot.searchTermsByKey();
        this.places = snapshot.places();
        this.categoryCounts = snapshot.categoryCounts();
        this.size = snapshot.size();
        this.logicalPlacesByAlias = loadLogicalPlaces(logicalGroupResource, places);
    }

    public SystemPlaceCatalog(Resource catalogResource) {
        this(catalogResource, new ClassPathResource("system-place-logical-groups.tsv"));
    }

    public Lookup lookup(String userText) {
        String key = normalize(userText);
        if (key.isBlank()) return Lookup.notFound();
        List<SystemPlace> matches = byAlias.getOrDefault(key, List.of());
        SystemPlaceCategory categoryHint = categoryHint(key);
        if (categoryHint != null) {
            List<SystemPlace> scoped = matches.stream()
                    .filter(place -> place.category() == categoryHint)
                    .toList();
            if (!scoped.isEmpty()) matches = scoped;
        }
        if (matches.isEmpty()) return Lookup.notFound();
        if (matches.size() == 1) return Lookup.found(matches.getFirst());
        return Lookup.ambiguous(matches.size());
    }

    /** Resolves an exact alias, a same-hub multi-point mention, or a true cross-region ambiguity. */
    public Resolution resolveMention(String userText) {
        String text = normalize(userText);
        if (text.isBlank()) return Resolution.notFound();
        SystemPlaceCategory category = categoryHint(text);
        LogicalPlace logicalPlace = category == null ? logicalPlaceMention(text) : null;
        if (logicalPlace != null) {
            List<SystemPlace> points = logicalPlace.candidateKeys().stream()
                    .map(this::findByKey).flatMap(Optional::stream).toList();
            return Resolution.logicalMultiPoint(selectHubCenter(points), points, logicalPlace);
        }
        LinkedHashSet<SystemPlace> candidates = new LinkedHashSet<>(
                byAlias.getOrDefault(text, List.of()));
        if (candidates.isEmpty()) {
            int longest = 0;
            for (Map.Entry<String, List<SystemPlace>> entry : byAlias.entrySet()) {
                String alias = entry.getKey();
                if (alias.length() < 2 || !text.contains(alias)) continue;
                if (alias.length() > longest) {
                    candidates.clear();
                    longest = alias.length();
                }
                if (alias.length() == longest) candidates.addAll(entry.getValue());
            }
        }
        if (category != null) {
            candidates.removeIf(place -> place.category() != category);
        }
        String operatorKey = metroOperatorKey(text);
        if (operatorKey != null) {
            LinkedHashSet<SystemPlace> operatorMatches = candidates.stream()
                    .filter(place -> place.key().contains(operatorKey))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!operatorMatches.isEmpty()) candidates = operatorMatches;
        }
        if (candidates.isEmpty()) {
            return category == null ? Resolution.notFound() : Resolution.categoryOnly(category);
        }
        List<SystemPlace> ordered = candidates.stream()
                .sorted(Comparator.comparing(SystemPlace::key))
                .toList();
        if (ordered.size() == 1) return Resolution.exact(ordered.getFirst());
        long regions = ordered.stream().map(SystemPlaceCatalog::regionLabel)
                .filter(value -> !value.isBlank()).distinct().count();
        if (regions <= 1) {
            return Resolution.logicalMultiPoint(selectHubCenter(ordered), ordered, null);
        }
        return Resolution.entityAmbiguous(ordered, category);
    }

    public List<SystemPlace> search(SystemPlaceCategory category, String regionHint, int limit) {
        if (category == null || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("system place search requires a category and bounded limit");
        }
        String region = normalize(regionHint);
        return places.stream()
                .filter(place -> place.category() == category)
                .filter(place -> region.isBlank()
                        || normalize(place.location()).contains(region)
                        || normalize(place.name()).contains(region))
                .sorted(Comparator.comparing(SystemPlace::name).thenComparing(SystemPlace::key))
                .limit(limit)
                .toList();
    }

    public SystemPlaceCategory categoryMentioned(String text) {
        return categoryHint(normalize(text));
    }

    public Optional<SystemPlace> findByKey(String key) {
        return places.stream().filter(place -> place.key().equals(key)).findFirst();
    }

    public String publicPointLabel(SystemPlace place) {
        if (place == null) return "";
        if (place.category() != SystemPlaceCategory.METRO_STATION) {
            return "%s「%s」".formatted(place.category().publicLabel(), place.name());
        }
        String system = place.key().contains(":TRTC-") ? "台北捷運"
                : place.key().contains(":NTMCC-") ? "新北捷運"
                : place.key().contains(":TYMC-") ? "桃園捷運"
                : place.key().contains(":TMRT-") ? "台中捷運"
                : place.key().contains(":KRTC-") ? "高雄捷運"
                : "捷運";
        String station = place.name().startsWith("捷運")
                ? place.name()
                : "捷運" + place.name();
        return "%s「%s」".formatted(system, station);
    }

    public List<SystemPlace> points(List<String> keys) {
        if (keys == null) return List.of();
        return keys.stream().map(this::findByKey).flatMap(Optional::stream)
                .sorted(Comparator.comparing(SystemPlace::key)).toList();
    }

    public List<SystemPlace> filterCandidates(List<String> keys, String userKeyword) {
        String keyword = normalizeKeyword(userKeyword);
        if (keyword.isBlank()) return List.of();
        return points(keys).stream()
                .filter(place -> searchTermsByKey.getOrDefault(place.key(), Set.of()).stream()
                        .anyMatch(term -> term.contains(keyword) || keyword.contains(term)))
                .toList();
    }

    public List<SystemPlace> matchRegion(List<String> keys, String userRegion) {
        String region = normalize(userRegion);
        if (region.length() < 2) return List.of();
        return keys.stream().map(this::findByKey).flatMap(Optional::stream)
                .filter(place -> normalize(place.location()).contains(region))
                .sorted(Comparator.comparing(SystemPlace::key)).toList();
    }

    public SystemPlace selectPlanningPoint(List<SystemPlace> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one system place candidate is required");
        }
        return selectHubCenter(List.copyOf(candidates));
    }

    public int size() {
        return size;
    }

    public Map<SystemPlaceCategory, Long> categoryCounts() {
        return categoryCounts;
    }

    static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('臺', '台')
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static SystemPlaceCategory categoryHint(String normalized) {
        if (normalized.contains("捷運")) return SystemPlaceCategory.METRO_STATION;
        if (normalized.contains("高鐵")) return SystemPlaceCategory.HSR_STATION;
        if (normalized.contains("台鐵") || normalized.contains("火車")) {
            return SystemPlaceCategory.RAIL_STATION;
        }
        if (normalized.contains("機場")) return SystemPlaceCategory.AIRPORT;
        if (normalized.contains("港")) return SystemPlaceCategory.SEAPORT;
        if (!normalized.contains("站")
                && (normalized.contains("市政府")
                        || normalized.contains("縣政府")
                        || normalized.contains("市府")
                        || normalized.contains("縣府"))) {
            return SystemPlaceCategory.LOCAL_GOVERNMENT;
        }
        if (normalized.contains("遊樂") || normalized.contains("樂園")) {
            return SystemPlaceCategory.AMUSEMENT_PARK;
        }
        return null;
    }

    private static String metroOperatorKey(String normalized) {
        if (normalized.contains("桃園捷運") || normalized.contains("桃園機場捷運")
                || normalized.contains("機場捷運") || normalized.contains("機捷")) {
            return ":TYMC-";
        }
        if (normalized.contains("台北捷運") || normalized.contains("北捷")) {
            return ":TRTC-";
        }
        if (normalized.contains("新北捷運")) return ":NTMCC-";
        if (normalized.contains("台中捷運") || normalized.contains("中捷")) return ":TMRT-";
        if (normalized.contains("高雄捷運") || normalized.contains("高捷")) return ":KRTC-";
        return null;
    }

    private LogicalPlace logicalPlaceMention(String text) {
        LogicalPlace selected = null;
        int longest = 0;
        for (Map.Entry<String, LogicalPlace> entry : logicalPlacesByAlias.entrySet()) {
            if (entry.getKey().length() >= 2 && text.contains(entry.getKey())
                    && entry.getKey().length() > longest) {
                selected = entry.getValue();
                longest = entry.getKey().length();
            }
        }
        return selected;
    }

    private String normalizeKeyword(String value) {
        String keyword = normalize(value);
        for (String alias : logicalPlacesByAlias.keySet()) {
            keyword = keyword.replace(alias, "");
        }
        for (String filler : List.of(
                "關鍵字", "幫我", "查詢", "查找", "找找", "搜尋", "列出", "顯示",
                "有哪些", "有什麼", "點位", "給我", "看看", "的", "呢", "嗎")) {
            keyword = keyword.replace(filler, "");
        }
        return keyword;
    }

    private static SystemPlace selectHubCenter(List<SystemPlace> candidates) {
        List<SystemPlace> located = candidates.stream()
                .filter(place -> place.latitude() != null && place.longitude() != null)
                .toList();
        if (located.isEmpty()) return candidates.getFirst();
        double latitude = located.stream().mapToDouble(SystemPlace::latitude).average().orElseThrow();
        double longitude = located.stream().mapToDouble(SystemPlace::longitude).average().orElseThrow();
        return located.stream().min(Comparator
                .comparingDouble((SystemPlace place) -> square(place.latitude() - latitude)
                        + square(place.longitude() - longitude))
                .thenComparing(SystemPlace::key)).orElseThrow();
    }

    private static double square(double value) {
        return value * value;
    }

    public static String regionLabel(SystemPlace place) {
        String value = normalize(place.location());
        for (String region : List.of(
                "台北市", "新北市", "桃園市", "台中市", "台南市", "高雄市",
                "基隆市", "新竹市", "嘉義市", "新竹縣", "苗栗縣", "彰化縣",
                "南投縣", "雲林縣", "嘉義縣", "屏東縣", "宜蘭縣", "花蓮縣",
                "台東縣", "澎湖縣", "金門縣", "連江縣")) {
            if (value.contains(normalize(region))) return normalize(region);
        }
        return "";
    }

    private static CatalogSnapshot load(Resource resource) {
        Map<String, SystemPlace> placesByKey = new LinkedHashMap<>();
        Map<String, LinkedHashSet<SystemPlace>> aliases = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> searchTerms = new LinkedHashMap<>();
        EnumMap<SystemPlaceCategory, Long> counts = new EnumMap<>(SystemPlaceCategory.class);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            String header = first != null && first.startsWith("#") ? reader.readLine() : first;
            if (!HEADER.equals(header)) {
                throw new IllegalStateException("system place catalog header is invalid");
            }
            int lineNumber = first != null && first.startsWith("#") ? 2 : 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] values = line.split("\\t", -1);
                if (values.length != 8) {
                    throw new IllegalStateException(
                            "system place catalog row has invalid shape at line " + lineNumber);
                }
                SystemPlace place = parse(values, lineNumber);
                if (placesByKey.putIfAbsent(place.key(), place) != null) {
                    throw new IllegalStateException(
                            "system place catalog has duplicate key at line " + lineNumber);
                }
                counts.merge(place.category(), 1L, Long::sum);
                index(aliases, place.name(), place);
                addSearchTerm(searchTerms, place.key(), place.name());
                addSearchTerm(searchTerms, place.key(), place.location());
                addSearchTerm(searchTerms, place.key(), place.category().publicLabel());
                for (String alias : values[6].split("\\|")) {
                    index(aliases, alias, place);
                    addSearchTerm(searchTerms, place.key(), alias);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("system place catalog cannot be loaded", exception);
        }
        for (SystemPlaceCategory category : SystemPlaceCategory.values()) {
            if (!counts.containsKey(category)) {
                throw new IllegalStateException("system place catalog is missing category " + category);
            }
        }
        Map<String, List<SystemPlace>> immutableAliases = new LinkedHashMap<>();
        aliases.forEach((key, value) -> immutableAliases.put(key, List.copyOf(value)));
        Map<String, Set<String>> immutableTerms = new LinkedHashMap<>();
        searchTerms.forEach((key, value) -> immutableTerms.put(key, Set.copyOf(value)));
        return new CatalogSnapshot(
                Map.copyOf(immutableAliases), List.copyOf(placesByKey.values()),
                Map.copyOf(counts), Map.copyOf(immutableTerms), placesByKey.size());
    }

    private static Map<String, LogicalPlace> loadLogicalPlaces(
            Resource resource, List<SystemPlace> places) {
        Map<String, SystemPlace> placesByKey = places.stream()
                .collect(java.util.stream.Collectors.toMap(SystemPlace::key, place -> place));
        Map<String, LogicalPlace> aliases = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            if (!"key\tname\tregion\taliases\tcandidate_keys".equals(reader.readLine())) {
                throw new IllegalStateException("system logical place header is invalid");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] values = line.split("\\t", -1);
                if (values.length != 5) {
                    throw new IllegalStateException("system logical place row has invalid shape");
                }
                List<String> candidateKeys = List.of(values[4].split("\\|"));
                if (candidateKeys.size() < 2
                        || candidateKeys.stream().anyMatch(key -> !placesByKey.containsKey(key))) {
                    throw new IllegalStateException("system logical place candidates are invalid");
                }
                LogicalPlace logical = new LogicalPlace(
                        required(values[0], "logical key", 0),
                        required(values[1], "logical name", 0),
                        required(values[2], "logical region", 0), candidateKeys);
                for (String alias : values[3].split("\\|")) {
                    LogicalPlace existing = aliases.putIfAbsent(normalize(alias), logical);
                    if (existing != null && !existing.key().equals(logical.key())) {
                        throw new IllegalStateException("system logical place alias is duplicated");
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("system logical places cannot be loaded", exception);
        }
        return Map.copyOf(aliases);
    }

    private static SystemPlace parse(String[] values, int lineNumber) {
        String key = required(values[0], "key", lineNumber);
        SystemPlaceCategory category;
        try {
            category = SystemPlaceCategory.valueOf(required(values[1], "category", lineNumber));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "system place catalog category is invalid at line " + lineNumber, exception);
        }
        String name = required(values[2], "name", lineNumber);
        String location = required(values[3], "location", lineNumber);
        Double latitude = coordinate(values[4], -90, 90, "latitude", lineNumber);
        Double longitude = coordinate(values[5], -180, 180, "longitude", lineNumber);
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalStateException(
                    "system place catalog coordinates are incomplete at line " + lineNumber);
        }
        String source = required(values[7], "source", lineNumber);
        return new SystemPlace(key, category, name, location, latitude, longitude, source);
    }

    private static Double coordinate(
            String value, double minimum, double maximum, String field, int lineNumber) {
        if (value == null || value.isBlank()) return null;
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "system place catalog " + field + " is invalid at line " + lineNumber,
                    exception);
        }
    }

    private static String required(String value, String field, int lineNumber) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "system place catalog " + field + " is missing at line " + lineNumber);
        }
        return value.strip();
    }

    private static void index(
            Map<String, LinkedHashSet<SystemPlace>> aliases, String value, SystemPlace place) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return;
        aliases.computeIfAbsent(normalized, ignored -> new LinkedHashSet<>()).add(place);
    }

    private static void addSearchTerm(
            Map<String, LinkedHashSet<String>> terms, String key, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            terms.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(normalized);
        }
    }

    public record SystemPlace(
            String key,
            SystemPlaceCategory category,
            String name,
            String location,
            Double latitude,
            Double longitude,
            String source) {
    }

    public record LogicalPlace(
            String key, String name, String region, List<String> candidateKeys) {
        public LogicalPlace {
            candidateKeys = List.copyOf(candidateKeys);
        }
    }

    public record Lookup(Status status, SystemPlace place, int candidateCount) {

        public enum Status {
            FOUND,
            AMBIGUOUS,
            NOT_FOUND
        }

        static Lookup found(SystemPlace place) {
            return new Lookup(Status.FOUND, place, 1);
        }

        static Lookup ambiguous(int candidateCount) {
            return new Lookup(Status.AMBIGUOUS, null, candidateCount);
        }

        static Lookup notFound() {
            return new Lookup(Status.NOT_FOUND, null, 0);
        }
    }

    public record Resolution(
            Status status, SystemPlace selected, List<SystemPlace> candidates,
            SystemPlaceCategory category, LogicalPlace logicalPlace) {

        public enum Status {
            EXACT,
            LOGICAL_PLACE_MULTIPOINT,
            ENTITY_AMBIGUOUS,
            CATEGORY_ONLY,
            NOT_FOUND
        }

        public Resolution {
            candidates = List.copyOf(candidates);
        }

        static Resolution exact(SystemPlace place) {
            return new Resolution(Status.EXACT, place, List.of(place), place.category(), null);
        }

        static Resolution logicalMultiPoint(
                SystemPlace selected, List<SystemPlace> candidates, LogicalPlace logicalPlace) {
            return new Resolution(
                    Status.LOGICAL_PLACE_MULTIPOINT, selected, candidates,
                    selected.category(), logicalPlace);
        }

        static Resolution entityAmbiguous(
                List<SystemPlace> candidates, SystemPlaceCategory category) {
            return new Resolution(Status.ENTITY_AMBIGUOUS, null, candidates, category, null);
        }

        static Resolution categoryOnly(SystemPlaceCategory category) {
            return new Resolution(Status.CATEGORY_ONLY, null, List.of(), category, null);
        }

        static Resolution notFound() {
            return new Resolution(Status.NOT_FOUND, null, List.of(), null, null);
        }
    }

    private record CatalogSnapshot(
            Map<String, List<SystemPlace>> byAlias,
            List<SystemPlace> places,
            Map<SystemPlaceCategory, Long> categoryCounts, Map<String, Set<String>> searchTermsByKey,
            int size) {
    }
}
