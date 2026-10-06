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
    estilos["Title"].fontName = "Helvetica-Bold"
    estilos["Title"].fontSize = 23
    estilos["Title"].leading = 28
    estilos["Title"].alignment = 0
    estilos["Title"].textColor = colors.HexColor("#17354A")
    estilos["Title"].spaceAfter = 6
    estilos.add(ParagraphStyle(
        name="Subtitulo", fontName="Helvetica", fontSize=11, leading=15,
        textColor=colors.HexColor("#577080"), spaceAfter=14,
    ))
    estilos.add(ParagraphStyle(
        name="CabeceraTabla", fontName="Helvetica-Bold", fontSize=8.5, leading=11,
        textColor=colors.white,
    ))
    estilos.add(ParagraphStyle(
        name="Celda", fontName="Helvetica", fontSize=8.5, leading=11,
        textColor=colors.HexColor("#293E4B"),
    ))
    estilos.add(ParagraphStyle(
        name="Texto", fontName="Helvetica", fontSize=9, leading=12, spaceAfter=7,
    ))
    estilos.add(ParagraphStyle(
        name="Seccion", fontName="Helvetica-Bold", fontSize=11, leading=14,
        textColor=colors.HexColor("#24465B"), spaceBefore=12, spaceAfter=8,
        keepWithNext=True,
    ))
    estilos.add(ParagraphStyle(
        name="Consola", fontName="Courier", fontSize=7.6, leading=10,
        textColor=colors.HexColor("#24465B"),
        backColor=colors.HexColor("#F0F4F7"), borderPadding=7, spaceAfter=10,
        borderColor=colors.HexColor("#DAE4EB"), borderWidth=0.5,
    ))
    contenido = []

    def texto(valor, estilo="Texto"):
        contenido.append(Paragraph(escape(str(valor)), estilos[estilo]))

    def tabla(encabezado, filas, anchos):
        celdas = [[Paragraph(escape(str(valor)), estilos["CabeceraTabla"]) for valor in encabezado]]
        celdas.extend([
            [Paragraph(escape(str(valor)), estilos["Celda"]) for valor in fila]
            for fila in filas
        ])
        bloque = Table(celdas, colWidths=anchos, repeatRows=1, hAlign="LEFT")
        bloque.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#24465B")),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1),
             [colors.white, colors.HexColor("#F3F6F8")]),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LINEBELOW", (0, 1), (-1, -1), 0.35, colors.HexColor("#DFE6EB")),
            ("LEFTPADDING", (0, 0), (-1, -1), 7),
            ("RIGHTPADDING", (0, 0), (-1, -1), 7),
            ("TOPPADDING", (0, 0), (-1, -1), 7),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ]))
        contenido.extend([bloque, Spacer(1, 8)])

    def consola(lineas):
        contenido.append(Paragraph(
            "<br/>".join(escape(str(linea)) for linea in lineas), estilos["Consola"],
        ))

    texto("Evidencia de ejecución", "Title")
    texto("Microservicios, seguridad y resiliencia", "Subtitulo")
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
    texto("El informe anual permanece en el volumen reportes. El procesamiento "
          "incluye limpieza, particionamiento y resultados consolidados.")

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
    texto("7. Resumen funcional", "Seccion")
    tabla(["Funcionalidad", "Evidencia"], [
        ["OAuth2", "Emisión, firma y respuestas 401/403"],
        ["Imágenes Docker", "Seis imágenes y servicios saludables"],
        ["Orquestación", "Ocho componentes y dependencias operativas"],
        ["Resilience4j", "Respuesta alternativa y OPEN/CLOSED"],
        ["JMS", "Tres procesos y recuperación del broker"],
    ], [4 * cm, 13 * cm])
    texto("Fuentes: verificacion-cloud.json, pruebas.json, evidencia-operativa.json "
          "y salidas-cloud.txt. El entorno validado es local, de una sola instancia.")

    def pie(canvas, documento):
        canvas.saveState()
        canvas.setStrokeColor(colors.HexColor("#DCE5EB"))
        canvas.setLineWidth(0.6)
        canvas.line(2 * cm, 1.7 * cm, A4[0] - 2 * cm, 1.7 * cm)
        canvas.setFillColor(colors.HexColor("#24465B"))
        canvas.rect(2 * cm, A4[1] - 0.9 * cm, 1.2 * cm, 0.12 * cm, fill=1, stroke=0)
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(colors.HexColor("#53616B"))
        canvas.drawString(2 * cm, 1.2 * cm, "Evidencia de ejecución")
        canvas.drawRightString(A4[0] - 2 * cm, 1.2 * cm, "Página " + str(documento.page))
        canvas.restoreState()

    documento = SimpleDocTemplate(
        str(destino), pagesize=A4, rightMargin=2 * cm, leftMargin=2 * cm,
        topMargin=1.5 * cm, bottomMargin=1.8 * cm,
        title="Evidencia de ejecución", author="",
    )
    documento.build(contenido, onFirstPage=pie, onLaterPages=pie)
    print("Informe generado:", destino)


if __name__ == "__main__":
    generar()
