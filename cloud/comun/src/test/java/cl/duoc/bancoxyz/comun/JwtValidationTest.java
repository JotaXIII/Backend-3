package cl.duoc.bancoxyz.comun;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidationTest {

    private HttpServer server;
    private JwtDecoder decoder;
    private NimbusJwtEncoder encoder;

    @BeforeEach
    void preparar() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID("prueba").build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
            exchange.close();
        });
        server.start();
        decoder = new SeguridadConfiguration().jwtDecoder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks", "http://emisor");
    }

    @AfterEach
    void cerrar() {
        server.stop(0);
    }

    @Test
    void aceptaFirmaEmisorDestinatarioYVigencia() {
        assertThat(decoder.decode(token("http://emisor", "banco-api", Instant.now().plusSeconds(600)))
                .getSubject()).isEqualTo("cliente");
    }

    @Test
    void rechazaEmisorDistinto() {
        assertThatThrownBy(() -> decoder.decode(
                token("http://otro", "banco-api", Instant.now().plusSeconds(600))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaDestinatarioDistinto() {
        assertThatThrownBy(() -> decoder.decode(
                token("http://emisor", "otro", Instant.now().plusSeconds(600))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaTokenVencido() {
        assertThatThrownBy(() -> decoder.decode(
                token("http://emisor", "banco-api", Instant.now().minusSeconds(120))))
                .isInstanceOf(JwtException.class);
    }

    private String token(String issuer, String audience, Instant expires) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("cliente")
                .audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(expires).build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
