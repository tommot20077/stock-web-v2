package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * BackfillJob 開始與結束時寫入稽核日誌（security.md §13 管理操作）。
 *
 * <p>稽核事件一律透過 {@link AuditLogger} 以憲法規定的固定格式輸出；批次由排程／管理端觸發，
 * 無使用者與來源 IP 情境，故該兩欄位以佔位符呈現。讀寫計數屬營運觀測資訊而非稽核欄位，
 * 改以一般應用日誌輸出，避免破壞稽核格式的可解析性。</p>
 *
 * @author Yuan
 * @version 1.0.1
 */
@Component
public class BackfillJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(BackfillJobListener.class);

    private final AuditLogger auditLogger;

    public BackfillJobListener(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /**
     * Job 啟動前記錄稽核事件。
     *
     * @param jobExecution 當前 Job 執行資訊
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        auditLogger.log(null, "backfill_start", "job:" + jobExecution.getId(), "started", null);
        log.info("Backfill job {} 啟動，params={}", jobExecution.getId(), jobExecution.getJobParameters().parameters());
    }

    /**
     * Job 結束後記錄稽核事件，並以一般日誌輸出讀寫計數。
     *
     * @param jobExecution 當前 Job 執行資訊
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        auditLogger.log(null, "backfill_finish", "job:" + jobExecution.getId(),
            String.valueOf(jobExecution.getStatus()), null);
        log.info("Backfill job {} 結束，status={} read={} written={}",
            jobExecution.getId(),
            jobExecution.getStatus(),
            jobExecution.getStepExecutions().stream().mapToLong(step -> step.getReadCount()).sum(),
            jobExecution.getStepExecutions().stream().mapToLong(step -> step.getWriteCount()).sum());
    }
}
