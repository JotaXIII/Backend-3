package cl.duoc.bancoxyz.consultas;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
// Expone resultados persistidos con lectura paginada.
public class ConsultaController {

    private final JdbcTemplate jdbcTemplate;

    public ConsultaController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/{recurso}")
    public List<Map<String, Object>> consultar(@PathVariable String recurso,
                                               @RequestParam(defaultValue = "100") int limite,
                                               @RequestParam(defaultValue = "0") int pagina) {
        if (limite < 1 || limite > 1000 || pagina < 0 || pagina > 100000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginación inválida");
        }
        String consulta = switch (recurso) {
            case "transacciones" -> "SELECT * FROM transacciones_procesadas ORDER BY id";
            case "intereses" -> "SELECT * FROM intereses_calculados ORDER BY cuenta_id";
            case "estados-anuales" -> "SELECT * FROM estados_cuenta_anuales ORDER BY cuenta_id, anio";
            case "rechazados" -> "SELECT * FROM registros_rechazados ORDER BY id";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso no disponible");
        };
        return jdbcTemplate.queryForList(consulta + " LIMIT ? OFFSET ?", limite, pagina * limite);
    }
}
