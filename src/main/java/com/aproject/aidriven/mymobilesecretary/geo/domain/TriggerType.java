package com.aproject.aidriven.mymobilesecretary.geo.domain;

/**
 * geofence 觸發方向:進入或離開區域。
 * 對應 iOS Core Location region monitoring 的 enter/exit 事件。
 */
public enum TriggerType {
    ENTER,
    EXIT
}
