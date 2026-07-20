package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 地點 use case:建立與查詢。
 *
 * 建立時的補全規則(使用者 2026-07-15 拍板):只給名字也能建——
 * 用 Google Places 抓完整資訊(地址/座標/類型);使用者自己給的欄位永遠優先,
 * Google 只補空缺。
 */
@Service
@Transactional
public class PlaceService {

    private static final Logger log = LoggerFactory.getLogger(PlaceService.class);

    private final PlaceRepository placeRepository;
    private final GooglePlacesClient googlePlacesClient;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PlaceService(PlaceRepository placeRepository, GooglePlacesClient googlePlacesClient,
                        ApplicationEventPublisher eventPublisher, Clock clock) {
        this.placeRepository = placeRepository;
        this.googlePlacesClient = googlePlacesClient;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 建立地點。座標可空:空時向 Google 查(名字+地址當查詢詞);
     * Google 未設定或查不到 → 明確業務錯誤,請使用者補座標,不猜位置。
     */
    public Place createPlace(String name, String address, Double latitude, Double longitude, String type) {
        if (latitude == null || longitude == null) {
            GooglePlacesClient.PlaceCandidate candidate = lookupOrThrow(name, address);
            latitude = candidate.latitude();
            longitude = candidate.longitude();
            if (address == null || address.isBlank()) {
                address = candidate.address();
            }
            if (type == null || type.isBlank()) {
                type = candidate.type();
            }
        }
        Place place = Place.create(name, address, latitude, longitude, type, Instant.now(clock));
        Place saved = placeRepository.save(place);
        eventPublisher.publishEvent(new PlaceCreatedEvent(
                saved.getId(), saved.getName(), saved.getType(), saved.getCreatedAt()));
        return saved;
    }

    /** Google 查詢;每一種失敗都要給使用者可行動的訊息。 */
    private GooglePlacesClient.PlaceCandidate lookupOrThrow(String name, String address) {
        if (!googlePlacesClient.usable()) {
            throw new BusinessException("MISSING_COORDINATES",
                    "未提供座標,且 Google 地點查詢未設定(secrets.yaml 缺 api-key),請直接提供經緯度");
        }
        String query = address == null || address.isBlank() ? name : name + " " + address;
        Optional<GooglePlacesClient.PlaceCandidate> candidate;
        try {
            candidate = googlePlacesClient.searchFirst(query);
        } catch (Exception e) {
            log.warn("Google Places lookup failed [query={}]", query, e);
            throw new BusinessException("PLACE_LOOKUP_FAILED",
                    "Google 地點查詢暫時失敗,請稍後再試或直接提供經緯度");
        }
        return candidate.orElseThrow(() -> new BusinessException("PLACE_NOT_FOUND_ON_GOOGLE",
                "Google 查不到「%s」,請換個名稱或直接提供經緯度".formatted(query)));
    }

    @Transactional(readOnly = true)
    public List<Place> listPlaces() {
        return placeRepository.findAll();
    }

    /** 查單一地點;不存在丟 NotFoundException(404)。 */
    @Transactional(readOnly = true)
    public Place getPlace(Long placeId) {
        return placeRepository.findById(placeId)
                .orElseThrow(() -> new NotFoundException("Place", placeId));
    }
}
