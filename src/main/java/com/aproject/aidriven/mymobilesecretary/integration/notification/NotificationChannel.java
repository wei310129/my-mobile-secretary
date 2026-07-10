package com.aproject.aidriven.mymobilesecretary.integration.notification;

/**
 * 通知通道。Phase 1 只有本機替代方案;APNS 在有 Mac 與 Apple Developer 帳號後補上。
 */
public enum NotificationChannel {
    LOG,
    WINDOWS_TOAST,
    /** 尚未實作,保留介面邊界。 */
    APNS
}
