package com.aproject.aidriven.mymobilesecretary.api.item;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 品項知識 API:登錄「這個東西在哪裡買得到」。
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    /** 登錄品項 → 201;名稱重複 → 422;地點不存在 → 404。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse createItem(@Valid @RequestBody CreateItemRequest request) {
        return ItemResponse.from(itemService.createItem(request.name(), request.placeIds()));
    }

    /** 列出全部品項知識。 */
    @GetMapping
    public List<ItemResponse> listItems() {
        return itemService.listItems().stream().map(ItemResponse::from).toList();
    }
}
