package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationSender;
import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderDelivery;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ReminderDeliveryService.class);

    private final List<NotificationSender> senders;
    private final ReminderDeliveryRepository deliveryRepository;
    private final Clock clock;

    /** senders 由 Spring 注入所有啟用的 NotificationSender(LOG 永遠在;toast 依設定)。 */
    public ReminderDeliveryService(List<NotificationSender> senders,
                                   ReminderDeliveryRepository deliveryRepository,
                                   Clock clock) {
        this.senders = senders;
        this.deliveryRepository = deliveryRepository;
        this.clock = clock;
    }

    /**
     * 對所有通道送出提醒,每通道各記一筆成敗。
     * 一個通道失敗不影響其他通道繼續送。
     */
    public void deliver(Reminder reminder, Task task) {
        ReminderNotification notification = new ReminderNotification(
                reminder.getId(), task.getId(), task.getTitle(), reminder.getTriggerReason());

        for (NotificationSender sender : senders) {
            String channel = sender.channel().name();
            Instant now = Instant.now(clock);
            try {
                sender.send(notification);
                deliveryRepository.save(ReminderDelivery.success(reminder.getId(), channel, now));
            } catch (Exception e) {
                // 只記錄,不中斷:其他通道照送,提醒核心不受影響
                log.warn("Notification delivery failed [channel={} reminder={}]", channel, reminder.getId(), e);
                deliveryRepository.save(ReminderDelivery.failure(reminder.getId(), channel, e.getMessage(), now));
            }
        }
    }
}
