package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 單人規劃偏好,例如每段交通或用餐要額外保留多久。 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_planning_preference_workspace", columnNames = "workspace_id"))
public class PlanningPreference extends WorkspaceOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private int extraTransferMinutes;

    @Column(nullable = false)
    private int mealBufferMinutes;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PlanningPreference() {
    }

    private PlanningPreference(Instant now) {
        this.updatedAt = now;
    }

    public static PlanningPreference create(Instant now) { return new PlanningPreference(now); }

    public void setBuffers(int transferMinutes, int mealMinutes, Instant now) {
        this.extraTransferMinutes = Math.max(transferMinutes, 0);
        this.mealBufferMinutes = Math.max(mealMinutes, 0);
        this.updatedAt = now;
    }

    public Integer getId() { return id; }
    public int getExtraTransferMinutes() { return extraTransferMinutes; }
    public int getMealBufferMinutes() { return mealBufferMinutes; }
    public Instant getUpdatedAt() { return updatedAt; }
}
