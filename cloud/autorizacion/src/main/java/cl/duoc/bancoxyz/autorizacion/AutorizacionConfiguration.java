package cl.duoc.bancoxyz.autorizacion;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Configuration
// Tokens con permisos explícitos.
public class AutorizacionConfiguration {

    @Bean
    @Order(1)
    public SecurityFilterChain oauthSecurity(HttpSecurity http) throws Exception {
        var configurer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        return http.securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, server -> {})
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain healthSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().denyAll()).build();
    }

    @Bean
    public RegisteredClientRepository clientes(
            @Value("${app.oauth.secret}") String secret,
            @Value("${app.oauth.lector-secret}") String readerSecret) {
        return new InMemoryRegisteredClientRepository(
                cliente("operador", secret, true), cliente("lector", readerSecret, false));
    }

    private RegisteredClient cliente(String id, String secret, boolean escritura) {
        var builder = RegisteredClient.withId(id).clientId(id)
                .clientSecret(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("datos.read")
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(10)).build());
        if (escritura) {
            builder.scope("procesos.write");
        }
        return builder.build();
    }

    @Bean
    // Clave de firma temporal.
    public JWKSource<SecurityContext> claves() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(UUID.randomUUID().toString()).build();
        return new ImmutableJWKSet<>(new JWKSet(rsa));
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> destinatario() {
        return context -> context.getClaims().audience(List.of("banco-api"));
    }

    @Bean
    public AuthorizationServerSettings servidor(@Value("${app.oauth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
}
