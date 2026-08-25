package cl.duoc.bancoxyz.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// Punto de entrada de la aplicación de procesamiento por lotes.
public class BancoXyzBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BancoXyzBatchApplication.class, args);
    }
}
