# Entregables actualizados

## Revisión de la versión anterior

La base se encontraba en `Backend 3/Actividad`, commit `bb790a4`. Contenía el README, `docs/consultas_evidencia.sql` y `docs/evidencia/ejecucion.pdf`.

El PDF anterior tiene tres páginas y ninguna imagen. Documenta siete pruebas, particiones y resultados de los tres procesos. Corresponde a la semana 3 y se conserva como antecedente.

## Versión actual

Rama: `feature/semana-8-microservicios`.

| Entregable | Archivo | Actualización |
|---|---|---|
| Documentación | `README.md` | arquitectura, ejecución, permisos, endpoints y pruebas |
| Propuesta técnica | `docs/propuesta-tecnica.md` | decisiones y correspondencia con los requisitos |
| Consultas de evidencia | `docs/consultas_evidencia.sql` | seguimiento de solicitudes, correlación JMS y deduplicación |
| Informe de ejecución | `docs/evidencia/ejecucion-cloud.pdf` | tres páginas con evidencia actual |
| Salidas de consola | `docs/evidencia/salidas-cloud.txt` | OAuth2, imágenes, inicio de servicios, procesos y SQL |
| Verificación funcional | `docs/evidencia/verificacion-cloud.json` | permisos, resultados, repetición y recuperación |
| Evidencia operativa | `docs/evidencia/evidencia-operativa.json` | token sin secreto, salud, imágenes y persistencia |
| Pruebas | `docs/evidencia/pruebas.json` | 65 pruebas, cero fallos y cero errores |
| Código | `src/`, `cloud/` y archivos de construcción | procesos y servicios |
| Paquete | `Exp3_S8_JuanCarlos_Osega.zip` | código, documentación y evidencias, sin cachés |

## Cobertura de la pauta

| Criterio | Puntaje máximo | Comprobación incluida |
|---|---:|---|
| OAuth2 funcional | 20 | client_credentials, Bearer, firma, scopes, 401 y 403 |
| Imágenes funcionales | 20 | seis imágenes de aplicaciones y ocho componentes saludables |
| Orquestación funcional | 20 | dependencias, puertos, red y volúmenes persistentes |
| Resilience4j | 20 | reintento, fallback, apertura y recuperación del circuito |
| Kafka o JMS | 15 | solicitudes JMS, tres procesos completados y recuperación del broker |
| Código, documentación y ejecución | 5 | repositorio, README, propuesta, PDF y salidas verificables |

Los archivos muestran los comportamientos solicitados para el nivel completamente logrado. La evaluación final depende de la revisión y ejecución del proyecto.

## Capturas necesarias

Cantidad mínima adicional: **0**.

Las instrucciones permiten capturas **o salidas de consola**. La versión anterior utilizaba salidas y la versión actual mantiene ese formato, ampliado con logs reales de inicio de cada aplicación, imágenes, seguridad, mensajería, resultados y recuperación.

El informe PDF permite revisar los resultados sin depender de imágenes. Las salidas completas y los JSON permiten comprobarlos. No se requieren capturas de cada servicio, de un cliente HTTP ni de la consola de mensajería para acreditar esos mismos resultados.

## Regenerar la evidencia

Desde la carpeta del proyecto, con los ocho componentes activos:

```powershell
powershell -NoProfile -File scripts/verificar.ps1 -ProbarInterrupciones
powershell -NoProfile -File scripts/recopilar-evidencia.ps1
python scripts/generar-informe.py
```

El primer comando detiene temporalmente consultas y mensajería y restaura su funcionamiento. El segundo reúne evidencia de la ejecución y filtra secretos. El tercero genera el PDF mediante ReportLab; para regenerarlo se necesita Python con `reportlab` instalado.

Para ejecutar las consultas directamente:

```powershell
Get-Content -Raw -Encoding UTF8 docs/consultas_evidencia.sql |
    docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -P pager=off -U banco -d banco_xyz
```

El PDF y las salidas ya están incluidos; no requieren instalar Python para revisar o ejecutar las aplicaciones.

Para generar un comprimido nuevo conservando el anterior:

```powershell
powershell -NoProfile -File scripts/empaquetar.ps1 -Destino ../Exp3_S8_JuanCarlos_Osega_actualizado.zip
```

Si ese comprimido ya existe y se desea reemplazarlo, agregar `-Actualizar`.
