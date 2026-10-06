package cl.duoc.bancoxyz.solicitudes;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
// Limita esperas y ofrece una respuesta temporal ante fallos remotos.
public class ConsultaService {

    private final RestClient restClient;
    private final DiscoveryClient discoveryClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String url;

    public ConsultaService(DiscoveryClient discoveryClient, CircuitBreakerRegistry breakers,
                           RetryRegistry retries, @Value("${app.consultas.url:}") String url) {
        this.discoveryClient = discoveryClient;
        this.circuitBreaker = breakers.circuitBreaker("consultas");
        this.retry = retries.retry("consultas");
        this.url = url;
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> consultar(String recurso, String token, int limite, int pagina) {
        if (!Set.of("transacciones", "intereses", "estados-anuales", "rechazados").contains(recurso)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Recurso no disponible");
        }
        Supplier<Object> llamada = () -> restClient.get()
                .uri(destino() + "/api/" + recurso + "?limite=" + limite + "&pagina=" + pagina)
                .header("Authorization", token).retrieve().body(Object.class);
        try {
            var datos = circuitBreaker.executeSupplier(Retry.decorateSupplier(retry, llamada));
            return Map.of("disponible", true, "datos", datos == null ? java.util.List.of() : datos);
        } catch (ResourceAccessException | HttpServerErrorException | CallNotPermittedException exception) {
            return Map.of("disponible", false, "datos", java.util.List.of(),
                    "mensaje", "Consulta temporalmente no disponible");
        }
    }

    private String destino() {
        if (!url.isBlank()) {
            return url;
        }
        var instancias = discoveryClient.getInstances("consultas");
        if (instancias.isEmpty()) {
            throw new ResourceAccessException("No hay instancias disponibles");
        }
        return instancias.getFirst().getUri().toString();
    }

    public String estado() {
        return circuitBreaker.getState().name();
    }
}
