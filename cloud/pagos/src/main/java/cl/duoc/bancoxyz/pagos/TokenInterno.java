package cl.duoc.bancoxyz.pagos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class TokenInterno {
    private final RestClient http;
    private final String url;
    private final String secret;
    private String token;
    private Instant vencimiento = Instant.EPOCH;

    public TokenInterno(@Value("${app.oauth.token-url}") String url,
                        @Value("${app.oauth.internal-secret}") String secret) {
        this.url=url; this.secret=secret;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        http=RestClient.builder().requestFactory(factory).build();
    }
    public synchronized String obtener() {
        if (Instant.now().isAfter(vencimiento)) {
            var body = new LinkedMultiValueMap<String,String>();
            body.add("grant_type","client_credentials"); body.add("scope","operaciones.internal");
            var respuesta = http.post().uri(url).headers(h -> h.setBasicAuth("pagos-interno",secret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(Map.class);
            token="Bearer "+respuesta.get("access_token");
            vencimiento=Instant.now().plusSeconds(((Number)respuesta.get("expires_in")).longValue()-30);
        }
        return token;
    }
    public synchronized void invalidar() { vencimiento=Instant.EPOCH; }
}
