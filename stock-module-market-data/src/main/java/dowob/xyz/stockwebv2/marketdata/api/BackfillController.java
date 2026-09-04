package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.marketdata.batch.BackfillJobLauncher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Backfill 觸發與狀態查詢 (ADMIN only)。
 *
 * <p>端點說明：
 * <ul>
 *   <li>{@code POST /api/v1/market/backfill} — 觸發 backfill job（需 ADMIN 角色）</li>
 *   <li>{@code GET /api/v1/market/backfill/{jobExecutionId}} — 查詢 job 執行狀態（需 ADMIN 角色）</li>
 * </ul>
 *
 * <p>安全機制：透過 {@link #requireAdmin(Authentication)} 驗證 ADMIN 角色，
 * 非 ADMIN 角色觸發 {@link ErrorCode#AUTH_FORBIDDEN}。
 *
 * @author Yuan
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/market/backfill")
public class BackfillController {

    /** Backfill 允許的最大時間範圍。 */
    public static final Duration MAX_RANGE = Duration.ofDays(90);

    private final BackfillJobLauncher launcher;
    private final BackfillIdempotencyService idempotencyService;
    private final JobExplorer jobExplorer;
    private final AuditLogger auditLogger;

    /**
     * 建構子注入所有依賴。
     *
     * @param launcher           BackfillJobLauncher，不可 null
     * @param idempotencyService idempotency 追蹤服務，不可 null
     * @param jobExplorer        Spring Batch JobExplorer，不可 null
     * @param auditLogger        稽核日誌，不可 null
     */
    public BackfillController(BackfillJobLauncher launcher,
                              BackfillIdempotencyService idempotencyService,
                              JobExplorer jobExplorer,
                              AuditLogger auditLogger) {
        this.launcher = launcher;
        this.idempotencyService = idempotencyService;
        this.jobExplorer = jobExplorer;
        this.auditLogger = auditLogger;
    }

    /**
     * 觸發 backfill job。需要 ADMIN 角色。
     *
     * <p>驗證流程：
     * <ol>
     *   <li>驗證 ADMIN 角色</li>
     *   <li>驗證時間範圍不超過 90 天</li>
     *   <li>驗證 interval 合法</li>
     *   <li>若有 {@code Idempotency-Key}，驗證非重複觸發</li>
     *   <li>啟動 job，回傳 202 + jobExecutionId</li>
     * </ol>
     *
     * @param request        請求 body，必填欄位均不可 null
     * @param idempotencyKey 選填的 HTTP header {@code Idempotency-Key}，1 小時內不可重複
     * @param authentication Spring Security 注入的當前請求身份
     * @return 202 Accepted + {@link BackfillTriggerResponse}
     * @throws Exception 若 JobLauncher 操作失敗
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BackfillTriggerResponse>> trigger(
            @Valid @RequestBody BackfillTriggerRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest) throws Exception {
        Long operatorId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        String target = "backfill:" + request.symbol();
        try {
            return doTrigger(request, idempotencyKey, authentication, operatorId, target, ip);
        } catch (BusinessException exception) {
            auditLogger.log(operatorId, "backfill_trigger", target, "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }

    /**
     * {@link #trigger} 的實際流程，抽出來讓稽核的 try/catch 保持單層。
     *
     * @param request        請求 body
     * @param idempotencyKey 選填的冪等鍵
     * @param authentication 當前請求身份
     * @param operatorId     操作者 userId
     * @param target         稽核目標字串
     * @param ip             來源 IP
     * @return 202 Accepted + 觸發結果
     * @throws Exception 若 JobLauncher 操作失敗
     */
    private ResponseEntity<ApiResponse<BackfillTriggerResponse>> doTrigger(
            BackfillTriggerRequest request,
            String idempotencyKey,
            Authentication authentication,
            Long operatorId,
            String target,
            String ip) throws Exception {
        requireAdmin(authentication);
        validateRange(request);

        KlineInterval interval;
        try {
            interval = KlineInterval.fromCode(request.interval());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.KLINE_INTERVAL_INVALID,
                "Invalid interval: " + request.interval());
        }

        if (idempotencyKey != null && !idempotencyService.tryAcquire(idempotencyKey)) {
            // 訊息刻意不含鍵值：冪等鍵完全由使用者控制，而錯誤訊息是使用者可見的輸出面。
            throw new BusinessException(ErrorCode.BACKFILL_ALREADY_RUNNING,
                "A backfill with the same idempotency key was already triggered within the last hour");
        }

        JobExecution execution = launcher.launch(request.symbol(), request.from(), request.to(), interval);
        BackfillTriggerResponse body = new BackfillTriggerResponse(
            execution.getId(), execution.getStatus().toString());
        auditLogger.log(operatorId, "backfill_trigger", target + ":job:" + execution.getId(), "success", ip);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(body, ApiMetaFactory.current()));
    }

    /**
     * 查詢 backfill job 執行狀態。需要 ADMIN 角色。
     *
     * @param jobExecutionId Spring Batch JobExecution ID
     * @param authentication Spring Security 注入的當前請求身份
     * @return 200 OK + {@link BackfillJobStatusDto}
     * @throws BusinessException {@link ErrorCode#BACKFILL_JOB_NOT_FOUND} 若 ID 不存在
     */
    @GetMapping("/{jobExecutionId}")
    public ApiResponse<BackfillJobStatusDto> status(
            @PathVariable("jobExecutionId") Long jobExecutionId,
            Authentication authentication) {
        requireAdmin(authentication);

        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.BACKFILL_JOB_NOT_FOUND,
                "Backfill job execution not found: " + jobExecutionId);
        }

        long readCount = execution.getStepExecutions().stream()
            .mapToLong(s -> s.getReadCount()).sum();
        long writeCount = execution.getStepExecutions().stream()
            .mapToLong(s -> s.getWriteCount()).sum();
        String failureMessage = execution.getAllFailureExceptions().isEmpty()
            ? null
            : execution.getAllFailureExceptions().get(0).getMessage();

        BackfillJobStatusDto body = new BackfillJobStatusDto(
            execution.getId(),
            execution.getStatus().toString(),
            toInstant(execution.getStartTime()),
            toInstant(execution.getEndTime()),
            readCount,
            writeCount,
            failureMessage
        );
        return ApiResponse.success(body, ApiMetaFactory.current());
    }

    /**
     * 驗證請求者具有 ADMIN 角色。
     *
     * <p>支援 {@code ROLE_ADMIN} 與 {@code ADMIN} 兩種格式（Spring Security 常用前者）。
     *
     * @param auth 當前請求身份，可為 null
     * @throws BusinessException {@link ErrorCode#AUTH_FORBIDDEN} 若無 ADMIN 角色
     */
    private void requireAdmin(Authentication auth) {
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(g -> g.getAuthority().equals("ROLE_ADMIN")
                             || g.getAuthority().equals("ADMIN"))) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "Admin role required");
        }
    }

    /**
     * 驗證 backfill 時間範圍合法（to 必須在 from 之後，且不超過 90 天）。
     *
     * @param request 請求 body
     * @throws BusinessException {@link ErrorCode#VALIDATION_FAILED} 若 to 不在 from 之後；
     *                           {@link ErrorCode#BACKFILL_RANGE_TOO_LARGE} 若超過 90 天
     */
    private void validateRange(BackfillTriggerRequest request) {
        if (!request.to().isAfter(request.from())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to must be after from");
        }
        if (Duration.between(request.from(), request.to()).compareTo(MAX_RANGE) > 0) {
            throw new BusinessException(ErrorCode.BACKFILL_RANGE_TOO_LARGE,
                "Backfill range exceeds 90 days");
        }
    }

    /**
     * 將 {@link LocalDateTime}（UTC）轉換為 {@link Instant}。
     *
     * @param ldt LocalDateTime，可為 null
     * @return 對應的 Instant，若輸入為 null 則回傳 null
     */
    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    /**
     * Backfill 觸發成功回應，包含 jobExecutionId 與初始狀態。
     *
     * @param jobExecutionId Spring Batch 產生的 execution ID
     * @param status         Job 啟動時的狀態（通常為 STARTED）
     */
    public record BackfillTriggerResponse(Long jobExecutionId, String status) {}
}
