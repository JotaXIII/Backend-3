package cl.duoc.bancoxyz.solicitudes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConsultaServiceTest {
    HttpServer server;
    DiscoveryClient discovery;
    CircuitBreaker breaker;
    CircuitBreakerRegistry breakers;
    RetryRegistry retries;
    ConsultaService service;
    AtomicInteger llamadas = new AtomicInteger();
    AtomicInteger codigo = new AtomicInteger(200);
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> destino = new AtomicReference<>();

    @BeforeEach
    void preparar() throws Exception {
        discovery = mock(DiscoveryClient.class);
        // Espera mínima entre reintentos.
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpClientErrorException.class).build());
        retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(1))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class).build());
        breaker = breakers.circuitBreaker("consultas");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            llamadas.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            destino.set(exchange.getRequestURI().toString());
            byte[] body = "[{\"id\":7}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(codigo.get(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        });
        server.start();
        service = new ConsultaService(discovery, breakers, retries, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void cerrar() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exitoPropagaTokenPaginacionYDatos() {
        var resultado = service.consultar("transacciones", "Bearer prueba", 5, 2);
        assertThat(resultado).containsEntry("disponible", true);
        assertThat(resultado.get("datos")).isEqualTo(List.of(Map.of("id", 7)));
        assertThat(authorization.get()).isEqualTo("Bearer prueba");
        assertThat(destino.get()).isEqualTo("/api/transacciones?limite=5&pagina=2");
        assertThat(llamadas.get()).isEqualTo(1);
        verifyNoInteractions(discovery);
    }

    @Test
    void fallosReintentanAbrenCircuitoYRecuperanHalfOpenClosed() {
        codigo.set(503);
        for (int i = 0; i < 4; i++) {
            assertThat(service.consultar("intereses", "Bearer prueba", 100, 0))
                    .containsEntry("disponible", false).containsEntry("datos", List.of())
                    .containsEntry("mensaje", "Consulta temporalmente no disponible");
        }
        assertThat(llamadas.get()).isEqualTo(8);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        assertThat(service.estado()).isEqualTo("OPEN");
        assertThat(service.consultar("intereses", "Bearer prueba", 100, 0)).containsEntry("disponible", false);
        assertThat(llamadas.get()).isEqualTo(8);
        codigo.set(200);
        // Recuperación sin espera temporal.
        breaker.transitionToHalfOpenState();
        assertThat(service.estado()).isEqualTo("HALF_OPEN");
        assertThat(service.consultar("intereses", "Bearer prueba", 100, 0)).containsEntry("disponible", true);
        assertThat(service.estado()).isEqualTo("HALF_OPEN");
        assertThat(service.consultar("intereses", "Bearer prueba", 100, 0)).containsEntry("disponible", true);
        assertThat(service.estado()).isEqualTo("CLOSED");
        assertThat(llamadas.get()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void errores4xxSePropaganSinRetryFallbackNiAbrirCircuito(int status) {
        codigo.set(status);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.consultar("rechazados", "Bearer prueba", 100, 0))
                    .isInstanceOfSatisfying(HttpClientErrorException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
        }
        assertThat(llamadas.get()).isEqualTo(5);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(service.estado()).isEqualTo("CLOSED");
    }

    @Test
    void discoveryVacioReintentaYEntregaFallback() {
        when(discovery.getInstances("consultas")).thenReturn(List.of());
        var sinInstancias = new ConsultaService(discovery, breakers, retries, "");
        assertThat(sinInstancias.consultar("intereses", "Bearer prueba", 100, 0))
                .containsEntry("disponible", false).containsEntry("datos", List.of());
        verify(discovery, times(2)).getInstances("consultas");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(llamadas.get()).isZero();
    }

    @Test
    void recursoInvalidoNoInvocaRemoto() {
        assertThatThrownBy(() -> service.consultar("otro", "Bearer prueba", 100, 0))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThat(llamadas.get()).isZero();
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }
}
