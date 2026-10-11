package cl.duoc.bancoxyz.batch.config;

import cl.duoc.bancoxyz.batch.domain.Transaccion;
import cl.duoc.bancoxyz.batch.domain.TransaccionCsv;
import cl.duoc.bancoxyz.batch.processor.TransaccionProcessor;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.support.RegistroOmitidoListener;
import cl.duoc.bancoxyz.batch.support.CsvPartitioner;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

@Configuration
// Lectura, transformación y resumen de transacciones.
public class TransaccionesJobConfig {

    @Bean
    // Etapas de procesamiento secuenciales.
    public Job transaccionesJob(JobRepository repository, Step limpiarTransaccionesStep,
                                Step procesarTransaccionesPartitionedStep, Step resumenTransaccionesStep,
                                org.springframework.batch.core.JobExecutionListener batchJobLogListener) {
        return new JobBuilder("transaccionesJob", repository)
                .incrementer(new org.springframework.batch.core.launch.support.RunIdIncrementer())
                .start(limpiarTransaccionesStep)
                .next(procesarTransaccionesPartitionedStep)
                .next(resumenTransaccionesStep)
                .listener(batchJobLogListener)
                .build();
    }

    @Bean
    // Resultados independientes por ejecución.
    public Step limpiarTransaccionesStep(JobRepository repository,
                                         PlatformTransactionManager transactionManager,
                                         JdbcTemplate jdbcTemplate) {
        return new StepBuilder("limpiarTransaccionesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("DELETE FROM transacciones_procesadas WHERE ejecucion_id=?", context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    jdbcTemplate.update("DELETE FROM resumen_transacciones WHERE ejecucion_id=?", context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    jdbcTemplate.update("DELETE FROM registros_rechazados WHERE job = \'transaccionesJob\' AND ejecucion_id=?", context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Rango de registros por partición.
    public Step procesarTransaccionesStep(JobRepository repository,
                                           PlatformTransactionManager transactionManager,
                                           @Value("${app.batch.chunk-size:100}") int chunkSize,
                                           FlatFileItemReader<TransaccionCsv> transaccionesReader,
                                           JdbcBatchItemWriter<Transaccion> transaccionesWriter,
                                           org.springframework.batch.item.ItemProcessor<TransaccionCsv,Transaccion> transaccionesProcessor,
                                           JdbcTemplate jdbcTemplate) {
        return new StepBuilder("procesarTransaccionesStep", repository)
                .<TransaccionCsv, Transaccion>chunk(chunkSize, transactionManager)
                .reader(transaccionesReader)
                .processor(transaccionesProcessor)
                .writer(transaccionesWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skip(FlatFileParseException.class)
                .skip(DataIntegrityViolationException.class)
                .skipLimit(100)
                .retry(CannotAcquireLockException.class)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .retry(org.springframework.jdbc.CannotGetJdbcConnectionException.class)
                .retryLimit(3)
                .listener(new RegistroOmitidoListener<TransaccionCsv, Transaccion>(jdbcTemplate,
                        "transaccionesJob"))
                .build();
    }

    @Bean
    public Partitioner transaccionesPartitioner(
            @Value("${app.archivos.transacciones}") Resource resource,
            @Value("${app.batch.grid-size:3}") int gridSize) {
        return new CsvPartitioner(resource, gridSize);
    }

    @Bean
    public Step procesarTransaccionesPartitionedStep(JobRepository repository,
                                                      Step procesarTransaccionesStep,
                                                      Partitioner transaccionesPartitioner,
                                                      TaskExecutor batchTaskExecutor,
                                                      @Value("${app.batch.grid-size:3}") int gridSize) {
        var handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(batchTaskExecutor);
        handler.setStep(procesarTransaccionesStep);
        handler.setGridSize(gridSize);
        return new StepBuilder("procesarTransaccionesPartitionedStep", repository)
                .partitioner("procesarTransaccionesStep", transaccionesPartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    // Resumen de transacciones por tipo.
    public Step resumenTransaccionesStep(JobRepository repository,
                                          PlatformTransactionManager transactionManager,
                                          JdbcTemplate jdbcTemplate) {
        return new StepBuilder("resumenTransaccionesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("""
                            INSERT INTO resumen_transacciones(ejecucion_id, fecha, tipo, cantidad, monto_total, anomalias)
                            SELECT ejecucion_id, fecha, tipo, COUNT(*), SUM(monto),
                                   SUM(CASE WHEN anomalia THEN 1 ELSE 0 END)
                            FROM transacciones_procesadas WHERE ejecucion_id=? GROUP BY ejecucion_id, fecha, tipo
                            """, context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Entrada de transacciones delimitada.
    @StepScope
    public FlatFileItemReader<TransaccionCsv> transaccionesReader(
            @Value("${app.archivos.transacciones}") Resource resource,
            @Value("#{stepExecutionContext['start']}") Integer start,
            @Value("#{stepExecutionContext['end']}") Integer end) {
        var reader = new FlatFileItemReaderBuilder<TransaccionCsv>()
                .name("transaccionesReader")
                .resource(resource)
                .linesToSkip(1)
                .delimited()
                .names("id", "fecha", "monto", "tipo")
                .fieldSetMapper(fields -> new TransaccionCsv(
                        fields.readLong("id"), fields.readString("fecha"),
                        fields.readString("monto"), fields.readString("tipo")))
                .build();
        reader.setCurrentItemCount(start);
        reader.setMaxItemCount(end);
        return reader;
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.ItemProcessor<TransaccionCsv,Transaccion> transaccionesProcessor(
            @Value("#{jobParameters['periodo']}") String periodo) {
        return item -> {
            var resultado=new TransaccionProcessor().process(item);
            return periodo==null || resultado.fecha().equals(java.time.LocalDate.parse(periodo))?resultado:null;
        };
    }

    @Bean
    // Persistencia de transacciones válidas.
    @StepScope
    public JdbcBatchItemWriter<Transaccion> transaccionesWriter(javax.sql.DataSource dataSource,
            @Value("#{stepExecution.jobExecution.jobInstance.instanceId}") Long ejecucion) {
        return new JdbcBatchItemWriterBuilder<Transaccion>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO transacciones_procesadas(ejecucion_id, id, fecha, monto, tipo, anomalia)
                        VALUES (:ejecucionId, :id, :fecha, :monto, :tipo, :anomalia)
                        """)
                .itemSqlParameterSourceProvider(item -> new cl.duoc.bancoxyz.batch.support.EjecucionSqlParameterSource(item,ejecucion))
                .build();
    }
}
