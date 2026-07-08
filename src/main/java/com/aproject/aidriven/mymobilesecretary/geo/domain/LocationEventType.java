package com.aproject.aidriven.mymobilesecretary.geo.domain;

/**
 * 位置事件種類。
 *
 * MANUAL_PING 是使用者手動回報「我現在在這」——語意上視同「人在區域內」,
 * geofence 比對時等同 ENTER 處理。保留它是為了 Phase 1E 用 curl 做真實測試時方便。
 */
public enum LocationEventType {
    ENTER,
    EXIT,
    MANUAL_PING
}
