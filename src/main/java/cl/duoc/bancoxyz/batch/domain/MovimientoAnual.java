package cl.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MovimientoAnual(
        Long cuentaId,
        LocalDate fecha,
        String transaccion,
        BigDecimal monto,
        String descripcion) {
}
