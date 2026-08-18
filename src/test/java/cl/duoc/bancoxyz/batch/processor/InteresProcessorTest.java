package cl.duoc.bancoxyz.batch.processor;

import cl.duoc.bancoxyz.batch.domain.InteresCsv;
import cl.duoc.bancoxyz.batch.support.RegistroInvalidoException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteresProcessorTest {

    private final InteresProcessor processor = new InteresProcessor();

    @Test
    void calculaInteresDeAhorro() throws Exception {
        var resultado = processor.process(new InteresCsv(101L, "John Doe", "5000", "30", "ahorro"));

        assertThat(resultado.interes()).isEqualByComparingTo("50.00");
        assertThat(resultado.saldoFinal()).isEqualByComparingTo("5050.00");
    }

    @Test
    void rechazaTipoNoSoportado() {
        assertThatThrownBy(() -> processor.process(
                new InteresCsv(105L, "Charlie Green", "7000", "35", "hipoteca")))
                .isInstanceOf(RegistroInvalidoException.class)
                .hasMessageContaining("Tipo de cuenta");
    }
}
