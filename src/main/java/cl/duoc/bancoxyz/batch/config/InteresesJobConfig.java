package cl.duoc.bancoxyz.batch.config;

import cl.duoc.bancoxyz.batch.domain.InteresCalculado;
import cl.duoc.bancoxyz.batch.domain.InteresCsv;
import cl.duoc.bancoxyz.batch.processor.InteresProcessor;
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

@Configuration
// Cálculo y persistencia de intereses.
public class InteresesJobConfig {

    @Bean
    // Etapas de intereses secuenciales.
    public Job interesesJob(JobRepository repository, Step limpiarInteresesStep,
                            Step procesarInteresesPartitionedStep,
                            org.springframework.batch.core.JobExecutionListener batchJobLogListener) {
        return new JobBuilder("interesesJob", repository)
                .incrementer(new org.springframework.batch.core.launch.support.RunIdIncrementer())
                .start(limpiarInteresesStep)
                .next(procesarInteresesPartitionedStep)
                .listener(batchJobLogListener)
                .build();
    }

    @Bean
    // Cálculos independientes por ejecución.
    public Step limpiarInteresesStep(JobRepository repository,
                                     PlatformTransactionManager transactionManager,
                                     JdbcTemplate jdbcTemplate) {
        return new StepBuilder("limpiarInteresesStep", repository)
                .tasklet((contribution, context) -> {
                    jdbcTemplate.update("DELETE FROM intereses_calculados");
                    jdbcTemplate.update("DELETE FROM registros_rechazados WHERE job = 'interesesJob'");
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    // Intereses en bloques de cinco registros.
    public Step procesarInteresesStep(JobRepository repository,
                                      PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<InteresCsv> interesesReader,
                                      JdbcBatchItemWriter<InteresCalculado> interesesWriter,
                                      JdbcTemplate jdbcTemplate) {
        return new StepBuilder("procesarInteresesStep", repository)
                .<InteresCsv, InteresCalculado>chunk(5, transactionManager)
                .reader(interesesReader)
                .processor(new InteresProcessor())
                .writer(interesesWriter)
                .faultTolerant()
                .skip(RegistroInvalidoException.class)
                .skip(FlatFileParseException.class)
                .skip(DataIntegrityViolationException.class)
                .skipLimit(100)
                .retry(CannotAcquireLockException.class)
                .retryLimit(3)
                .listener(new RegistroOmitidoListener<InteresCsv, InteresCalculado>(jdbcTemplate,
                        "interesesJob"))
                .build();
    }

    @Bean
    public Partitioner interesesPartitioner(
            @Value("${app.archivos.intereses}") Resource resource,
            @Value("${app.batch.grid-size:3}") int gridSize) {
        return new CsvPartitioner(resource, gridSize);
    }

    @Bean
    public Step procesarInteresesPartitionedStep(JobRepository repository,
                                                 Step procesarInteresesStep,
                                                 Partitioner interesesPartitioner,
                                                 TaskExecutor batchTaskExecutor,
                                                 @Value("${app.batch.grid-size:3}") int gridSize) {
        var handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(batchTaskExecutor);
        handler.setStep(procesarInteresesStep);
        handler.setGridSize(gridSize);
        return new StepBuilder("procesarInteresesPartitionedStep", repository)
                .partitioner("procesarInteresesStep", interesesPartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    // Entrada de cuentas delimitada.
    @StepScope
    public FlatFileItemReader<InteresCsv> interesesReader(
            @Value("${app.archivos.intereses}") Resource resource,
            @Value("#{stepExecutionContext['start']}") Integer start,
            @Value("#{stepExecutionContext['end']}") Integer end) {
        var reader = new FlatFileItemReaderBuilder<InteresCsv>()
                .name("interesesReader")
                .resource(resource)
                .linesToSkip(1)
                .delimited()
                .names("cuentaId", "nombre", "saldo", "edad", "tipo")
                .fieldSetMapper(fields -> new InteresCsv(
                        fields.readLong("cuentaId"), fields.readString("nombre"),
                        fields.readString("saldo"), fields.readString("edad"),
                        fields.readString("tipo")))
                .build();
        reader.setCurrentItemCount(start);
        reader.setMaxItemCount(end);
        return reader;
    }

    @Bean
    // Persistencia de intereses calculados.
    public JdbcBatchItemWriter<InteresCalculado> interesesWriter(javax.sql.DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<InteresCalculado>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO intereses_calculados(
                            cuenta_id, nombre, saldo_inicial, edad, tipo, tasa, interes, saldo_final)
                        VALUES (:cuentaId, :nombre, :saldoInicial, :edad, :tipo, :tasa, :interes, :saldoFinal)
                        """)
                .beanMapped()
                .build();
    }
}
