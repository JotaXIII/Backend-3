package cl.duoc.bancoxyz.comun;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Map<String,String>> duplicado(org.springframework.dao.DuplicateKeyException error) {
        return ResponseEntity.status(409).body(Map.of("mensaje","Identificador registrado"));
    }
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, String>> remoto(HttpClientErrorException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("mensaje", "Operacion rechazada"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validacion(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", "Datos invalidos"));
    }
}
