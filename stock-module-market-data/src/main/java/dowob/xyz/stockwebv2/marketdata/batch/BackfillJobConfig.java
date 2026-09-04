package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.provider.PriceTick;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch 設定 — 定義 BackfillJob 與 BackfillStep。
 *
 * <p>Chunk size 設為 {@value #CHUNK_SIZE}，每 1000 筆 tick 為一個 transaction 單元。
 * 若整個 chunk 寫入失敗，只有該 chunk 會重試或進入 DLT，不影響其他 chunk。
 *
 * <p>Reader 與 Processor 均為 {@code @StepScope}，每次 Job 執行時從 Job Parameters 讀取
 * symbol、from、to、interval 等參數。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
public class BackfillJobConfig {

    /** Job 名稱，供 JobLauncher 與 JobRepository 識別。 */
    public static final String JOB_NAME = "marketDataBackfillJob";

    /** Step 名稱，供 Spring Batch metadata 儲存與查詢。 */
    public static final String STEP_NAME = "marketDataBackfillStep";

    /** 每個 transaction chunk 的 tick 筆數。 */
    public static final int CHUNK_SIZE = 1000;

    /**
     * 建立 marketDataBackfillJob。
     *
     * @param jobRepository          Spring Batch Job Repository，不可為 null
     * @param marketDataBackfillStep 唯一的 backfill step
     * @param listener               Job 生命週期 audit logger
     * @return 已設定 listener 與 step 的 {@link Job}
     */
    @Bean
    public Job marketDataBackfillJob(JobRepository jobRepository,
                                     Step marketDataBackfillStep,
                                     BackfillJobListener listener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(listener)
                .start(marketDataBackfillStep)
                .build();
    }

    /**
     * 建立 marketDataBackfillStep（Chunk-Oriented Processing）。
     *
     * <p>Reader：{@link HistoricalTickReader}（{@code @StepScope}）
     * Processor：{@link BackfillItemProcessor}（{@code @StepScope}）
     * Writer：{@link KafkaBackfillItemWriter}
     *
     * @param jobRepository      Spring Batch Job Repository
     * @param transactionManager 事務管理器，用於 chunk transaction boundary
     * @param reader             historical tick reader
     * @param processor          tick 轉換 processor
     * @param writer             Kafka backfill writer
     * @return 設定完成的 {@link Step}
     */
    @Bean
    public Step marketDataBackfillStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       HistoricalTickReader reader,
                                       BackfillItemProcessor processor,
                                       KafkaBackfillItemWriter writer) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<PriceTick, PriceTickEvent>chunk(CHUNK_SIZE, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }
}
