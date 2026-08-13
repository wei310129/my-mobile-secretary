package com.aproject.aidriven.mymobilesecretary.geo.domain;

/** Stable categories in the versioned, system-owned public-place catalog. */
public enum SystemPlaceCategory {
    METRO_STATION("捷運站"),
    SEAPORT("港口"),
    AIRPORT("機場"),
    HSR_STATION("高鐵站"),
    RAIL_STATION("火車站"),
    LOCAL_GOVERNMENT("縣市政府"),
    AMUSEMENT_PARK("觀光遊樂園");

    private final String publicLabel;

    SystemPlaceCategory(String publicLabel) {
        this.publicLabel = publicLabel;
    }

    public String publicLabel() {
        return publicLabel;
    }

    public static java.util.stream.Stream<SystemPlaceCategory> stream() {
        return java.util.Arrays.stream(values());
    }
}
