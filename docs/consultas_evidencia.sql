SELECT job_name, status, start_time, end_time
FROM batch_job_execution
JOIN batch_job_instance USING (job_instance_id)
ORDER BY start_time DESC;

SELECT * FROM transacciones_procesadas ORDER BY id;
SELECT * FROM resumen_transacciones ORDER BY tipo;
SELECT * FROM intereses_calculados ORDER BY cuenta_id;
SELECT * FROM movimientos_anuales ORDER BY cuenta_id, fecha;
SELECT * FROM estados_cuenta_anuales ORDER BY cuenta_id, anio;
SELECT job, etapa, datos, motivo, fecha_rechazo
FROM registros_rechazados
ORDER BY id;
