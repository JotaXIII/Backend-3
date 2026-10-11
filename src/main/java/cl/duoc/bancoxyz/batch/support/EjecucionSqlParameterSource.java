package cl.duoc.bancoxyz.batch.support;

import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.sql.Types;

public class EjecucionSqlParameterSource implements SqlParameterSource {
    private final BeanPropertySqlParameterSource datos;
    private final long ejecucion;
    public EjecucionSqlParameterSource(Object item,long ejecucion) {
        datos=new BeanPropertySqlParameterSource(item);this.ejecucion=ejecucion;
    }
    public boolean hasValue(String name) { return name.equals("ejecucionId") || datos.hasValue(name); }
    public Object getValue(String name) { return name.equals("ejecucionId")?ejecucion:datos.getValue(name); }
    public int getSqlType(String name) { return name.equals("ejecucionId")?Types.BIGINT:datos.getSqlType(name); }
    public String getTypeName(String name) { return datos.getTypeName(name); }
}
