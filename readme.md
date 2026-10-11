# Banco XYZ

Servicios bancarios independientes, tres BFF y procesamiento por lotes con seguridad distribuida.

Repositorio: https://github.com/JotaXIII/Backend-3

## Ejecución

Requisitos: Docker con contenedores Linux, Compose 2.24.4 o posterior y acceso a imágenes y Maven Central. La compilación local utiliza Java 21 y Maven 3.9.

```powershell
Copy-Item .env.example .env
docker compose up -d --build --wait --wait-timeout 300
powershell -NoProfile -File scripts/verificar-banco.ps1
```

La configuración crea volúmenes propios bajo el proyecto `banco_xyz_eft`. El entorno anterior conserva sus volúmenes. No reutilizar una base de datos anterior sin una migración del esquema.

## Componentes

| Componente | Función | Puerto local |
|---|---|---|
| bff-web | Perfil completo, cuentas, pagos y mantenimiento | 8090 |
| bff-movil | Saldos compactos y pagos | 8091 |
| bff-cajeros | Saldo y retiros | 8092 |
| clientes | Información personal y perfiles | Interno |
| cuentas | Apertura, mantenimiento, cierre y libro de movimientos | Interno |
| pagos | Operaciones idempotentes, recuperación y outbox | Interno |
| autorizacion | Tokens OAuth2 y claves persistentes | 9000 |
| descubrimiento | Eureka | 8761 |
| configuracion | Spring Cloud Config | 8888 |
| solicitudes | Solicitudes batch y consultas resilientes | 8080 |
| consultas | Últimos resultados batch completados | Interno |
| batch | Tres jobs particionados | Interno |
| Kafka | Eventos bancarios y auditoría | Interno |
| Artemis | Solicitudes y resultados batch | 8161 |
| PostgreSQL | Esquemas de clientes, cuentas, pagos y resultados | 5433 |

## Documentación

- [Ejecución y pruebas](instrucciones.md).
- [Despliegue en AWS](despliegue.md).
- [Arquitectura y decisiones](docs/arquitectura.md).
- [Justificacion de infraestructura](docs/justificacion-infraestructura.md).
- [AWS Academy](docs/aws-academy.md).

## Consistencia

Cuentas es propietario del saldo. Los débitos y créditos se realizan en una sola transacción con bloqueo ordenado de cuentas. El identificador de operación impide repetir el movimiento. Pagos conserva las operaciones pendientes ante una caída remota y reutiliza el mismo identificador al recuperarlas. La finalización y su evento se guardan juntos en una outbox. La publicación Kafka admite entrega repetida y el consumidor deduplica por identificador.

Las ejecuciones batch conservan resultados por instancia. Las consultas utilizan una publicación que solo cambia al completar un job. Las tasas mensuales de referencia son 1% para ahorro y 2% para préstamo; el intervalo de edad permitido es 18 a 75 años. Estas reglas son supuestos explícitos y deben ajustarse al contrato bancario real.

## Seguridad

Los canales de demostración tienen identidades firmadas de los clientes 1 y 2. Sus credenciales pertenecen a aplicaciones confiables; no deben incluirse en JavaScript público ni en una aplicación móvil distribuida. Una identidad de usuarios reales requiere integración con un proveedor de identidad y un flujo apropiado, como Authorization Code con PKCE.

El despliegue público utiliza HTTPS mediante Caddy. En la red privada de Compose los servicios se comunican por HTTP. El dominio productivo debe configurar `OAUTH_ISSUER=https://dominio`; las claves se conservan en un volumen. Cambiar todos los secretos antes de publicar el entorno.

## Validación

```powershell
mvn test
mvn -f cloud/pom.xml test
```

El perfil `pruebas-sin-kafka` excluye exclusivamente el adaptador Kafka para verificar reglas, seguridad, BFF y transacciones cuando falta esa dependencia. No valida Kafka y no debe utilizarse para construir imágenes de despliegue. La configuración completa y la verificación de contenedores requieren las dependencias normales.

Los resultados y capturas se generan localmente y quedan excluidos del repositorio. Las verificaciones reproducibles se ejecutan con `scripts/verificar-banco.ps1` y, en EC2, `scripts/verificar-aws.py`. El despliegue comprobado utiliza HTTPS, dos replicas de clientes, cuentas y pagos, y completa las verificaciones funcionales y los tres procesos batch. Estas comprobaciones no acreditan carga sostenida ni alta disponibilidad entre hosts.
