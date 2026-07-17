package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 價格紀錄:某品項某天在某店的單價(收據解析落地的最小事實單位)。
 */
@Entity
public class PriceRecord extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 對到品項知識庫;名稱對不到就 NULL,價格照存(之後建品項可回溯)。 */
    private Long itemId;

    @Column(nullable = false, length = 100)
    private String itemName;

    @Column(length = 100)
    private String storeName;

    @Column(nullable = false)
    private int priceTwd;

    @Column(nullable = false)
    private LocalDate purchasedAt;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA 專用。 */
    protected PriceRecord() {
    }

    private PriceRecord(Long itemId, String itemName, String storeName,
                        int priceTwd, LocalDate purchasedAt, Instant now) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.storeName = storeName;
        this.priceTwd = priceTwd;
        this.purchasedAt = purchasedAt;
        this.createdAt = now;
    }

    /** 建立價格紀錄;價格必須為正(0 元多半是解析錯誤,不能汙染價格歷史)。 */
    public static PriceRecord record(Long itemId, String itemName, String storeName,
                                     int priceTwd, LocalDate purchasedAt, Instant now) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("INVALID_PRICE_RECORD", "itemName must not be blank");
        }
        if (priceTwd <= 0) {
            throw new BusinessException("INVALID_PRICE_RECORD", "priceTwd must be positive");
        }
        return new PriceRecord(itemId, itemName.strip(), storeName, priceTwd, purchasedAt, now);
    }

    public Long getId() {
        return id;
    }

    public Long getItemId() {
        return itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public String getStoreName() {
        return storeName;
    }

    public int getPriceTwd() {
        return priceTwd;
    }

    public LocalDate getPurchasedAt() {
        return purchasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
