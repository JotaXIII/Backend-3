package cl.duoc.bancoxyz.comun;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public final class Identidad {
    private Identidad() {}

    public static long cliente(Jwt jwt) {
        if (jwt==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Identidad requerida");
        Number id = jwt.getClaim("cliente_id");
        if (id == null || id.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identidad de cliente requerida");
        }
        return id.longValue();
    }

    public static void canal(Jwt jwt, String canal) {
        if (jwt==null || !canal.equals(jwt.getClaimAsString("canal"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Canal no autorizado");
        }
    }

    public static boolean administrador(Jwt jwt) {
        Object scope=jwt.getClaim("scope");
        if (scope instanceof java.util.Collection<?> values) return values.contains("banco.admin");
        return scope instanceof String value && java.util.Arrays.asList(value.split(" ")).contains("banco.admin");
    }
}
