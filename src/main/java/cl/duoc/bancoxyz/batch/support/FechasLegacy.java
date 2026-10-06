package cl.duoc.bancoxyz.batch.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public final class FechasLegacy {

    private static final List<DateTimeFormatter> FORMATOS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    private FechasLegacy() {
    }

    public static LocalDate parsear(String valor, Object registro) {
        if (valor != null) {
            for (DateTimeFormatter formato : FORMATOS) {
                try {
                    return LocalDate.parse(valor.trim(), formato);
                } catch (DateTimeParseException ignored) {
                    // Formatos de fecha alternativos.
                }
            }
        }
        throw new RegistroInvalidoException("Fecha invalida: " + valor, registro);
    }
}
