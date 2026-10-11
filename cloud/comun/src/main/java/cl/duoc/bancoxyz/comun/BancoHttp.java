package cl.duoc.bancoxyz.comun;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.server.ResponseStatusException;
import java.net.http.HttpClient;
import java.time.Duration;

public class BancoHttp {
    private final LoadBalancerClient balanceador;
    private final CircuitBreakerRegistry circuitos;
    private final RestClient http;

    public BancoHttp(LoadBalancerClient balanceador, CircuitBreakerRegistry circuitos) {
        this.balanceador = balanceador;
        this.circuitos = circuitos;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public <T> T get(String servicio, String ruta, String token, Class<T> tipo) {
        return ejecutar(servicio, () -> http.get().uri(destino(servicio) + ruta)
                .header("Authorization", token).retrieve().body(tipo));
    }

    public <T> T post(String servicio, String ruta, String token, Object body, Class<T> tipo) {
        return ejecutar(servicio, () -> http.post().uri(destino(servicio) + ruta)
                .header("Authorization", token).body(body).retrieve().body(tipo));
    }
    public <T> T put(String servicio,String ruta,String token,Object body,Class<T> tipo) {
        return ejecutar(servicio,() -> http.put().uri(destino(servicio)+ruta)
                .header("Authorization",token).body(body).retrieve().body(tipo));
    }

    private String destino(String servicio) {
        var instancia = balanceador.choose(servicio);
        if (instancia == null) throw new ResourceAccessException("Servicio no disponible");
        return instancia.getUri().toString();
    }

    private <T> T ejecutar(String servicio, java.util.function.Supplier<T> llamada) {
        try {
            return circuitos.circuitBreaker(servicio).executeSupplier(llamada);
        } catch (ResourceAccessException | HttpServerErrorException
                 | io.github.resilience4j.circuitbreaker.CallNotPermittedException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Servicio temporalmente no disponible");
        }
    }
}
