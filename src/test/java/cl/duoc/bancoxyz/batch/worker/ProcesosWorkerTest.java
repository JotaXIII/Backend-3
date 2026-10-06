package cl.duoc.bancoxyz.batch.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jms.core.JmsTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcesosWorkerTest {
    private final JobLauncher launcher = mock(JobLauncher.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final JmsTemplate jms = mock(JmsTemplate.class);
    private final Job job = mock(Job.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ProcesosWorker worker;

    @BeforeEach
    void preparar() {
        worker = new ProcesosWorker(launcher, repository,
                Map.of("transaccionesJob", job, "interesesJob", job, "estadosAnualesJob", job), jms, mapper);
    }

    @ParameterizedTest
    @CsvSource({"transacciones,transaccionesJob", "intereses,interesesJob", "estados-anuales,estadosAnualesJob"})
    void seleccionaJobConParametroIdentificador(String proceso, String nombre) throws Exception {
        when(job.getName()).thenReturn(nombre);
        var execution = new JobExecution(1L);
        execution.setStatus(BatchStatus.COMPLETED);
        when(launcher.run(eq(job), any())).thenReturn(execution);
        worker.recibir("{\"id\":\"abc\",\"proceso\":\"" + proceso + "\"}");
        verify(launcher).run(eq(job), argThat(parameters -> {
            assertThat(parameters.getParameters()).hasSize(1);
            assertThat(parameters.getString("solicitud.id")).isEqualTo("abc");
            assertThat(parameters.getParameters().get("solicitud.id").isIdentifying()).isTrue();
            return true;
        }));
        verificarResultado("COMPLETED");
    }

    @Test
    void duplicadoCompletoDevuelveCompleted() throws Exception {
        when(job.getName()).thenReturn("transaccionesJob");
        when(launcher.run(eq(job), any())).thenThrow(new JobInstanceAlreadyCompleteException("Completo"));
        worker.recibir("{\"id\":\"abc\",\"proceso\":\"transacciones\"}");
        verificarResultado("COMPLETED");
    }

    @Test
    void falloSePublicaSinLanzarExcepcion() throws Exception {
        when(job.getName()).thenReturn("transaccionesJob");
        var execution = new JobExecution(1L);
        execution.setStatus(BatchStatus.FAILED);
        when(launcher.run(eq(job), any())).thenReturn(execution);
        worker.recibir("{\"id\":\"abc\",\"proceso\":\"transacciones\"}");
        verificarResultado("FAILED");
    }

    @Test
    void falloPrevioNoSeReejecuta() throws Exception {
        when(job.getName()).thenReturn("transaccionesJob");
        var execution = new JobExecution(1L);
        execution.setStatus(BatchStatus.FAILED);
        when(repository.getLastJobExecution(eq("transaccionesJob"), any(JobParameters.class)))
                .thenReturn(execution);
        worker.recibir("{\"id\":\"abc\",\"proceso\":\"transacciones\"}");
        verifyNoInteractions(launcher);
        verificarResultado("FAILED");
    }

    @Test
    void procesoInvalidoPublicaFailed() throws Exception {
        worker.recibir("{\"id\":\"abc\",\"proceso\":\"otro\"}");
        verifyNoInteractions(launcher, repository);
        verificarResultado("FAILED");
    }

    @Test
    void rechazaIdAusenteYJsonInvalido() {
        assertThatThrownBy(() -> worker.recibir("{\"proceso\":\"transacciones\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker.recibir("invalido")).isInstanceOf(Exception.class);
        verifyNoInteractions(launcher, repository, jms);
    }

    @Test
    void errorAlPublicarPropagaParaRollback() throws Exception {
        doThrow(new IllegalStateException("Broker no disponible")).when(jms).convertAndSend(anyString(), anyString());
        assertThatThrownBy(() -> worker.recibir("{\"id\":\"abc\",\"proceso\":\"otro\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    private void verificarResultado(String estado) throws Exception {
        verify(jms).convertAndSend(eq("procesos.resultados"), argThat((String json) -> {
            try {
                var resultado = mapper.readTree(json);
                assertThat(resultado.size()).isEqualTo(2);
                assertThat(resultado.path("id").asText()).isEqualTo("abc");
                assertThat(resultado.path("estado").asText()).isEqualTo(estado);
                return true;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }));
    }
}
