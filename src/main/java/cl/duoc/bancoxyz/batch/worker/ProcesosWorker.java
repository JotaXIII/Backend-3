package cl.duoc.bancoxyz.batch.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("cloud")
public class ProcesosWorker {
    private final JobLauncher launcher;
    private final JobRepository repository;
    private final Map<String, Job> jobs;
    private final JmsTemplate jms;
    private final ObjectMapper mapper;

    public ProcesosWorker(JobLauncher launcher, JobRepository repository, Map<String, Job> jobs,
                          JmsTemplate jms, ObjectMapper mapper) {
        this.launcher = launcher;
        this.repository = repository;
        this.jobs = jobs;
        this.jms = jms;
        this.mapper = mapper;
    }

    // Ejecuta una solicitud y publica su estado terminal.
    @JmsListener(destination = "procesos.solicitudes", containerFactory = "cloudJmsListenerContainerFactory")
    public void recibir(String mensaje) throws Exception {
        var solicitud = mapper.readTree(mensaje);
        var id = solicitud.path("id");
        if (!id.isValueNode() || id.isNull() || id.asText().isBlank()) {
            throw new IllegalArgumentException("Solicitud sin id valido");
        }
        String nombre = switch (solicitud.path("proceso").asText()) {
            case "transacciones" -> "transaccionesJob";
            case "intereses" -> "interesesJob";
            case "estados-anuales" -> "estadosAnualesJob";
            default -> null;
        };
        String estado = "FAILED";
        if (nombre != null) {
            var job = jobs.get(nombre);
            var parametros = new JobParametersBuilder()
                    .addString("solicitud.id", id.asText()).toJobParameters();
            var anterior = repository.getLastJobExecution(job.getName(), parametros);
            if (anterior != null && anterior.getStatus() == BatchStatus.FAILED) {
                estado = "FAILED";
            } else {
                try {
                    estado = launcher.run(job, parametros).getStatus() == BatchStatus.COMPLETED
                            ? "COMPLETED" : "FAILED";
                } catch (JobInstanceAlreadyCompleteException exception) {
                    estado = "COMPLETED";
                }
            }
        }
        var resultado = mapper.createObjectNode();
        resultado.set("id", id);
        resultado.put("estado", estado);
        jms.convertAndSend("procesos.resultados", mapper.writeValueAsString(resultado));
    }
}
