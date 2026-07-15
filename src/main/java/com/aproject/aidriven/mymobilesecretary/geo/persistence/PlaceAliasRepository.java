package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.PlaceAlias;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceAliasRepository extends JpaRepository<PlaceAlias, Long> {
    Optional<PlaceAlias> findByAliasIgnoreCase(String alias);
    boolean existsByAliasIgnoreCase(String alias);
}
