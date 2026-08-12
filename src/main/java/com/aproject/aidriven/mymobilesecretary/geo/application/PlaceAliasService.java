package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.PlaceAlias;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceAliasRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 地點名稱與使用者別名的確定性解析。 */
@Service
@Transactional
public class PlaceAliasService {
    private final PlaceAliasRepository aliasRepository;
    private final PlaceRepository placeRepository;
    private final PlaceService placeService;
    private final Clock clock;

    public PlaceAliasService(PlaceAliasRepository aliasRepository, PlaceRepository placeRepository,
                             PlaceService placeService, Clock clock) {
        this.aliasRepository = aliasRepository;
        this.placeRepository = placeRepository;
        this.placeService = placeService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Place> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = name.strip();
        Optional<PlaceAlias> alias = aliasRepository.findByAliasIgnoreCase(needle);
        if (alias.isPresent()) {
            return placeRepository.findById(alias.get().getPlaceId());
        }
        var places = placeRepository.findAll();
        Optional<Place> exact = places.stream()
                .filter(p -> p.getName().equalsIgnoreCase(needle)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        // 使用者輸入比既有名稱更長時,多出的字通常是分店／區域限定
        // (實際案例:「新店七張的夏恩英語」不能被舊的「夏恩英語」台北店吃掉)。
        // 只允許「既有完整名稱包含使用者縮寫」,不允許反向以短名稱攔截更具體查詢。
        return places.stream()
                .filter(p -> p.getName().contains(needle))
                .findFirst();
    }

    /** Longest unique actor-owned name or alias mentioned in a natural read-only question. */
    @Transactional(readOnly = true)
    public Optional<Place> resolveMention(String text) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) return Optional.empty();
        LinkedHashMap<Long, Integer> matches = new LinkedHashMap<>();
        for (PlaceAlias alias : aliasRepository.findAll()) {
            addMention(matches, alias.getPlaceId(), alias.getAlias(), normalizedText);
        }
        List<Place> places = placeRepository.findAll();
        for (Place place : places) {
            if (place.getId() != null) {
                addMention(matches, place.getId(), place.getName(), normalizedText);
            }
        }
        int longest = matches.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Long> ids = matches.entrySet().stream()
                .filter(entry -> entry.getValue() == longest)
                .map(java.util.Map.Entry::getKey).distinct().toList();
        if (ids.size() != 1) return Optional.empty();
        return places.stream().filter(place -> ids.getFirst().equals(place.getId())).findFirst()
                .or(() -> placeRepository.findById(ids.getFirst()));
    }

    private static void addMention(
            LinkedHashMap<Long, Integer> matches, Long placeId, String candidate, String text) {
        String normalized = normalize(candidate);
        if (placeId != null && normalized.length() >= 2 && text.contains(normalized)) {
            matches.merge(placeId, normalized.length(), Math::max);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('臺', '台')
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public PlaceAlias remember(String alias, Long placeId) {
        if (aliasRepository.existsByAliasIgnoreCase(alias.strip())) {
            throw new BusinessException("DUPLICATE_PLACE_ALIAS", "地點別名「%s」已經存在。".formatted(alias));
        }
        placeService.getPlace(placeId);
        return aliasRepository.save(PlaceAlias.create(alias.strip(), placeId, Instant.now(clock)));
    }
}
