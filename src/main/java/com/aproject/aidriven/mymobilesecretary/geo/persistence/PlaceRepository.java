package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import org.springframework.data.jpa.repository.JpaRepository;

/** Place 資料存取。Phase 1B 會在這裡加 PostGIS 半徑查詢。 */
public interface PlaceRepository extends JpaRepository<Place, Long> {
}
