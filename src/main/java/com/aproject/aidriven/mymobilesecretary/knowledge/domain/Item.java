package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 物品知識:某個品項可以在哪些地點買到。
 *
 * 這是「自動綁定」的事實來源:任務標題出現品項名 → 自動綁到所有可購買地點。
 * Phase 2 先存地點 id 的直接關聯;之後知識庫擴充(冷藏需求、重量、營業時間風險)再加欄位。
 */
@Entity
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    /** 可購買地點 id 集合。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "item_place", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "place_id", nullable = false)
    private Set<Long> placeIds = new LinkedHashSet<>();

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA 專用。 */
    protected Item() {
    }

    private Item(String name, Set<Long> placeIds, Instant now) {
        this.name = name;
        this.placeIds = new LinkedHashSet<>(placeIds);
        this.createdAt = now;
    }

    /** 建立品項知識。名稱唯一性與地點存在性由 application 層把關。 */
    public static Item create(String name, Set<Long> placeIds, Instant now) {
        return new Item(name, placeIds, now);
    }

    /** 任務標題是否提到這個品項(中文無斷詞,包含即視為提到)。 */
    public boolean isMentionedIn(String text) {
        return text != null && text.contains(name);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Long> getPlaceIds() {
        return Set.copyOf(placeIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
