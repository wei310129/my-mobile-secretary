package com.aproject.aidriven.mymobilesecretary.api.place;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建立地點的請求。緯度 -90~90、經度 -180~180。
 *
 * 座標選填:沒給就由後端向 Google Places 補全(地址/座標/類型),
 * Google 未設定或查不到時回業務錯誤請使用者補座標。
 *
 * @param type 地點類型,自由文字(如「超市」「菜市場」),選填
 */
public record CreatePlaceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 300) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Size(max = 50) String type
) {
}
