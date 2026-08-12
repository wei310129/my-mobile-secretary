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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern STREET_NUMBER = Pattern.compile(
            "([\\p{IsHan}]{1,20}(?:路|街|大道|巷)\\s*\\d+(?:之\\d+)?號)");

    private final PlaceRepository placeRepository;
    private final SystemPlaceCatalog systemPlaceCatalog;
    private final GooglePlacesClient googlePlacesClient;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PlaceService(PlaceRepository placeRepository, SystemPlaceCatalog systemPlaceCatalog,
                        GooglePlacesClient googlePlacesClient,
                        ApplicationEventPublisher eventPublisher, Clock clock) {
        this.placeRepository = placeRepository;
        this.systemPlaceCatalog = systemPlaceCatalog;
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
            } else if (!sameStreetNumber(address, candidate.address())) {
                throw new BusinessException("PLACE_ADDRESS_MISMATCH",
                        "查到的候選地址與你提供的街路門牌不一致，因此沒有建立。"
                                + "請確認分店、地址或提供 Google Maps 連結。");
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

    /** Resolves one provider candidate without creating user-owned data. */
    @Transactional(readOnly = true)
    public ResolvedPlaceCandidate resolvePlaceCandidate(String query) {
        GooglePlacesClient.PlaceCandidate candidate;
        if (GooglePlacesClient.isGoogleMapsLink(query)) {
            try {
                candidate = googlePlacesClient.resolveMapsLink(query)
                        .orElseThrow(() -> new BusinessException(
                                "GOOGLE_MAPS_LINK_UNRESOLVED",
                                "這個 Google Maps 連結沒有足夠的地點資訊，請改傳地點頁面的分享連結或完整地址。"));
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("Google Maps link resolution failed ({})", exception.getClass().getSimpleName());
                throw new BusinessException(
                        "GOOGLE_MAPS_LINK_UNRESOLVED",
                        "目前無法讀取這個 Google Maps 連結，請重傳分享連結或改提供完整地址。");
            }
        } else {
            if (isHttpLink(query)) {
                throw new BusinessException(
                        "UNSUPPORTED_PLACE_LINK",
                        "目前只接受 Google Maps 地點連結，請改傳 Google Maps 分享連結、地點名稱或地址。");
            }
            candidate = lookupOrThrow(query, null);
        }
        if (!Double.isFinite(candidate.latitude()) || !Double.isFinite(candidate.longitude())
                || candidate.latitude() < -90 || candidate.latitude() > 90
                || candidate.longitude() < -180 || candidate.longitude() > 180) {
            throw new BusinessException("PLACE_COORDINATES_INVALID", "查到的地點缺少可用座標，請換一個更完整的地點名稱。");
        }
        return new ResolvedPlaceCandidate(
                candidate.name(),
                candidate.address(),
                candidate.latitude(),
                candidate.longitude(),
                candidate.type());
    }

    /** Returns typed unavailability without allowing a provider exception to mark the caller rollback-only. */
    @Transactional(readOnly = true)
    public Optional<ResolvedPlaceCandidate> findPlaceCandidate(String query) {
        try {
            return Optional.of(resolvePlaceCandidate(query));
        } catch (BusinessException exception) {
            return Optional.empty();
        }
    }

    /** Persists only a previously validated typed candidate. */
    public Place createResolvedPlace(ResolvedPlaceCandidate candidate) {
        if (candidate == null) throw new IllegalArgumentException("resolved place candidate is required");
        return createPlace(
                candidate.name(),
                candidate.address(),
                candidate.latitude(),
                candidate.longitude(),
                candidate.type());
    }

    /**
     * 更新既有地點地址。使用者原文地址保留為 source of truth；Google 只供座標，
     * 但候選街路門牌不一致時整筆拒絕，避免把民權路寫成北宜路。
     */
    public Place updateAddress(Long placeId, String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address is required");
        }
        Place place = getPlace(placeId);
        GooglePlacesClient.PlaceCandidate candidate = lookupOrThrow(place.getName(), address.strip());
        if (!sameStreetNumber(address, candidate.address())) {
            throw new BusinessException("PLACE_ADDRESS_MISMATCH",
                    "查到的候選地址「%s」與你提供的「%s」街路門牌不一致，因此沒有更新。請確認地址或提供 Google Maps 連結。"
                            .formatted(candidate.address(), address.strip()));
        }
        Instant now = Instant.now(clock);
        place.relocate(address, candidate.latitude(), candidate.longitude(), candidate.type());
        Place saved = placeRepository.save(place);
        eventPublisher.publishEvent(new PlaceUpdatedEvent(
                saved.getId(), saved.getName(), now));
        return saved;
    }

    static boolean sameStreetNumber(String supplied, String candidate) {
        String suppliedKey = streetNumber(supplied);
        String candidateKey = streetNumber(candidate);
        return suppliedKey != null && suppliedKey.equals(candidateKey);
    }

    private static String streetNumber(String address) {
        if (address == null || address.isBlank()) return null;
        Matcher matcher = STREET_NUMBER.matcher(
                java.text.Normalizer.normalize(address, java.text.Normalizer.Form.NFKC)
                        .replaceAll("\\s+", ""));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isHttpLink(String value) {
        if (value == null) return false;
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
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

    /** Looks up public place information without creating, updating, or publishing domain data. */
    @Transactional(readOnly = true)
    public PublicPlaceLookup lookupPublicPlace(String name) {
        if (name == null || name.isBlank()) {
            return PublicPlaceLookup.unavailable();
        }
        SystemPlaceCatalog.Lookup systemLookup = systemPlaceCatalog.lookup(name);
        if (systemLookup.status() == SystemPlaceCatalog.Lookup.Status.FOUND) {
            SystemPlaceCatalog.SystemPlace place = systemLookup.place();
            return PublicPlaceLookup.foundInSystemCatalog(
                    place.name(), place.location(), place.category().name());
        }
        if (systemLookup.status() == SystemPlaceCatalog.Lookup.Status.AMBIGUOUS) {
            return PublicPlaceLookup.ambiguous();
        }
        if (!googlePlacesClient.usable()) return PublicPlaceLookup.unavailable();
        try {
            return googlePlacesClient.searchFirst(name.strip())
                    .map(candidate -> PublicPlaceLookup.foundFromProvider(
                            candidate.name(), candidate.address(), candidate.type()))
                    .orElseGet(PublicPlaceLookup::notFound);
        } catch (Exception exception) {
            log.warn("Public place lookup failed ({})", exception.getClass().getSimpleName());
            return PublicPlaceLookup.unavailable();
        }
    }

    public record PublicPlaceLookup(
            Status status, Source source, String name, String address, String type) {
        public enum Status { FOUND, AMBIGUOUS, NOT_FOUND, UNAVAILABLE }

        public enum Source { SYSTEM_CATALOG, EXTERNAL_PROVIDER, NONE }

        public static PublicPlaceLookup found(String name, String address, String type) {
            return foundFromProvider(name, address, type);
        }

        public static PublicPlaceLookup foundInSystemCatalog(
                String name, String address, String category) {
            return new PublicPlaceLookup(
                    Status.FOUND, Source.SYSTEM_CATALOG, name, address, category);
        }

        public static PublicPlaceLookup foundFromProvider(
                String name, String address, String type) {
            return new PublicPlaceLookup(
                    Status.FOUND, Source.EXTERNAL_PROVIDER, name, address, type);
        }

        public static PublicPlaceLookup ambiguous() {
            return new PublicPlaceLookup(Status.AMBIGUOUS, Source.SYSTEM_CATALOG, null, null, null);
        }

        public static PublicPlaceLookup notFound() {
            return new PublicPlaceLookup(Status.NOT_FOUND, Source.NONE, null, null, null);
        }

        public static PublicPlaceLookup unavailable() {
            return new PublicPlaceLookup(Status.UNAVAILABLE, Source.NONE, null, null, null);
        }
    }

    public record ResolvedPlaceCandidate(
            String name, String address, double latitude, double longitude, String type) {

        public ResolvedPlaceCandidate {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("resolved place name is required");
            }
        }
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
