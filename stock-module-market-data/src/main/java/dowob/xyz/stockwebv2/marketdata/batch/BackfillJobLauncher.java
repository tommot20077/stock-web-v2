package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.model.KlineInterval;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * {@link BackfillJobConfig#marketDataBackfillJob} 的啟動封裝，
 * 供 REST controller（T26）使用，隱藏 Spring Batch 的 JobParameters 細節。
 *
 * <p>{@code startedAt} 參數以當前毫秒時間戳加入，確保每次啟動的 JobParameters
 * 組合不同，使 Spring Batch 可接受重複觸發（不視為同一 Job Instance）。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class BackfillJobLauncher {

    private final JobLauncher jobLauncher;
    private final Job marketDataBackfillJob;

    /**
     * 建構子注入 JobLauncher 與 backfill Job。
     *
     * @param jobLauncher           Spring Batch Job Launcher
     * @param marketDataBackfillJob backfill Job bean（由 {@link BackfillJobConfig} 建立）
     */
    public BackfillJobLauncher(JobLauncher jobLauncher, Job marketDataBackfillJob) {
        this.jobLauncher = jobLauncher;
        this.marketDataBackfillJob = marketDataBackfillJob;
    }

    /**
     * 啟動 marketDataBackfillJob。
     *
     * <p>以 {@code symbol}、{@code from}、{@code to}、{@code interval}、{@code startedAt}
     * 組成 JobParameters 後交給 {@link JobLauncher#run} 執行。
     *
     * @param symbol   資產代號，不可為 null
     * @param from     回填起始時間（含），不可為 null
     * @param to       回填結束時間（不含），不可為 null
     * @param interval K 線粒度，不可為 null
     * @return {@link JobExecution}，可查詢 Job 狀態
     * @throws Exception 若 JobLauncher 或 JobRepository 操作失敗
     */
    public JobExecution launch(String symbol, Instant from, Instant to,
                               KlineInterval interval) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("symbol", symbol)
                .addString("from", from.toString())
                .addString("to", to.toString())
                .addString("interval", interval.code())
                .addLong("startedAt", System.currentTimeMillis())  // 確保每次不同，允許重複觸發
                .toJobParameters();
        return jobLauncher.run(marketDataBackfillJob, params);
    }
}
