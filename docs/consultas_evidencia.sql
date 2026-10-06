SELECT job_name, status, start_time, end_time
FROM batch_job_execution
JOIN batch_job_instance USING (job_instance_id)
ORDER BY start_time DESC;

SELECT solicitud.id, solicitud.proceso, solicitud.estado, solicitud.creada,
       parametro.job_execution_id, ejecucion.status AS estado_ejecucion
FROM solicitudes solicitud
LEFT JOIN batch_job_execution_params parametro
       ON parametro.parameter_name = 'solicitud.id'
      AND parametro.parameter_value = solicitud.id
LEFT JOIN batch_job_execution ejecucion
       ON ejecucion.job_execution_id = parametro.job_execution_id
ORDER BY solicitud.creada DESC;

SELECT parametro.parameter_value AS solicitud_id,
       COUNT(*) AS ejecuciones
FROM batch_job_execution_params parametro
WHERE parametro.parameter_name = 'solicitud.id'
GROUP BY parametro.parameter_value
ORDER BY solicitud_id;

SELECT 'transacciones' AS resultado, COUNT(*) AS cantidad FROM transacciones_procesadas
UNION ALL
SELECT 'intereses', COUNT(*) FROM intereses_calculados
UNION ALL
SELECT 'movimientos_anuales', COUNT(*) FROM movimientos_anuales
UNION ALL
SELECT 'estados_anuales', COUNT(*) FROM estados_cuenta_anuales;

SELECT * FROM resumen_transacciones ORDER BY tipo;
SELECT * FROM transacciones_procesadas ORDER BY id;
SELECT * FROM intereses_calculados ORDER BY cuenta_id;
SELECT * FROM movimientos_anuales ORDER BY cuenta_id, fecha;
SELECT * FROM estados_cuenta_anuales ORDER BY cuenta_id, anio;

SELECT job, COUNT(*) AS rechazados
FROM registros_rechazados
GROUP BY job
ORDER BY job;

SELECT job, etapa, datos, motivo, fecha_rechazo
FROM registros_rechazados
ORDER BY id;
