package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Place 資料存取。 */
public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 找出座標半徑內的地點(geography + ST_DWithin,公尺計算真實球面距離)。
     * 距離規則集中在 geo 模組的 PostGIS 查詢,其他模組不得自行算距離。
     */
    @Query(value = """
            SELECT p.* FROM place p
            WHERE ST_DWithin(
                  ST_SetSRID(ST_MakePoint(p.longitude, p.latitude), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                  :radiusMeters)
            """, nativeQuery = true)
    List<Place> findWithinRadius(@Param("latitude") double latitude,
                                 @Param("longitude") double longitude,
                                 @Param("radiusMeters") double radiusMeters);
}
