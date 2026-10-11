package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.batch.job.enabled=false","spring.datasource.url=jdbc:h2:mem:rendimiento;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named="batch.performance",matches="true")
class RendimientoBatchTest {
    @DynamicPropertySource static void fuente(DynamicPropertyRegistry properties) throws Exception {
        Path file=Files.createTempFile("transacciones-",".csv");
        int registros=Integer.getInteger("batch.records",50000);
        try(var writer=Files.newBufferedWriter(file)) {
            writer.write("id,fecha,monto,tipo\n");
            for(int i=1;i<=registros;i++) writer.write(i+",2024-01-01,100,credito\n");
        }
        properties.add("app.archivos.transacciones",() -> file.toUri().toString());
        file.toFile().deleteOnExit();
    }
    @Autowired JobLauncher launcher;
    @Autowired @Qualifier("transaccionesJob") Job job;
    @Autowired JdbcTemplate db;
    @Test void volumenConservaCantidadYTotal() throws Exception {
        int registros=Integer.getInteger("batch.records",50000);
        long inicio=System.nanoTime();
        var ejecucion=launcher.run(job,new JobParametersBuilder().addLong("run.id",System.nanoTime()).toJobParameters());
        double segundos=(System.nanoTime()-inicio)/1_000_000_000d;
        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transacciones_procesadas",Integer.class)).isEqualTo(registros);
        assertThat(db.queryForObject("SELECT SUM(monto) FROM transacciones_procesadas",java.math.BigDecimal.class))
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(registros*100L));
        int particiones=Integer.getInteger("app.batch.grid-size",3);
        var ruta=Path.of("docs/evidencia/rendimiento-"+particiones+".json");Files.createDirectories(ruta.getParent());
        Files.writeString(ruta,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("registros",registros,"particiones",particiones,"segundos",segundos,"registrosPorSegundo",registros/segundos,
                        "baseDatos","H2 en memoria","estado",ejecucion.getStatus().name(),"fecha",java.time.Instant.now().toString())));
    }
}
