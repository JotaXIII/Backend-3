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
            @Value("${app.oauth.lector-secret}") String readerSecret,
            @Value("${app.oauth.web-secret:web-local}") String webSecret,
            @Value("${app.oauth.movil-secret:movil-local}") String movilSecret,
            @Value("${app.oauth.cajeros-secret:cajeros-local}") String cajerosSecret,
            @Value("${app.oauth.internal-secret:interno-local}") String internalSecret,
            @Value("${app.oauth.admin-secret:admin-local}") String adminSecret,
            @Value("${app.oauth.otro-secret:otro-local}") String otroSecret) {
        return new InMemoryRegisteredClientRepository(
                cliente("operador", secret, true), cliente("lector", readerSecret, false),
                canal("web-demo",webSecret,"datos.read","pagos.write","cuentas.write","clientes.write"),
                canal("movil-demo",movilSecret,"datos.read","pagos.write"),
                canal("cajeros-demo",cajerosSecret,"datos.read","retiros.write"),
                canal("otro-web",otroSecret,"datos.read","pagos.write","cuentas.write","clientes.write"),
                canal("pagos-interno",internalSecret,"operaciones.internal"),
                canal("administrador",adminSecret,"datos.read","banco.admin","procesos.write"));
    }

    private RegisteredClient canal(String id,String secret,String... scopes) {
        var builder=RegisteredClient.withId(id).clientId(id)
                .clientSecret(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(10)).build());
        for(String scope:scopes) builder.scope(scope);
        return builder.build();
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
    // Clave de firma persistente.
    public JWKSource<SecurityContext> claves(@Value("${app.oauth.key-file:build/claves/firma.properties}") String archivo) throws Exception {
        var ruta=java.nio.file.Path.of(archivo);
        java.nio.file.Files.createDirectories(ruta.toAbsolutePath().getParent());
        var datos=new java.util.Properties();
        if (!java.nio.file.Files.exists(ruta)) {
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
            var pair=generator.generateKeyPair();
            datos.setProperty("publica",java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            datos.setProperty("privada",java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            datos.setProperty("id",UUID.randomUUID().toString());
            try(var output=java.nio.file.Files.newOutputStream(ruta,java.nio.file.StandardOpenOption.CREATE_NEW)) {
                datos.store(output,null);
            }
        }
        try(var input=java.nio.file.Files.newInputStream(ruta)) { datos.load(input); }
        var factory=java.security.KeyFactory.getInstance("RSA");
        var publica=(RSAPublicKey)factory.generatePublic(new java.security.spec.X509EncodedKeySpec(
                java.util.Base64.getDecoder().decode(datos.getProperty("publica"))));
        var privada=(RSAPrivateKey)factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(
                java.util.Base64.getDecoder().decode(datos.getProperty("privada"))));
        var rsa = new RSAKey.Builder(publica).privateKey(privada).keyID(datos.getProperty("id")).build();
        return new ImmutableJWKSet<>(new JWKSet(rsa));
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> destinatario() {
        return context -> {
            context.getClaims().audience(List.of("banco-api"));
            String cliente=context.getRegisteredClient().getClientId();
            switch(cliente) {
                case "web-demo" -> context.getClaims().claim("cliente_id",1L).claim("canal","web");
                case "movil-demo" -> context.getClaims().claim("cliente_id",1L).claim("canal","movil");
                case "cajeros-demo" -> context.getClaims().claim("cliente_id",1L).claim("canal","cajeros");
                case "otro-web" -> context.getClaims().claim("cliente_id",2L).claim("canal","web");
                default -> { }
            }
        };
    }

    @Bean
    public AuthorizationServerSettings servidor(@Value("${app.oauth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
}
