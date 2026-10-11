package cl.duoc.bancoxyz.batch.config;

import cl.duoc.bancoxyz.batch.domain.MovimientoAnual;
import cl.duoc.bancoxyz.batch.domain.MovimientoAnualCsv;
import cl.duoc.bancoxyz.batch.processor.MovimientoAnualProcessor;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import cl.duoc.bancoxyz.batch.support.RegistroOmitidoListener;
import cl.duoc.bancoxyz.batch.support.CsvPartitioner;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
// Movimientos y estados anuales.
public class EstadosAnualesJobConfig {

    @Bean
    // Etapas del informe anual secuenciales.
    public Job estadosAnualesJob(JobRepository repository, Step limpiarEstadosAnualesStep,
                                 Step procesarEstadosAnualesPartitionedStep, Step generarEstadosAnualesStep,
                                 org.springframework.batch.core.JobExecutionListener batchJobLogListener) {
        return new JobBuilder("estadosAnualesJob", repository)
                .incrementer(new org.springframework.batch.core.launch.support.RunIdIncrementer())
                .start(limpiarEstadosAnualesStep)
                .next(procesarEstadosAnualesPartitionedStep)
                .next(generarEstadosAnualesStep)
                .listener(batchJobLogListener)
                .build();
    }

    @Bean
    // Resultados anuales independientes por ejecución.
    public Step limpiarEstadosAnualesStep(JobRepository repository,
                                          PlatformTransactionManager transactionManager,
                                          JdbcTemplate jdbcTemplate) {
        return new StepBuilder("limpiarEstadosAnualesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("DELETE FROM movimientos_anuales WHERE ejecucion_id=?", context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    jdbcTemplate.update("DELETE FROM estados_cuenta_anuales WHERE ejecucion_id=?", context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    jdbcTemplate.update("DELETE FROM registros_rechazados WHERE job = \'estadosAnualesJob\' AND ejecucion_id=?", context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Movimientos válidos en bloques de cinco registros.
    public Step procesarEstadosAnualesStep(JobRepository repository,
                                            PlatformTransactionManager transactionManager,
                                            @Value("${app.batch.chunk-size:100}") int chunkSize,
                                            FlatFileItemReader<MovimientoAnualCsv> movimientosAnualesReader,
                                            JdbcBatchItemWriter<MovimientoAnual> movimientosAnualesWriter,
                                            org.springframework.batch.item.ItemProcessor<MovimientoAnualCsv,MovimientoAnual> movimientosAnualesProcessor,
                                            JdbcTemplate jdbcTemplate) {
        return new StepBuilder("procesarEstadosAnualesStep", repository)
                .<MovimientoAnualCsv, MovimientoAnual>chunk(chunkSize, transactionManager)
                .reader(movimientosAnualesReader)
                .processor(movimientosAnualesProcessor)
                .writer(movimientosAnualesWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skip(FlatFileParseException.class)
                .skip(DataIntegrityViolationException.class)
                .skipLimit(100)
                .retry(CannotAcquireLockException.class)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .retry(org.springframework.jdbc.CannotGetJdbcConnectionException.class)
                .retryLimit(3)
                .listener(new RegistroOmitidoListener<MovimientoAnualCsv, MovimientoAnual>(jdbcTemplate,
                        "estadosAnualesJob"))
                .build();
    }

    @Bean
    public Partitioner estadosAnualesPartitioner(
            @Value("${app.archivos.cuentas-anuales}") Resource resource,
            @Value("${app.batch.grid-size:3}") int gridSize) {
        return new CsvPartitioner(resource, gridSize);
    }

    @Bean
    public Step procesarEstadosAnualesPartitionedStep(JobRepository repository,
                                                      Step procesarEstadosAnualesStep,
                                                      Partitioner estadosAnualesPartitioner,
                                                      TaskExecutor batchTaskExecutor,
                                                      @Value("${app.batch.grid-size:3}") int gridSize) {
        var handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(batchTaskExecutor);
        handler.setStep(procesarEstadosAnualesStep);
        handler.setGridSize(gridSize);
        return new StepBuilder("procesarEstadosAnualesPartitionedStep", repository)
                .partitioner("procesarEstadosAnualesStep", estadosAnualesPartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    // Movimientos consolidados por cuenta y año.
    public Step generarEstadosAnualesStep(JobRepository repository,
                                           PlatformTransactionManager transactionManager,
                                           JdbcTemplate jdbcTemplate,
                                           @Value("${app.reportes.directorio}") String directorio) {
        return new StepBuilder("generarEstadosAnualesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("""
                            INSERT INTO estados_cuenta_anuales(
                                ejecucion_id, cuenta_id, anio, cantidad_movimientos, total_depositos, total_cargos, saldo_anual)
                            SELECT ejecucion_id, cuenta_id, EXTRACT(YEAR FROM fecha), COUNT(*),
                                   SUM(CASE WHEN monto > 0 THEN monto ELSE 0 END),
                                   SUM(CASE WHEN monto < 0 THEN ABS(monto) ELSE 0 END), SUM(monto)
                            FROM movimientos_anuales WHERE ejecucion_id=?
                            GROUP BY ejecucion_id, cuenta_id, EXTRACT(YEAR FROM fecha)
                            """, context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    escribirInforme(jdbcTemplate, Path.of(directorio, "estados_cuenta_anuales.csv"), context.getStepContext().getStepExecution().getJobExecution().getJobInstance().getInstanceId());
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Entrada de movimientos delimitada.
    @StepScope
    public FlatFileItemReader<MovimientoAnualCsv> movimientosAnualesReader(
            @Value("${app.archivos.cuentas-anuales}") Resource resource,
            @Value("#{stepExecutionContext['start']}") Integer start,
            @Value("#{stepExecutionContext['end']}") Integer end) {
        var reader = new FlatFileItemReaderBuilder<MovimientoAnualCsv>()
                .name("movimientosAnualesReader")
                .resource(resource)
                .linesToSkip(1)
                .delimited()
                .names("cuentaId", "fecha", "transaccion", "monto", "descripcion")
                .fieldSetMapper(fields -> new MovimientoAnualCsv(
                        fields.readLong("cuentaId"), fields.readString("fecha"),
                        fields.readString("transaccion"), fields.readString("monto"),
                        fields.readString("descripcion")))
                .build();
        reader.setCurrentItemCount(start);
        reader.setMaxItemCount(end);
        return reader;
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.ItemProcessor<MovimientoAnualCsv,MovimientoAnual> movimientosAnualesProcessor(
            @Value("#{jobParameters['periodo']}") String periodo) {
        return item -> {
            var resultado=new MovimientoAnualProcessor().process(item);
            return periodo==null || resultado.fecha().getYear()==Integer.parseInt(periodo)?resultado:null;
        };
    }

    @Bean
    // Persistencia de movimientos válidos.
    @StepScope
    public JdbcBatchItemWriter<MovimientoAnual> movimientosAnualesWriter(javax.sql.DataSource dataSource,
            @Value("#{stepExecution.jobExecution.jobInstance.instanceId}") Long ejecucion) {
        return new JdbcBatchItemWriterBuilder<MovimientoAnual>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO movimientos_anuales(ejecucion_id, cuenta_id, fecha, transaccion, monto, descripcion)
                        VALUES (:ejecucionId, :cuentaId, :fecha, :transaccion, :monto, :descripcion)
                        """)
                .itemSqlParameterSourceProvider(item -> new cl.duoc.bancoxyz.batch.support.EjecucionSqlParameterSource(item,ejecucion))
                .build();
    }

    private void escribirInforme(JdbcTemplate jdbcTemplate, Path ruta, long ejecucion) throws Exception {
        // Resumen consolidado en formato CSV.
        Files.createDirectories(ruta.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(ruta.resolveSibling("estados_cuenta_anuales-"+ejecucion+".csv"), StandardCharsets.UTF_8)) {
            writer.write("cuenta_id,anio,cantidad_movimientos,total_depositos,total_cargos,saldo_anual");
            writer.newLine();
            var filas = jdbcTemplate.queryForList("""
                    SELECT cuenta_id, anio, cantidad_movimientos, total_depositos, total_cargos, saldo_anual
                    FROM estados_cuenta_anuales WHERE ejecucion_id=? ORDER BY cuenta_id, anio
                    """, ejecucion);
            for (var fila : filas) {
                writer.write("%s,%s,%s,%s,%s,%s".formatted(
                        fila.get("cuenta_id"), fila.get("anio"), fila.get("cantidad_movimientos"),
                        fila.get("total_depositos"), fila.get("total_cargos"), fila.get("saldo_anual")));
                writer.newLine();
            }
        }
        Files.copy(ruta.resolveSibling("estados_cuenta_anuales-"+ejecucion+".csv"), ruta,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
