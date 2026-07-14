package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** PriceRecord 資料存取。 */
public interface PriceRecordRepository extends JpaRepository<PriceRecord, Long> {

    /** 品項價格走勢(模糊比對:「鮮奶」要能撈到「林鳳營鮮奶」)。 */
    List<PriceRecord> findByItemNameContainingOrderByPurchasedAtDescIdDesc(String itemName);

    List<PriceRecord> findAllByOrderByPurchasedAtDescIdDesc();
}
