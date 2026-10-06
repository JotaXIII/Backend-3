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
// Define los recursos compartidos por los procesos batch.
public class BatchConfiguration {

    @Bean
    // Limita la ejecución paralela al número de hilos configurado.
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
    // Registra el inicio, término, estado y omisiones de cada Job.
    public JobExecutionListener batchJobLogListener() {
        return new JobExecutionListener() {
            private final Logger logger = Logger.getLogger(BatchConfiguration.class.getName());

            @Override
            public void beforeJob(JobExecution jobExecution) {
                logger.info(() -> "Iniciando Job: " + jobExecution.getJobInstance().getJobName());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
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
        // Protege la lectura compartida cuando varios hilos procesan chunks.
        return new SynchronizedItemStreamReaderBuilder<T>()
                .delegate(reader)
                .build();
    }
}
