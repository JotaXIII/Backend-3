package cl.duoc.bancoxyz.solicitudes;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api")
// Solicitudes y consultas autenticadas.
public class SolicitudController {

    private final SolicitudService solicitudes;
    private final ConsultaService consultas;

    public SolicitudController(SolicitudService solicitudes, ConsultaService consultas) {
        this.solicitudes = solicitudes;
        this.consultas = consultas;
    }

    @PostMapping("/procesos/{proceso}")
    public ResponseEntity<Map<String, Object>> solicitar(@PathVariable String proceso,
                                                         @AuthenticationPrincipal Jwt jwt) {
        var solicitud = solicitudes.solicitar(proceso, jwt.getSubject());
        return ResponseEntity.accepted().location(URI.create("/api/solicitudes/" + solicitud.get("id")))
                .body(solicitud);
    }

    @GetMapping("/solicitudes/{id}")
    public Map<String, Object> estado(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return solicitudes.consultar(id, jwt.getSubject());
    }

    @GetMapping("/consultas/{recurso}")
    public Map<String, Object> consultar(@PathVariable String recurso,
                                         @RequestHeader("Authorization") String token,
                                         @RequestParam(defaultValue = "100") int limite,
                                         @RequestParam(defaultValue = "0") int pagina) {
        return consultas.consultar(recurso, token, limite, pagina);
    }

    @GetMapping("/resiliencia")
    public Map<String, String> resiliencia() {
        return Map.of("circuito", consultas.estado());
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Void> errorRemoto(HttpClientErrorException exception) {
        return ResponseEntity.status(exception.getStatusCode()).build();
    }
}
