package cl.duoc.bancoxyz.autorizacion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.oauth.secret=operador-test", "app.oauth.lector-secret=lector-test",
        "app.oauth.issuer=http://localhost:9000"})
@AutoConfigureMockMvc
class AutorizacionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"lector,lector-test,datos.read", "operador,operador-test,'datos.read procesos.write'"})
    void emiteClientCredentialsConScopesYFirmaJwks(String cliente, String secret, String scopes) throws Exception {
        var respuesta = mvc.perform(post("/oauth2/token").header("Authorization", basic(cliente, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("scope", scopes))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").isNumber()).andReturn();
        var body = mapper.readTree(respuesta.getResponse().getContentAsString());
        assertThat(Arrays.asList(body.path("scope").asText().split(" ")))
                .containsExactlyInAnyOrder(scopes.split(" "));
        var token = SignedJWT.parse(body.path("access_token").asText());
        var claims = token.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(cliente);
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:9000");
        assertThat(claims.getAudience()).containsExactly("banco-api");
        assertThat(claims.getStringListClaim("scope")).containsExactlyInAnyOrder(scopes.split(" "));
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(600_000);
        var jwks = mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andReturn();
        // Firma criptográfica válida.
        var keys = JWKSet.parse(jwks.getResponse().getContentAsString());
        var key = (RSAKey) keys.getKeyByKeyId(token.getHeader().getKeyID());
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).isFalse();
        assertThat(token.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"lector,incorrecta", "operador,incorrecta", "desconocido,incorrecta"})
    void credencialesIncorrectasSon401(String cliente, String secret) throws Exception {
        mvc.perform(post("/oauth2/token").header("Authorization", basic(cliente, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void lectorNoPuedePedirEscritura() throws Exception {
        mvc.perform(post("/oauth2/token").header("Authorization", basic("lector", "lector-test"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials")
                .param("scope", "procesos.write"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    private String basic(String cliente, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((cliente + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
