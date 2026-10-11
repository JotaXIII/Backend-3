package cl.duoc.bancoxyz.clientes;
import cl.duoc.bancoxyz.comun.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
@Import({SeguridadConfiguration.class, BancoHttp.class, ApiErrors.class})
public class ClientesApplication {
    public static void main(String[] args) { SpringApplication.run(ClientesApplication.class, args); }
}
