package dowob.xyz.stockwebv2.marketdata.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * BackfillJob 開始與結束時寫 audit log。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class BackfillJobListener implements JobExecutionListener {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /**
     * Job 啟動前記錄 audit log。
     *
     * @param jobExecution 當前 Job 執行資訊
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        audit.info("BACKFILL_STARTED jobId={} params={}",
                jobExecution.getId(),
                jobExecution.getJobParameters().parameters());
    }

    /**
     * Job 結束後記錄 audit log，包含最終狀態與讀寫計數。
     *
     * @param jobExecution 當前 Job 執行資訊
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        audit.info("BACKFILL_FINISHED jobId={} status={} read={} written={}",
                jobExecution.getId(),
                jobExecution.getStatus(),
                jobExecution.getStepExecutions().stream()
                        .mapToLong(s -> s.getReadCount()).sum(),
                jobExecution.getStepExecutions().stream()
                        .mapToLong(s -> s.getWriteCount()).sum());
    }
}
