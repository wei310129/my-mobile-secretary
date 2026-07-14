package com.aproject.aidriven.mymobilesecretary.api.item;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 價格歷史 API:收據解析累積的品項價格查詢。
 */
@RestController
@RequestMapping("/api/price-records")
public class PriceRecordController {

    private final PriceRecordService priceRecordService;

    public PriceRecordController(PriceRecordService priceRecordService) {
        this.priceRecordService = priceRecordService;
    }

    /** 查價格歷史(?itemName=鮮奶 模糊比對;不帶參數列出全部,新到舊)。 */
    @GetMapping
    public List<PriceRecordResponse> list(@RequestParam(required = false) String itemName) {
        return priceRecordService.list(itemName).stream().map(PriceRecordResponse::from).toList();
    }
}
