package com.aproject.aidriven.mymobilesecretary.api.intent;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentIssueService;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意圖問題 API:開發前檢視 OPEN 清單、處理後標記狀態(使用者指定的工作流)。
 */
@RestController
@RequestMapping("/api/intent-issues")
public class IntentIssueController {

    private final IntentIssueService issueService;

    public IntentIssueController(IntentIssueService issueService) {
        this.issueService = issueService;
    }

    /** 列出意圖問題(?status=OPEN 過濾;不帶參數列全部,新到舊)。 */
    @GetMapping
    public List<IntentIssueResponse> list(@RequestParam(required = false) IntentIssue.Status status) {
        return issueService.list(status).stream().map(IntentIssueResponse::from).toList();
    }

    /** 標記已解決;非 OPEN → 422。 */
    @PatchMapping("/{issueId}/resolve")
    public IntentIssueResponse resolve(@PathVariable Long issueId) {
        return IntentIssueResponse.from(issueService.resolve(issueId));
    }

    /** 標記超出服務範圍(不處理);非 OPEN → 422。 */
    @PatchMapping("/{issueId}/out-of-scope")
    public IntentIssueResponse outOfScope(@PathVariable Long issueId) {
        return IntentIssueResponse.from(issueService.markOutOfScope(issueId));
    }
}
