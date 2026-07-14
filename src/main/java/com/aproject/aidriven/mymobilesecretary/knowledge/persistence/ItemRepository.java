package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;

/** Item 資料存取。 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    boolean existsByName(String name);

    java.util.Optional<Item> findByName(String name);
}
