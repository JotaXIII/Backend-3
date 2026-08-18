package cl.duoc.bancoxyz.batch.support;

public class RegistroInvalidoException extends RuntimeException {

    private final String datos;

    public RegistroInvalidoException(String mensaje, Object datos) {
        super(mensaje);
        this.datos = String.valueOf(datos);
    }

    public String getDatos() {
        return datos;
    }
}
