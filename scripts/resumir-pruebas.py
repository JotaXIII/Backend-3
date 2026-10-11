import json
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone

root=Path(__file__).resolve().parent.parent
suites=[]
for archivo in sorted(root.glob('**/target/surefire-reports/TEST-*.xml')):
    suite=ET.parse(archivo).getroot()
    suites.append({'nombre':suite.attrib['name'],'pruebas':int(suite.attrib['tests']),
                   'fallos':int(suite.attrib['failures']),'errores':int(suite.attrib['errors']),
                   'omitidas':int(suite.attrib.get('skipped',0)),
                   'segundos':float(suite.attrib['time']),
                   'informe':str(archivo.relative_to(root)).replace('\\','/')})
resultado={'fecha':datetime.now(timezone.utc).isoformat(),
           'comandos':['mvn -o test','mvn -o -f cloud/pom.xml -Ppruebas-sin-kafka test'],
           'pruebasEjecutadas':sum(s['pruebas']-s['omitidas'] for s in suites),
           'fallos':sum(s['fallos'] for s in suites),'errores':sum(s['errores'] for s in suites),
           'omitidas':sum(s['omitidas'] for s in suites),'suites':suites,
           'composeLocal':'Configuracion validada con docker compose config --quiet',
           'composeAWS':'Configuracion validada con dominio de prueba',
           'kafka':{'estado':'PENDIENTE','motivo':'Cliente Kafka ausente de cache y acceso de red bloqueado'},
           'contenedores':{'estado':'PENDIENTE','motivo':'Acceso al motor Docker denegado'},
           'aws':{'estado':'PENDIENTE','motivo':'Destino y dominio sin definir'}}
archivo=root/'docs/evidencia/validacion-actual.json'
archivo.parent.mkdir(parents=True,exist_ok=True)
archivo.write_text(json.dumps(resultado,ensure_ascii=False,indent=2),encoding='utf-8')
print(f"{resultado['pruebasEjecutadas']} pruebas, {resultado['fallos']} fallos, {resultado['errores']} errores")
