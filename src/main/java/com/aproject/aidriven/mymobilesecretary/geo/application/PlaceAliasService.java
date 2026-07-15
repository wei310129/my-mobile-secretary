package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.PlaceAlias;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceAliasRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
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
        return places.stream()
                .filter(p -> p.getName().contains(needle) || needle.contains(p.getName()))
                .findFirst();
    }

    public PlaceAlias remember(String alias, Long placeId) {
        if (aliasRepository.existsByAliasIgnoreCase(alias.strip())) {
            throw new BusinessException("DUPLICATE_PLACE_ALIAS", "地點別名「%s」已經存在。".formatted(alias));
        }
        placeService.getPlace(placeId);
        return aliasRepository.save(PlaceAlias.create(alias.strip(), placeId, Instant.now(clock)));
    }
}
