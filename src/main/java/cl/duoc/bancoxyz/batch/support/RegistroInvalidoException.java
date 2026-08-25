package cl.duoc.bancoxyz.batch.support;

public class RegistroInvalidoException extends RuntimeException {

    private final String datos;

    public RegistroInvalidoException(String mensaje, Object datos) {
        // Conserva los datos del registro para informar el motivo del rechazo.
        super(mensaje);
        this.datos = String.valueOf(datos);
    }

    public String getDatos() {
        return datos;
    }
}
