import base64
import json
import subprocess
import time
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
ENV = {}
for line in (ROOT / '.env.ec2').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        ENV[key.strip()] = value.strip().strip('"').strip("'")
BASE = 'https://' + ENV['PUBLIC_DOMAIN']
COMPOSE = ['docker', 'compose', '--env-file', '.env.ec2', '-f',
           'docker-compose.yaml', '-f', 'docker-compose.aws.yaml']
RESULTS = []


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def record(name):
    RESULTS.append({'comprobacion': name, 'resultado': 'CORRECTO'})
    print('Correcto: ' + name, flush=True)


def api(method, path, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = Request(BASE + path, method=method, headers=headers,
                      data=None if body is None else json.dumps(body).encode())
    try:
        with urlopen(request, timeout=25) as response:
            data = response.read().decode()
            return response.status, json.loads(data) if data else {}
    except HTTPError as error:
        error.read()
        return error.code, {}


def token(client, secret, scopes):
    basic = base64.b64encode((client + ':' + ENV[secret]).encode()).decode()
    request = Request(BASE + '/oauth2/token',
                      headers={'Authorization': 'Basic ' + basic},
                      data=urlencode({'grant_type': 'client_credentials', 'scope': scopes}).encode())
    with urlopen(request, timeout=25) as response:
        return json.load(response)['access_token']


def data(method, path, access, body=None):
    status, result = api(method, path, access, body)
    check(status in (200, 201, 202), f'{method} {path}: HTTP {status}')
    return result


def expected(method, path, access, status, body=None):
    actual, _ = api(method, path, access, body)
    check(actual == status, f'{method} {path}: HTTP {actual}, esperado {status}')
    record(f'HTTP {status} {path}')


def account(access):
    accounts = data('GET', '/api/web/resumen', access)['cuentas']
    active = [item for item in accounts if item['estado'] == 'ACTIVA']
    return active[0]['id'] if active else data('POST', '/api/web/cuentas', access, {'tipo': 'AHORRO'})['id']


def balance(access, identifier):
    accounts = data('GET', '/api/web/resumen', access)['cuentas']
    return Decimal(str(next(item['saldo'] for item in accounts if item['id'] == identifier)))


def payment(access, payload, atm=False):
    path = '/api/cajeros/retiros' if atm else '/api/web/pagos'
    result = data('POST', path, access, payload)
    for _ in range(60):
        if result['estado'] != 'PENDIENTE':
            break
        time.sleep(1)
        result = data('GET', path + '/' + payload['id'], access)
    check(result['estado'] == 'COMPLETADO', 'Operacion sin completar')


def compose(*args):
    result = subprocess.run(COMPOSE + list(args), cwd=ROOT, capture_output=True, text=True)
    check(result.returncode == 0, 'Fallo una comprobacion interna Docker')
    return result.stdout.strip()


def batch(method, path, access):
    args = ['exec', '-T', 'solicitudes', 'curl', '-sS', '--fail', '-X', method,
            '-H', 'Authorization: Bearer ' + access, 'http://localhost:8080' + path]
    return json.loads(compose(*args))


def main():
    with urlopen(BASE + '/health', timeout=25) as response:
        check(response.read().decode().strip() == 'OK', 'Salud HTTPS incorrecta')
    record('HTTPS con certificado validado')
    web = token('web-demo', 'OAUTH_WEB_SECRET', 'datos.read pagos.write cuentas.write clientes.write')
    mobile = token('movil-demo', 'OAUTH_MOVIL_SECRET', 'datos.read pagos.write')
    atm = token('cajeros-demo', 'OAUTH_CAJEROS_SECRET', 'datos.read retiros.write')
    other = token('otro-web', 'OAUTH_OTRO_SECRET', 'datos.read pagos.write cuentas.write clientes.write')
    operator = token('operador', 'OAUTH_CLIENT_SECRET', 'datos.read procesos.write')
    expected('GET', '/api/web/resumen', None, 401)
    expected('GET', '/api/movil/resumen', web, 403)
    expected('POST', '/api/web/cuentas', mobile, 403, {'tipo': 'AHORRO'})
    origin, destination = account(web), account(other)
    prefix = uuid.uuid4().hex
    payment(web, {'id': prefix+'d', 'tipo': 'DEPOSITO', 'destino': origin, 'monto': 1000})
    initial, other_initial = balance(web, origin), balance(other, destination)
    transfer = {'id': prefix+'t', 'tipo': 'TRANSFERENCIA', 'origen': origin, 'destino': destination, 'monto': 200}
    payment(web, transfer)
    payment(web, transfer)
    check(balance(web, origin) == initial-200, 'Debito incorrecto o duplicado')
    check(balance(other, destination) == other_initial+200, 'Credito incorrecto')
    record('Transferencia atomica e idempotente')
    full = data('GET', '/api/web/resumen', web)
    reduced = data('GET', '/api/movil/resumen', mobile)
    check(full['cliente']['email'] and not reduced.get('cliente')
          and 'cliente_id' not in reduced['cuentas'][0], 'Contratos BFF incorrectos')
    record('Contratos BFF')
    payment(atm, {'id': prefix+'r', 'tipo': 'RETIRO', 'origen': origin, 'monto': 50}, True)
    check(Decimal(str(data('GET', f'/api/cajeros/cuentas/{origin}/saldo', atm)['saldo'])) == initial-250,
          'Saldo del cajero incorrecto')
    record('Retiro autorizado')
    expected('GET', '/api/web/pagos/'+transfer['id'], other, 404)
    expected('POST', '/api/web/pagos', web, 409, {**transfer, 'monto': 201})
    rejection = {'id': prefix+'x', 'tipo': 'PAGO', 'origen': origin, 'monto': 999999999999}
    status, result = api('POST', '/api/web/pagos', web, rejection)
    check(status in (409, 202), f'Rechazo de fondos: HTTP {status}')
    if status == 202:
        for _ in range(60):
            result = data('GET', '/api/web/pagos/'+rejection['id'], web)
            if result['estado'] != 'PENDIENTE':
                break
            time.sleep(1)
        check(result['estado'] == 'RECHAZADO', 'Fondos insuficientes sin rechazo final')
        expected('POST', '/api/web/pagos', web, 409, rejection)
    else:
        record('HTTP 409 por fondos insuficientes')
    check(balance(web, origin) == initial-250, 'El rechazo altero el saldo')
    count = 0
    for _ in range(60):
        sql = "SELECT COUNT(*) FROM pagos.eventos_recibidos WHERE id='"+transfer['id']+"-COMPLETADO';"
        count = int(compose('exec', '-T', 'postgres', 'psql', '-U', 'banco', '-d', 'banco_xyz', '-tAc', sql))
        if count:
            break
        time.sleep(1)
    check(count == 1, 'Evento Kafka ausente o duplicado')
    record('Kafka y deduplicacion')
    for process in ('transacciones', 'intereses', 'estados-anuales'):
        request = batch('POST', '/api/procesos/'+process, operator)
        for _ in range(90):
            state = batch('GET', '/api/solicitudes/'+str(request['id']), operator)
            if state['estado'] in ('COMPLETED', 'FAILED'):
                break
            time.sleep(1)
        check(state['estado'] == 'COMPLETED', 'Batch fallido: '+process)
        record('Batch '+process)


if __name__ == '__main__':
    failure = None
    try:
        main()
    except Exception as error:
        failure = str(error)
        print('Fallo: '+failure)
    output = ROOT / 'docs/evidencia/verificacion-aws.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'fecha_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                                 'base_url': BASE, 'resultado': 'FALLIDO' if failure else 'CORRECTO',
                                 'comprobaciones': RESULTS, 'error': failure,
                                 'limite': 'Prueba funcional con datos simulados; no acredita carga sostenida ni reparto entre replicas.'}, indent=2))
    print('Evidencia: docs/evidencia/verificacion-aws.json')
    raise SystemExit(1 if failure else 0)
