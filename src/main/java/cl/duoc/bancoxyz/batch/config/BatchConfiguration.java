package cl.duoc.bancoxyz.batch.config;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.item.support.builder.SynchronizedItemStreamReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.logging.Logger;

@Configuration
// Recursos compartidos del procesamiento por lotes.
public class BatchConfiguration {

    @Bean
    // Paralelismo limitado por configuración.
    public TaskExecutor batchTaskExecutor(@Value("${app.batch.threads:3}") int threads) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("Batch-Thread-");
        executor.initialize();
        return executor;
    }

    @Bean
    // Trazabilidad de ejecución y omisiones.
    public JobExecutionListener batchJobLogListener(org.springframework.jdbc.core.JdbcTemplate db) {
        return new JobExecutionListener() {
            private final Logger logger = Logger.getLogger(BatchConfiguration.class.getName());

            @Override
            public void beforeJob(JobExecution jobExecution) {
                logger.info(() -> "Iniciando Job: " + jobExecution.getJobInstance().getJobName());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus()==org.springframework.batch.core.BatchStatus.COMPLETED) {
                    String job=jobExecution.getJobInstance().getJobName();
                    long ejecucion=jobExecution.getJobInstance().getInstanceId();
                    int cambios=db.update("UPDATE publicaciones_batch SET ejecucion_id=?,publicada=CURRENT_TIMESTAMP WHERE job=? AND ejecucion_id<=?",
                            ejecucion,job,ejecucion);
                    if (cambios==0) {
                        try { db.update("INSERT INTO publicaciones_batch(job,ejecucion_id) VALUES (?,?)",job,ejecucion); }
                        catch(org.springframework.dao.DuplicateKeyException ignored) { }
                    }
                }
                logger.info(() -> "Job finalizado: " + jobExecution.getJobInstance().getJobName()
                        + " - estado=" + jobExecution.getStatus()
                        + " - omitidos=" + jobExecution.getStepExecutions().stream()
                        .filter(step -> !step.getStepName().endsWith("PartitionedStep"))
                        .mapToLong(step -> step.getSkipCount()).sum());
            }
        };
    }

    public static <T> SynchronizedItemStreamReader<T> synchronizedReader(
            org.springframework.batch.item.ItemStreamReader<T> reader) {
        // Lectura compartida sincronizada.
        return new SynchronizedItemStreamReaderBuilder<T>()
                .delegate(reader)
                .build();
    }
}
