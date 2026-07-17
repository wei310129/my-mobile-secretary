package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Windows 桌面 toast 通知:Phase 1E 生活測試時讓提醒「真的跳出來」,
 * 而不是要使用者自己翻 log。
 *
 * 啟用條件:app.notification.windows-toast.enabled=true(只在 local profile 開;
 * VPS/測試環境沒有桌面,保持關閉)。
 *
 * 實作用 AWT SystemTray(免外部依賴);JVM 需以非 headless 模式啟動,
 * 見 MyMobileSecretaryApplication 的 setHeadless(false)。
 */
@Component
@ConditionalOnProperty(prefix = "app.notification.windows-toast", name = "enabled", havingValue = "true")
public class WindowsToastNotificationSender implements NotificationSender {

    /** 延遲初始化的常駐 tray icon;volatile + synchronized 保護並發初始化。 */
    private volatile TrayIcon trayIcon;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WINDOWS_TOAST;
    }

    @Override
    public Optional<String> destinationFor(UUID workspaceId, UUID targetUserId) {
        if (LegacyAccountIds.WORKSPACE_ID.equals(workspaceId)
                && LegacyAccountIds.USER_ID.equals(targetUserId)) {
            return Optional.of("local-windows");
        }
        return Optional.empty();
    }

    @Override
    public void send(ReminderNotification notification) {
        if (!"local-windows".equals(notification.destination())
                || !LegacyAccountIds.WORKSPACE_ID.equals(notification.workspaceId())
                || !LegacyAccountIds.USER_ID.equals(notification.targetUserId())) {
            throw new NotificationException("Windows toast destination is not the local owner");
        }
        try {
            ensureTrayIcon().displayMessage(
                    notification.title(), notification.message(), TrayIcon.MessageType.INFO);
        } catch (NotificationException e) {
            throw e;
        } catch (Exception e) {
            throw new NotificationException("Failed to show Windows toast", e);
        }
    }

    /** 第一次送通知時才掛上 tray icon(啟動時桌面環境可能尚未就緒)。 */
    private TrayIcon ensureTrayIcon() {
        TrayIcon existing = trayIcon;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (trayIcon == null) {
                if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                    throw new NotificationException("System tray not available (headless or unsupported)");
                }
                try {
                    TrayIcon icon = new TrayIcon(
                            new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "My Mobile Secretary");
                    icon.setImageAutoSize(true);
                    SystemTray.getSystemTray().add(icon);
                    trayIcon = icon;
                } catch (Exception e) {
                    throw new NotificationException("Failed to initialize system tray icon", e);
                }
            }
            return trayIcon;
        }
    }
}
