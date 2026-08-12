package com.aproject.aidriven.mymobilesecretary.planner.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.integration.route-planning")
public record RouteProviderPolicyProperties(
        @DefaultValue("TDX_PRIMARY") Strategy providerStrategy) {

    public enum Strategy {
        TDX_PRIMARY,
        GOOGLE_PRIMARY,
        GOOGLE_PRIMARY_TDX_CONFIRM
    }
}
