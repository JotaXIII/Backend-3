package cl.duoc.bancoxyz.bffcajeros;
import cl.duoc.bancoxyz.comun.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
@Import({SeguridadConfiguration.class, BancoHttp.class, ApiErrors.class})
public class BffCajerosApplication {
    public static void main(String[] args) { SpringApplication.run(BffCajerosApplication.class, args); }
}
