package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
@ActiveProfiles("test")
class JobsIntegrationTest {

    private final JobLauncher jobLauncher;
    private final JdbcTemplate jdbcTemplate;
    private final Job transaccionesJob;
    private final Job interesesJob;
    private final Job estadosAnualesJob;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void perfilDefaultMantieneBatchSinWebNiWorker() {
        assertThat(applicationContext).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
        assertThat(applicationContext.getBeansOfType(cl.duoc.bancoxyz.batch.worker.ProcesosWorker.class)).isEmpty();
    }

    @Autowired
    JobsIntegrationTest(JobLauncher jobLauncher, JdbcTemplate jdbcTemplate,
                        @Qualifier("transaccionesJob") Job transaccionesJob,
                        @Qualifier("interesesJob") Job interesesJob,
                        @Qualifier("estadosAnualesJob") Job estadosAnualesJob) {
        this.jobLauncher = jobLauncher;
        this.jdbcTemplate = jdbcTemplate;
        this.transaccionesJob = transaccionesJob;
        this.interesesJob = interesesJob;
        this.estadosAnualesJob = estadosAnualesJob;
    }

    @Test
    void ejecutaLosTresProcesosConTrazabilidad() throws Exception {
        assertThat(ejecutar(transaccionesJob, 1L)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(valor("SELECT COUNT(*) FROM transacciones_procesadas")).isEqualTo(10);
        assertThat(valor("SELECT SUM(anomalias) FROM resumen_transacciones")).isEqualTo(2);
        assertThat(rechazados("transaccionesJob")).isZero();
        System.out.println("EVIDENCIA transaccionesJob: COMPLETED | persistidos=10 | anomalias=2 | rechazados=0");

        assertThat(ejecutar(interesesJob, 2L)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(valor("SELECT COUNT(*) FROM intereses_calculados")).isEqualTo(5);
        assertThat(rechazados("interesesJob")).isEqualTo(3);
        System.out.println("EVIDENCIA interesesJob: COMPLETED | persistidos=5 | rechazados=3");

        assertThat(ejecutar(estadosAnualesJob, 3L)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(valor("SELECT COUNT(*) FROM movimientos_anuales")).isEqualTo(8);
        assertThat(valor("SELECT COUNT(*) FROM estados_cuenta_anuales")).isEqualTo(7);
        assertThat(rechazados("estadosAnualesJob")).isEqualTo(1);
        assertThat(Files.exists(Path.of("build/reportes/estados_cuenta_anuales.csv"))).isTrue();
        System.out.println("EVIDENCIA estadosAnualesJob: COMPLETED | movimientos=8 | estados=7 | rechazados=1 | informe=OK");
        var evidencia=java.util.Map.of(
                "transacciones",jdbcTemplate.queryForList("SELECT id,fecha,monto,tipo,anomalia FROM transacciones_procesadas ORDER BY id"),
                "intereses",jdbcTemplate.queryForList("SELECT cuenta_id,saldo_inicial,tasa,interes,saldo_final FROM intereses_calculados ORDER BY cuenta_id"),
                "estadosAnuales",jdbcTemplate.queryForList("SELECT cuenta_id,anio,cantidad_movimientos,total_depositos,total_cargos,saldo_anual FROM estados_cuenta_anuales ORDER BY cuenta_id,anio"));
        var ruta=Path.of("docs/evidencia/resultados-batch.json");Files.createDirectories(ruta.getParent());
        Files.writeString(ruta,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writerWithDefaultPrettyPrinter().writeValueAsString(evidencia));
        assertThat(ejecutar(transaccionesJob,4L)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(valor("SELECT COUNT(*) FROM transacciones_procesadas")).isEqualTo(20);
        assertThat(valor("SELECT COUNT(*) FROM transacciones_procesadas WHERE ejecucion_id=(SELECT ejecucion_id FROM publicaciones_batch WHERE job='transaccionesJob')")).isEqualTo(10);
    }

    private BatchStatus ejecutar(Job job, long runId) throws Exception {
        var parameters = new JobParametersBuilder().addLong("run.id", runId).toJobParameters();
        return jobLauncher.run(job, parameters).getStatus();
    }

    private int valor(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private int rechazados(String job) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registros_rechazados WHERE job = ?", Integer.class, job);
    }
}
