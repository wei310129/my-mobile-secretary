package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationException;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationSender;
import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderDelivery;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 提醒送出測試:每通道各記一筆成敗,單一通道失敗不影響其他通道、不往外丟例外。
 */
@ExtendWith(MockitoExtension.class)
class ReminderDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T10:00:00Z");

    @Mock
    private ReminderDeliveryRepository deliveryRepository;

    /** 記下收到的通知、可設定為失敗的假 sender。 */
    private static class StubSender implements NotificationSender {
        private final NotificationChannel channel;
        private final boolean failing;
        final List<ReminderNotification> received = new ArrayList<>();

        StubSender(NotificationChannel channel, boolean failing) {
            this.channel = channel;
            this.failing = failing;
        }

        @Override
        public NotificationChannel channel() {
            return channel;
        }

        @Override
        public void send(ReminderNotification notification) {
            received.add(notification);
            if (failing) {
                throw new NotificationException("channel down");
            }
        }
    }

    private Reminder reminder() {
        return Reminder.triggered(1L, "ENTER geofence: 全聯", NOW);
    }

    private Task task() {
        return Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
    }

    @Test
    void successfulDeliveryIsRecordedPerChannel() {
        StubSender logSender = new StubSender(NotificationChannel.LOG, false);
        StubSender toastSender = new StubSender(NotificationChannel.WINDOWS_TOAST, false);
        ReminderDeliveryService service = new ReminderDeliveryService(
                List.of(logSender, toastSender), deliveryRepository, Clock.fixed(NOW, ZoneOffset.UTC));

        service.deliver(reminder(), task());

        ArgumentCaptor<ReminderDelivery> captor = ArgumentCaptor.forClass(ReminderDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReminderDelivery::getChannel, ReminderDelivery::isSuccess)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LOG", true),
                        org.assertj.core.groups.Tuple.tuple("WINDOWS_TOAST", true));
        assertThat(logSender.received).hasSize(1);
        assertThat(logSender.received.get(0).title()).isEqualTo("買排骨");
    }

    /** 一個通道失敗:記失敗、不丟例外、其他通道照送。 */
    @Test
    void failingChannelIsRecordedAndOthersStillDeliver() {
        StubSender failing = new StubSender(NotificationChannel.WINDOWS_TOAST, true);
        StubSender logSender = new StubSender(NotificationChannel.LOG, false);
        ReminderDeliveryService service = new ReminderDeliveryService(
                List.of(failing, logSender), deliveryRepository, Clock.fixed(NOW, ZoneOffset.UTC));

        // 關鍵:通知失敗絕不能讓提醒核心炸掉
        assertThatCode(() -> service.deliver(reminder(), task())).doesNotThrowAnyException();

        ArgumentCaptor<ReminderDelivery> captor = ArgumentCaptor.forClass(ReminderDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReminderDelivery::getChannel, ReminderDelivery::isSuccess, ReminderDelivery::getErrorMessage)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("WINDOWS_TOAST", false, "channel down"),
                        org.assertj.core.groups.Tuple.tuple("LOG", true, null));
        assertThat(logSender.received).hasSize(1);
    }
}
