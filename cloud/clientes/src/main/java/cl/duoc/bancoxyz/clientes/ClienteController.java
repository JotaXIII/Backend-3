package cl.duoc.bancoxyz.clientes;
import cl.duoc.bancoxyz.comun.Identidad;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {
    private final JdbcTemplate db;
    public ClienteController(JdbcTemplate db) { this.db = db; }
    public record Datos(@NotBlank @Size(max=150) String nombre,
                        @NotBlank @Email @Size(max=180) String email,
                        @NotBlank @Size(max=40) String telefono) {}
    @GetMapping("/me")
    public Map<String,Object> propio(@AuthenticationPrincipal Jwt jwt) { return obtener(Identidad.cliente(jwt)); }
    @GetMapping("/{id}")
    public Map<String,Object> consultar(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        if (!Identidad.administrador(jwt) && id != Identidad.cliente(jwt))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no disponible");
        return obtener(id);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> crear(@Valid @RequestBody Datos datos) {
        try {
            return db.queryForMap("INSERT INTO clientes(nombre,email,telefono) VALUES (?,?,?) RETURNING *",
                    datos.nombre(), datos.email(), datos.telefono());
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email registrado");
        }
    }
    @PutMapping("/me")
    public Map<String,Object> actualizar(@Valid @RequestBody Datos datos, @AuthenticationPrincipal Jwt jwt) {
        long id = Identidad.cliente(jwt);
        obtener(id);
        try { db.update("UPDATE clientes SET nombre=?,email=?,telefono=? WHERE id=?",
                datos.nombre(), datos.email(), datos.telefono(), id); }
        catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email registrado");
        }
        return obtener(id);
    }
    private Map<String,Object> obtener(long id) {
        var filas = db.queryForList("SELECT * FROM clientes WHERE id=? AND activo=TRUE", id);
        if (filas.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no disponible");
        return filas.getFirst();
    }
}
