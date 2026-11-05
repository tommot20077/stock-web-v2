package dowob.xyz.stockwebv2.asset.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 建立 / 改名觀察清單的請求（{@code POST /api/v1/watchlists}、{@code PATCH /api/v1/watchlists/{id}}）。
 *
 * <p>這裡只擋缺漏與全空白；長度上限（trim 後 1–30 字，以 code point 計）在 service 層判定。刻意不加
 * {@code @Size(max = 30)}：它以 UTF-16 長度計、且在 trim 之前檢查，前後帶空白的合法名稱與 emoji 名稱會被誤擋，
 * 與「trim 後 1–30 字」的契約不一致。兩條路徑失敗時都回 400 {@code VALIDATION_FAILED}、{@code fields.name}。</p>
 *
 * @param name 清單名稱
 * @author Yuan
 * @version 1.0
 */
public record WatchlistNameRequest(@NotBlank String name) {
}
