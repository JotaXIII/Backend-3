package cl.duoc.bancoxyz.solicitudes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
// Conserva las solicitudes pendientes antes de publicar mensajes.
public class SolicitudService {

    private static final Set<String> PROCESOS = Set.of("transacciones", "intereses", "estados-anuales");
    private final JdbcTemplate jdbcTemplate;
    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    public SolicitudService(JdbcTemplate jdbcTemplate, JmsTemplate jmsTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> solicitar(String proceso, String propietario) {
        if (!PROCESOS.contains(proceso)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proceso no disponible");
        }
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO solicitudes(id, proceso, propietario, estado) VALUES (?, ?, ?, 'PENDIENTE')",
                id, proceso, propietario);
        return consultar(id, propietario);
    }

    public Map<String, Object> consultar(String id, String propietario) {
        var resultados = jdbcTemplate.queryForList(
                "SELECT id, proceso, estado, creada FROM solicitudes WHERE id = ? AND propietario = ?",
                id, propietario);
        if (resultados.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no disponible");
        }
        return resultados.getFirst();
    }

    @Scheduled(fixedDelayString = "${app.publicacion.intervalo:1000}")
    // Reintenta la publicación sin perder solicitudes pendientes.
    public void publicarPendientes() throws Exception {
        for (var solicitud : jdbcTemplate.queryForList(
                "SELECT id, proceso FROM solicitudes WHERE estado = 'PENDIENTE' ORDER BY creada LIMIT 100")) {
            String id = solicitud.get("id").toString();
            jmsTemplate.convertAndSend("procesos.solicitudes", objectMapper.writeValueAsString(solicitud));
            jdbcTemplate.update("UPDATE solicitudes SET estado = 'ENVIADA' WHERE id = ? AND estado = 'PENDIENTE'", id);
        }
    }

    @JmsListener(destination = "procesos.resultados")
    // Actualiza el resultado sin alterar la identidad de la solicitud.
    public void recibirResultado(String contenido) throws Exception {
        var resultado = objectMapper.readTree(contenido);
        String estado = resultado.path("estado").asText();
        if (!Set.of("COMPLETED", "FAILED").contains(estado)) {
            throw new IllegalArgumentException("Estado no permitido");
        }
        jdbcTemplate.update("UPDATE solicitudes SET estado = ? WHERE id = ?",
                estado, resultado.path("id").asText());
    }
}
