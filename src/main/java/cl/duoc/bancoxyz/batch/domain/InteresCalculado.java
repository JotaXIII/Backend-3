package cl.duoc.bancoxyz.batch.domain;

import java.math.BigDecimal;

public record InteresCalculado(
        Long cuentaId,
        String nombre,
        BigDecimal saldoInicial,
        int edad,
        String tipo,
        BigDecimal tasa,
        BigDecimal interes,
        BigDecimal saldoFinal) {
}
