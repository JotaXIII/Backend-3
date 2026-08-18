package cl.duoc.bancoxyz.batch.processor;

import cl.duoc.bancoxyz.batch.domain.MovimientoAnualCsv;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovimientoAnualProcessorTest {

    private final MovimientoAnualProcessor processor = new MovimientoAnualProcessor();

    @Test
    void aceptaRetiroNegativo() throws Exception {
        var resultado = processor.process(new MovimientoAnualCsv(
                101L, "2024-03-15", "retiro", "-500", "Retiro parcial"));

        assertThat(resultado.monto()).isEqualByComparingTo("-500");
    }

    @Test
    void rechazaMovimientosEnCero() {
        assertThatThrownBy(() -> processor.process(new MovimientoAnualCsv(
                107L, "2024-12-25", "deposito", "0", "Ingreso navideno")))
                .isInstanceOf(RegistroInvalidoException.class)
                .hasMessageContaining("monto invalido");
    }
}
