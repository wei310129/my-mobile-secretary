package com.aproject.aidriven.mymobilesecretary.api.reminder;

import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提醒 API:查詢與確認。Phase 1 沒有 dashboard,這裡就是查提醒的入口。
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /** 列出提醒(新到舊)。 */
    @GetMapping
    public List<ReminderResponse> listReminders() {
        return reminderService.listReminders().stream().map(ReminderResponse::from).toList();
    }

    /** 查單一提醒;不存在 → 404。 */
    @GetMapping("/{reminderId}")
    public ReminderResponse getReminder(@PathVariable Long reminderId) {
        return ReminderResponse.from(reminderService.getReminder(reminderId));
    }

    /** 確認提醒已處理;不存在 → 404,重複確認 → 422。 */
    @PatchMapping("/{reminderId}/confirm")
    public ReminderResponse confirmReminder(@PathVariable Long reminderId) {
        return ReminderResponse.from(reminderService.confirmReminder(reminderId));
    }
}
