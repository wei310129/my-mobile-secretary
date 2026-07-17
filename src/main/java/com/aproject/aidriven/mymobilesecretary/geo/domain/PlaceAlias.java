package com.aproject.aidriven.mymobilesecretary.geo.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 使用者自己的地點叫法,例如「公司」「常去的全聯」。 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_place_alias_workspace_alias", columnNames = {"workspace_id", "alias"}))
public class PlaceAlias extends WorkspaceOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String alias;

    @Column(nullable = false)
    private Long placeId;

    @Column(nullable = false)
    private Instant createdAt;

    protected PlaceAlias() {
    }

    private PlaceAlias(String alias, Long placeId, Instant now) {
        this.alias = alias;
        this.placeId = placeId;
        this.createdAt = now;
    }

    public static PlaceAlias create(String alias, Long placeId, Instant now) {
        return new PlaceAlias(alias, placeId, now);
    }

    public Long getId() { return id; }
    public String getAlias() { return alias; }
    public Long getPlaceId() { return placeId; }
    public Instant getCreatedAt() { return createdAt; }
}
