package com.aproject.aidriven.mymobilesecretary.geo.domain;

/**
 * 兩點球面距離(haversine)。
 *
 * 關鍵規則:距離計算集中在 geo 模組——planner 的可行性驗算、之後的順路判斷
 * 都用這裡,不得各自重寫。(geofence 命中走 PostGIS,是另一條路;
 * 這裡是純 Java 版,給不經過資料庫的點對點估算用。)
 */
public final class GeoDistance {

    /** 地球平均半徑(公尺)。 */
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoDistance() {
    }

    /** 兩座標的直線(大圓)距離,單位公尺。 */
    public static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
