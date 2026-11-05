package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 股數調整帳本（{@code share_adjustments}）的一列（ADJ-01）。
 *
 * <p>沖銷列（{@code reversalOfId != null}）存與原列<strong>相同</strong>的值（含 {@code quantityDelta}），
 * 方向由 {@code reversalOfId} 表達；任何聚合都必須對沖銷列反號。</p>
 *
 * @param id             內部主鍵；尚未寫入時為 null
 * @param uuid           對外識別碼
 * @param userId         擁有者
 * @param assetId        標的主鍵（不 JOIN 資產主檔，代號與幣別經 AssetFacade 取得）
 * @param type           調整類型
 * @param ratioNew       比例分子（new），僅 {@link ShareAdjustmentType#SPLIT} 有值
 * @param ratioOld       比例分母（old），僅 {@link ShareAdjustmentType#SPLIT} 有值
 * @param quantityDelta  後端算出的股數變動，帶號（合股為負）
 * @param effectiveDate  除權日（該標的市場的日曆日）
 * @param effectiveAt    生效瞬間（除權日該市場交易日的起點）
 * @param note           備註；無則為 null
 * @param createdAt      寫入時間；尚未寫入時為 null
 * @param idempotencyKey 冪等鍵（不對外輸出）
 * @param reversalOfId   被沖銷的原列主鍵；一般紀錄為 null
 * @author Yuan
 * @version 1.0
 */
public record ShareAdjustmentEntry(
    Long id,
    UUID uuid,
    Long userId,
    Long assetId,
    ShareAdjustmentType type,
    Integer ratioNew,
    Integer ratioOld,
    BigDecimal quantityDelta,
    LocalDate effectiveDate,
    OffsetDateTime effectiveAt,
    String note,
    OffsetDateTime createdAt,
    String idempotencyKey,
    Long reversalOfId
) {
}
