package com.aproject.aidriven.mymobilesecretary.api.place;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Protocol-only API for the installation-owned place catalog. */
@RestController
@Validated
@RequestMapping("/api/place-catalog")
public class SystemPlaceCatalogController {

    private final SystemPlaceCatalogService catalogService;

    public SystemPlaceCatalogController(SystemPlaceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public SystemPlaceCatalogLookupResponse lookup(
            @RequestParam @NotBlank @Size(max = 200) String query,
            @RequestParam(required = false) @Size(max = 100) String region) {
        return SystemPlaceCatalogLookupResponse.from(catalogService.lookup(query, region));
    }

    @PostMapping("/{catalogKey}/adopt")
    public SystemPlaceCatalogAdoptionResponse adopt(@PathVariable String catalogKey) {
        return SystemPlaceCatalogAdoptionResponse.from(catalogService.adopt(catalogKey));
    }
}
