import csv
import json
from pathlib import Path
from decimal import Decimal, ROUND_HALF_UP
from datetime import datetime, timezone
from collections import defaultdict

root = Path(__file__).resolve().parent.parent
fuente = root / 'src/main/resources/data'
def leer(nombre):
    with (fuente / nombre).open(encoding='utf-8-sig', newline='') as archivo:
        return list(csv.DictReader(archivo))
def fecha(valor):
    for formato in ('%Y-%m-%d', '%Y/%m/%d', '%d/%m/%Y'):
        try: return datetime.strptime(valor, formato).date().isoformat()
        except ValueError: pass
    raise ValueError('Fecha invalida')
def dinero(valor): return Decimal(str(valor)).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
transacciones = []
for fila in leer('transacciones.csv'):
    try:
        tipo = fila['tipo'].strip().lower()
        if tipo not in ('debito', 'credito'): continue
        monto = dinero(fila['monto'])
        transacciones.append((int(fila['id']), fecha(fila['fecha']), monto, tipo, monto <= 0))
    except (ValueError, ArithmeticError): pass
intereses = []
for fila in leer('intereses.csv'):
    try:
        saldo = dinero(fila['saldo']); edad = int(fila['edad'])
        tipo = fila['tipo'].strip().lower()
        if not fila['nombre'].strip() or saldo <= 0 or not 18 <= edad <= 75 or tipo not in ('ahorro', 'prestamo'): continue
        tasa = Decimal('0.01' if tipo == 'ahorro' else '0.02')
        interes = dinero(saldo * tasa)
        intereses.append((int(fila['cuenta_id']), saldo, tasa, interes, dinero(saldo + interes)))
    except (ValueError, ArithmeticError): pass
anuales = defaultdict(lambda: [0, Decimal('0'), Decimal('0')])
for fila in leer('cuentas_anuales.csv'):
    try:
        monto = dinero(fila['monto']); tipo = fila['transaccion'].strip().lower()
        if not fila['descripcion'].strip() or tipo not in ('deposito', 'retiro', 'compra') or monto == 0: continue
        if (tipo == 'deposito') != (monto > 0): continue
        clave = int(fila['cuenta_id']), int(fecha(fila['fecha'])[:4])
        valor = anuales[clave]; valor[0] += 1
        valor[1 if monto > 0 else 2] += abs(monto)
    except (ValueError, ArithmeticError): pass
estados = [(cuenta, anio, v[0], dinero(v[1]), dinero(v[2]), dinero(v[1]-v[2])) for (cuenta, anio), v in anuales.items()]
actual = json.loads((root / 'docs/evidencia/resultados-batch.json').read_text(encoding='utf-8'), parse_float=Decimal)
obtenidos_t = [(f['id'], f['fecha'], dinero(f['monto']), f['tipo'], f['anomalia']) for f in actual['transacciones']]
obtenidos_i = [(f['cuenta_id'], dinero(f['saldo_inicial']), Decimal(str(f['tasa'])), dinero(f['interes']), dinero(f['saldo_final'])) for f in actual['intereses']]
obtenidos_a = [(f['cuenta_id'], f['anio'], f['cantidad_movimientos'], dinero(f['total_depositos']), dinero(f['total_cargos']), dinero(f['saldo_anual'])) for f in actual['estadosAnuales']]
assert sorted(transacciones) == sorted(obtenidos_t), 'Diferencias en transacciones'
assert sorted(intereses) == sorted(obtenidos_i), 'Diferencias en intereses'
assert sorted(estados) == sorted(obtenidos_a), 'Diferencias en estados anuales'
resultado = {'fecha': datetime.now(timezone.utc).isoformat(), 'comparacion': 'Referencia independiente de reglas documentadas',
             'transacciones': len(transacciones), 'intereses': len(intereses), 'estadosAnuales': len(estados),
             'diferencias': 0, 'alcance': 'CSV incluidos y reglas configuradas; sin ejecucion de COBOL'}
(root / 'docs/evidencia/comparacion-batch.json').write_text(json.dumps(resultado, ensure_ascii=False, indent=2), encoding='utf-8')
print('Tres procesos sin diferencias respecto de la referencia')
