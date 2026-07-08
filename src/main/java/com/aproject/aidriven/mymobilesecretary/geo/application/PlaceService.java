package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 地點 use case:建立與查詢。
 */
@Service
@Transactional
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final Clock clock;

    public PlaceService(PlaceRepository placeRepository, Clock clock) {
        this.placeRepository = placeRepository;
        this.clock = clock;
    }

    /** 建立地點。經緯度合法性由 API 層 Bean Validation 把關。 */
    public Place createPlace(String name, String address, double latitude, double longitude, String type) {
        Place place = Place.create(name, address, latitude, longitude, type, Instant.now(clock));
        return placeRepository.save(place);
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
