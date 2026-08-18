package cl.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Transaccion(Long id, LocalDate fecha, BigDecimal monto, String tipo, boolean anomalia) {
}
