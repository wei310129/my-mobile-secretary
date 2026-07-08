package com.aproject.aidriven.mymobilesecretary.api.place;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地點 API。
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    /** 建立地點 → 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceResponse createPlace(@Valid @RequestBody CreatePlaceRequest request) {
        return PlaceResponse.from(placeService.createPlace(
                request.name(), request.address(), request.latitude(), request.longitude(), request.type()));
    }

    /** 列出全部地點。 */
    @GetMapping
    public List<PlaceResponse> listPlaces() {
        return placeService.listPlaces().stream().map(PlaceResponse::from).toList();
    }
}
