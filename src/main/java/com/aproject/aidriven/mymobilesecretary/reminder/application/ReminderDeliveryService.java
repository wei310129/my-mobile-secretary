package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.planner.application.WeatherAdvisoryService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.shared.observability.SensitiveValueFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提醒送出:把已觸發的提醒推向所有啟用的通知通道,並逐通道記錄成敗。
 *
 * 關鍵規則:任何通道失敗只記錄、不往外丟——通知通道故障
 * 絕不能拖垮提醒閉環(提醒紀錄本身已落庫,可事後補查)。
 */
@Service
@Transactional
public class ReminderDeliveryService {

    private final NotificationPublisher notificationPublisher;
    private final WeatherAdvisoryService weatherAdvisoryService;

    public ReminderDeliveryService(NotificationPublisher notificationPublisher,
                                   WeatherAdvisoryService weatherAdvisoryService) {
        this.notificationPublisher = notificationPublisher;
        this.weatherAdvisoryService = weatherAdvisoryService;
    }

    /**
     * 對所有通道送出提醒,內文用提醒的觸發原因。
     */
    public void deliver(Reminder reminder, Task task) {
        deliver(reminder, task, reminder.getTriggerReason());
    }

    /**
     * 對所有通道送出提醒,每通道各記一筆成敗;內文可覆寫(升級催促用)。
     * 一個通道失敗不影響其他通道繼續送。
     * 有天氣風險時附上建議(取不到天氣就不附,不影響送出)。
     */
    public void deliver(Reminder reminder, Task task, String message) {
        String finalMessage = weatherAdvisoryService.currentAdvisory()
                .map(advisory -> message + "\n\n天氣提醒:\n" + advisory)
                .orElse(message);
        String deliveryKey = "reminder:" + reminder.getId() + ":"
                + SensitiveValueFingerprint.of(finalMessage);
        notificationPublisher.enqueue(new NotificationRequest(
                task.getCreatedByUserId(), deliveryKey, reminder.getId(), task.getId(),
                task.getTitle(), finalMessage));
    }
}
