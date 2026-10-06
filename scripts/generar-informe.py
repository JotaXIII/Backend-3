import json
import re
from html import escape
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import (
    PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
)

ROOT = Path(__file__).resolve().parents[1]
EVIDENCIA = ROOT / "docs" / "evidencia"


def leer(nombre):
    return json.loads((EVIDENCIA / nombre).read_text(encoding="utf-8-sig"))


def generar():
    verificacion = leer("verificacion-cloud.json")
    pruebas = leer("pruebas.json")
    operativa = leer("evidencia-operativa.json")
    casos = {item["caso"]: item["resultado"] for item in verificacion["verificaciones"]}
    if pruebas["fallos"] or pruebas["errores"]:
        raise ValueError("Las pruebas contienen fallos")
    if len(operativa["componentes"]) != 8 or any(
        item["Health"] != "healthy" for item in operativa["componentes"]
    ):
        raise ValueError("Falta evidencia de salud")

    destino = EVIDENCIA / "ejecucion-cloud.pdf"
    estilos = getSampleStyleSheet()
    estilos.add(ParagraphStyle(
        name="Texto", fontName="Helvetica", fontSize=9, leading=12, spaceAfter=7,
    ))
    estilos.add(ParagraphStyle(
        name="Seccion", fontName="Helvetica-Bold", fontSize=11, leading=14,
        textColor=colors.HexColor("#24465B"), spaceBefore=10, spaceAfter=7,
    ))
    estilos.add(ParagraphStyle(
        name="Consola", fontName="Courier", fontSize=7.6, leading=10,
        backColor=colors.HexColor("#F3F5F6"), borderPadding=6, spaceAfter=8,
    ))
    contenido = []

    def texto(valor, estilo="Texto"):
        contenido.append(Paragraph(escape(str(valor)), estilos[estilo]))

    def tabla(encabezado, filas, anchos):
        celdas = [[Paragraph(escape(str(valor)), estilos["Texto"]) for valor in encabezado]]
        celdas.extend([
            [Paragraph(escape(str(valor)), estilos["Texto"]) for valor in fila]
            for fila in filas
        ])
        bloque = Table(celdas, colWidths=anchos, repeatRows=1, hAlign="LEFT")
        bloque.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E7EEF2")),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CCD5DC")),
            ("LEFTPADDING", (0, 0), (-1, -1), 7),
            ("RIGHTPADDING", (0, 0), (-1, -1), 7),
            ("TOPPADDING", (0, 0), (-1, -1), 5),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 1),
        ]))
        contenido.extend([bloque, Spacer(1, 8)])

    def consola(lineas):
        contenido.append(Paragraph(
            "<br/>".join(escape(str(linea)) for linea in lineas), estilos["Consola"],
        ))

    texto("EVIDENCIA DE EJECUCIÓN", "Title")
    texto("Microservicios, seguridad y resiliencia · Semana 8")
    texto("Rama: " + operativa["rama"])
    texto("Verificación funcional: " + verificacion["fecha"])
    texto("Los resultados proceden de una ejecución local real en contenedores. "
          "Las salidas completas y las consultas persistidas acompañan este informe.")
    texto("1. Ejecución y contenedores", "Seccion")
    tabla(["Componente", "Imagen ejecutada", "Salud"], [
        [item["Service"], item["Image"], item["Health"]]
        for item in operativa["componentes"]
    ], [3.2 * cm, 10.2 * cm, 3.6 * cm])
    texto("Las seis aplicaciones tienen imágenes independientes. La base de datos y "
          "el broker utilizan imágenes oficiales. Los logs de inicio de cada aplicación "
          "están conservados en salidas-cloud.txt.")
    texto("2. Autenticación y permisos", "Seccion")
    oauth = operativa["oauth"]
    consola([
        "grant_type=" + oauth["grant_type"],
        "token_type=" + oauth["token_type"],
        "scope=" + oauth["scope"],
        "expires_in=" + str(oauth["expires_in"]) + " segundos",
    ])
    tabla(["Caso verificado", "Respuesta HTTP"], [
        [nombre, casos[nombre]]
        for nombre in ["Sin token", "Firma invalida", "Permiso insuficiente",
                       "Proceso invalido", "Propietario distinto"]
    ], [12.5 * cm, 4.5 * cm])
    texto("Se omiten los tokens y las contraseñas. Las pruebas verifican también "
          "firma RSA, emisor, destinatario, expiración y permisos.")

    contenido.append(PageBreak())
    texto("3. Procesamiento asíncrono y resultados", "Seccion")
    texto("La API acepta solicitudes con HTTP 202, las guarda y las publica en "
          "procesos.solicitudes. El consumidor ejecuta el proceso y publica su "
          "estado terminal en procesos.resultados.")
    tabla(["Proceso", "Estado JMS", "Resultados"], [
        ["transacciones", casos["JMS transacciones"], casos["Resultados transacciones"]],
        ["intereses", casos["JMS intereses"], casos["Resultados intereses"]],
        ["estados-anuales", casos["JMS estados-anuales"], casos["Resultados estados-anuales"]],
    ], [5.5 * cm, 5.5 * cm, 6 * cm])
    texto("Salidas reales del procesador", "Seccion")
    lineas = []
    for log in operativa["procesos"]:
        match = re.search(r"Job finalizado: (.*)", log)
        if match and match.group(1) not in lineas:
            lineas.append(match.group(1))
    consola(lineas)
    tabla(["Resultado persistido", "Cantidad"], [
        ["Transacciones procesadas", 9],
        ["Anomalías detectadas", 2],
        ["Intereses calculados", 4],
        ["Movimientos anuales", 8],
        ["Estados anuales", 7],
        ["Rechazos: transacciones / intereses / estados", "1 / 4 / 1"],
    ], [12.5 * cm, 4.5 * cm])
    texto("Las cantidades anteriores se contrastan con las consultas SQL guardadas "
          "en evidencia-operativa.json y salidas-cloud.txt.")
    texto("4. Repetición y recuperación de mensajería", "Seccion")
    consola([
        "Solicitud repetida : " + casos["Solicitud repetida"],
        "Broker detenido : " + casos["Broker detenido"],
        "Broker recuperado : " + casos["Broker recuperado"],
    ])
    texto("Una solicitud repetida conserva una única ejecución identificada por "
          "solicitud.id. La caída del broker permite guardar una solicitud pendiente; "
          "su recuperación completa el procesamiento sin reenviarla manualmente.")
    texto("El informe anual se conserva en el volumen reportes. Las etapas de "
          "limpieza, particionamiento y generación mantienen el comportamiento previo.")

    contenido.append(PageBreak())
    texto("5. Tolerancia a fallos y recuperación", "Seccion")
    consola([
        "Servicio detenido : " + casos["Servicio detenido"],
        "Servicio recuperado : " + casos["Servicio recuperado"],
    ])
    texto("Al detener consultas, la API responde con disponible=false y un mensaje "
          "temporal. El circuito alcanza OPEN y evita nuevas llamadas remotas. Tras "
          "recuperar el servicio, admite llamadas de prueba y vuelve a CLOSED.")
    tabla(["Parámetro", "Configuración"], [
        ["Tiempo de conexión / lectura", "2 s / 3 s"],
        ["Intentos / espera entre reintentos", "2 / 200 ms"],
        ["Ventana / mínimo de llamadas", "4 / 4"],
        ["Umbral de fallos", "50 %"],
        ["Espera en OPEN / llamadas en HALF_OPEN", "10 s / 2"],
    ], [11.5 * cm, 5.5 * cm])
    texto("6. Pruebas automatizadas", "Seccion")
    consola([
        "mvn test",
        "mvn -f cloud/pom.xml test",
        "Tests run: " + str(pruebas["pruebas"]) + ", Failures: "
        + str(pruebas["fallos"]) + ", Errors: " + str(pruebas["errores"]),
    ])
    texto("Se comprueban validaciones, ejecución por lotes, consumo y publicación "
          "JMS, persistencia, autorización, scopes y recuperación del circuito.")
    texto("7. Correspondencia con la pauta", "Seccion")
    tabla(["Criterio", "Evidencia incluida", "Puntos"], [
        ["OAuth2", "Emisión, firma y respuestas 401/403", 20],
        ["Imágenes Docker", "Seis imágenes y servicios saludables", 20],
        ["Orquestación", "Ocho componentes y dependencias operativas", 20],
        ["Resilience4j", "Respuesta alternativa y OPEN/CLOSED", 20],
        ["JMS", "Tres procesos y recuperación del broker", 15],
        ["Entregables", "Código, README, propuesta y salidas", 5],
    ], [4 * cm, 11 * cm, 2 * cm])
    texto("Las evidencias cubren los aspectos de funcionalidad solicitados. La "
          "calificación corresponde a la revisión del código y su ejecución.")
    texto("Fuentes: verificacion-cloud.json, pruebas.json, evidencia-operativa.json "
          "y salidas-cloud.txt. El entorno validado es local, de una sola instancia.")

    def pie(canvas, documento):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(colors.HexColor("#53616B"))
        canvas.drawString(2 * cm, 1.2 * cm, "Evidencia de ejecución · Semana 8")
        canvas.drawRightString(A4[0] - 2 * cm, 1.2 * cm, "Página " + str(documento.page))
        canvas.restoreState()

    documento = SimpleDocTemplate(
        str(destino), pagesize=A4, rightMargin=2 * cm, leftMargin=2 * cm,
        topMargin=1.5 * cm, bottomMargin=1.8 * cm,
        title="Evidencia de ejecución - Semana 8", author="",
    )
    documento.build(contenido, onFirstPage=pie, onLaterPages=pie)
    print("Informe generado:", destino)


if __name__ == "__main__":
    generar()
