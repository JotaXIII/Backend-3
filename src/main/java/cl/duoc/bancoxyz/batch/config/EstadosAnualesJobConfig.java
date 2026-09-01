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
// Configura la carga de movimientos y la generación de estados anuales.
public class EstadosAnualesJobConfig {

    @Bean
    // Ejecuta la limpieza, carga y generación del informe.
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
    // Elimina los resultados anteriores del proceso anual.
    public Step limpiarEstadosAnualesStep(JobRepository repository,
                                          PlatformTransactionManager transactionManager,
                                          JdbcTemplate jdbcTemplate) {
        return new StepBuilder("limpiarEstadosAnualesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("DELETE FROM movimientos_anuales");
                    jdbcTemplate.update("DELETE FROM estados_cuenta_anuales");
                    jdbcTemplate.update("DELETE FROM registros_rechazados WHERE job = 'estadosAnualesJob'");
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Valida y almacena los movimientos en chunks de cinco registros.
    public Step procesarEstadosAnualesStep(JobRepository repository,
                                            PlatformTransactionManager transactionManager,
                                            FlatFileItemReader<MovimientoAnualCsv> movimientosAnualesReader,
                                            JdbcBatchItemWriter<MovimientoAnual> movimientosAnualesWriter,
                                            JdbcTemplate jdbcTemplate) {
        return new StepBuilder("procesarEstadosAnualesStep", repository)
                .<MovimientoAnualCsv, MovimientoAnual>chunk(5, transactionManager)
                .reader(movimientosAnualesReader)
                .processor(new MovimientoAnualProcessor())
                .writer(movimientosAnualesWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skip(FlatFileParseException.class)
                .skip(DataIntegrityViolationException.class)
                .skipLimit(100)
                .retry(CannotAcquireLockException.class)
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
    // Consolida los movimientos por cuenta y año.
    public Step generarEstadosAnualesStep(JobRepository repository,
                                           PlatformTransactionManager transactionManager,
                                           JdbcTemplate jdbcTemplate,
                                           @Value("${app.reportes.directorio}") String directorio) {
        return new StepBuilder("generarEstadosAnualesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("""
                            INSERT INTO estados_cuenta_anuales(
                                cuenta_id, anio, cantidad_movimientos, total_depositos, total_cargos, saldo_anual)
                            SELECT cuenta_id, EXTRACT(YEAR FROM fecha), COUNT(*),
                                   SUM(CASE WHEN monto > 0 THEN monto ELSE 0 END),
                                   SUM(CASE WHEN monto < 0 THEN ABS(monto) ELSE 0 END), SUM(monto)
                            FROM movimientos_anuales
                            GROUP BY cuenta_id, EXTRACT(YEAR FROM fecha)
                            """);
                    escribirInforme(jdbcTemplate, Path.of(directorio, "estados_cuenta_anuales.csv"));
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Lee los movimientos desde un archivo delimitado.
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
    // Inserta los movimientos válidos en la base de datos.
    public JdbcBatchItemWriter<MovimientoAnual> movimientosAnualesWriter(javax.sql.DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MovimientoAnual>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO movimientos_anuales(cuenta_id, fecha, transaccion, monto, descripcion)
                        VALUES (:cuentaId, :fecha, :transaccion, :monto, :descripcion)
                        """)
                .beanMapped()
                .build();
    }

    private void escribirInforme(JdbcTemplate jdbcTemplate, Path ruta) throws Exception {
        // Escribe el resumen consolidado en formato CSV.
        Files.createDirectories(ruta.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(ruta, StandardCharsets.UTF_8)) {
            writer.write("cuenta_id,anio,cantidad_movimientos,total_depositos,total_cargos,saldo_anual");
            writer.newLine();
            var filas = jdbcTemplate.queryForList("""
                    SELECT cuenta_id, anio, cantidad_movimientos, total_depositos, total_cargos, saldo_anual
                    FROM estados_cuenta_anuales ORDER BY cuenta_id, anio
                    """);
            for (var fila : filas) {
                writer.write("%s,%s,%s,%s,%s,%s".formatted(
                        fila.get("cuenta_id"), fila.get("anio"), fila.get("cantidad_movimientos"),
                        fila.get("total_depositos"), fila.get("total_cargos"), fila.get("saldo_anual")));
                writer.newLine();
            }
        }
    }
}
