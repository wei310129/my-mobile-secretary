package com.aproject.aidriven.mymobilesecretary.api.intent;

import com.aproject.aidriven.mymobilesecretary.api.schedule.ScheduleDecisionResponse;
import com.aproject.aidriven.mymobilesecretary.api.task.TaskResponse;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意圖 API:一句話 → 任務或行程。未來 iOS(Siri 捷徑)與 LINE bot 都打這一支。
 */
@RestController
@RequestMapping("/api/intent")
public class IntentController {

    private final IntentService intentService;

    public IntentController(IntentService intentService) {
        this.intentService = intentService;
    }

    /** 使用者的一句話。 */
    public record IntentRequest(@NotBlank @Size(max = 500) String text) {
    }

    /** 結果:做了什麼 + 對應物件(任務或行程決策擇一)。 */
    public record IntentResponse(String action, String message,
                                 TaskResponse task, ScheduleDecisionResponse schedule) {
    }

    @PostMapping
    public IntentResponse handle(@Valid @RequestBody IntentRequest request) {
        IntentResult result = intentService.handle(request.text());
        return new IntentResponse(
                result.action().name(),
                result.message(),
                result.task() == null ? null : TaskResponse.from(result.task()),
                result.decision() == null ? null : ScheduleDecisionResponse.from(result.decision()));
    }
}
