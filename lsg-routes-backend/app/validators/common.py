"""Tipos de datos y utilidades compartidas por los validadores anti-cheat."""

import math
from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class FixData:
    """Punto GPS de una sesion."""

    lat: float
    lon: float
    captured_at: datetime
    speed_mps: float | None = None
    is_from_mock_provider: bool = False
    accuracy_m: float | None = None


@dataclass
class AccelData:
    """Bucket de acelerometro de un segundo."""

    magnitude_mean: float
    magnitude_variance: float
    bucket_started_at: datetime
    bucket_seconds: int = 1


@dataclass
class StepData:
    """Delta de pasos."""

    steps_delta: int
    captured_at: datetime


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distancia Haversine entre dos puntos en metros."""
    R = 6_371_000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


_NOISE_FLOOR_FACTOR = 2.0
_MAX_NOISE_FLOOR_M = 30.0
# Precision (radio, m) sobre la cual un fix no sirve ni para medir. No lo cubre el piso
# de ruido: su techo hace que un fix con 200 m de precision se filtre como si estuviera
# errado por 30 m, y entra entero.
MAX_ACCURACY_M = 50.0
# Quietud a partir de la cual se reinicia el ancla. Por debajo de 1,2 km/h no se supera
# un piso de 20 m en 60 s, y esa velocidad ya cae bajo el umbral de "detenido" del
# recorrido libre (1,5 km/h): nadie que se mueva de verdad pierde su tramo por esto.
_STOP_WINDOW_S = 60.0


def _noise_floor_m(a: FixData, b: FixData) -> float:
    """Desplazamiento bajo el cual el movimiento no se distingue del ruido del GPS.

    Es el doble de la peor precision declarada de los dos fixes: accuracy_m es un radio
    (~68% de confianza), asi que dos lecturas del mismo punto quieto pueden separarse
    hasta el diametro. Usar el radio dejaria pasar justo el jitter que se quiere ignorar.
    El techo evita que un fix con precision absurda se trague un tramo real.

    Sin precision declarada (fixes anteriores a que se propagara el campo) devuelve 0:
    sin dato no se filtra nada.
    """
    worst = max(a.accuracy_m or 0.0, b.accuracy_m or 0.0)
    return min(worst * _NOISE_FLOOR_FACTOR, _MAX_NOISE_FLOOR_M)


def drop_imprecise_fixes(fixes: list[FixData]) -> tuple[list[FixData], int]:
    """Descarta los fixes cuya precision declarada supera MAX_ACCURACY_M.

    Sin precision declarada el fix se conserva (fixes anteriores a que se propagara el
    campo): sin dato no se filtra nada, mismo criterio que _noise_floor_m.

    Returns:
        (fixes_utiles, cantidad_descartada)
    """
    kept = [f for f in fixes if f.accuracy_m is None or f.accuracy_m <= MAX_ACCURACY_M]
    return kept, len(fixes) - len(kept)


def significant_legs(fixes: list[FixData]) -> list[tuple[FixData, FixData, float]]:
    """Trocea el track en tramos cuyo desplazamiento se distingue del ruido del GPS.

    Devuelve (origen, destino, distancia_m) por cada tramo aceptado. Acumula desde un
    ancla y cierra un tramo cuando la separacion respecto de ella supera el piso de
    ruido; entonces el destino pasa a ser la nueva ancla. Estar detenido deja de generar
    distancia fantasma, que es de donde viene el grueso de la inflacion.

    El umbral se mide contra el ancla y no entre fixes consecutivos porque llegan a 1 Hz:
    un caminante avanza ~1,4 m por fix, muy por debajo de la precision tipica (5-15 m), y
    descartar fix a fix borraria la caminata entera. Acumulando, caminar supera el piso en
    unos segundos y el tramo se cuenta completo.

    Si la quietud se prolonga mas de _STOP_WINDOW_S el ancla salta al fix actual sin
    acumular distancia: de lo contrario la pausa se sumaria al tiempo del tramo siguiente
    y lo haria parecer mucho mas lento de lo que fue.

    Contrapartida conocida: el tramo final que no alcanza a superar el piso no se cuenta,
    de modo que la distancia queda subestimada en menos de un piso (<30 m) por sesion.
    """
    legs: list[tuple[FixData, FixData, float]] = []
    if not fixes:
        return legs

    anchor = fixes[0]
    for fix in fixes[1:]:
        distance = haversine_m(anchor.lat, anchor.lon, fix.lat, fix.lon)
        if distance >= _noise_floor_m(anchor, fix):
            legs.append((anchor, fix, distance))
            anchor = fix
        elif (fix.captured_at - anchor.captured_at).total_seconds() > _STOP_WINDOW_S:
            anchor = fix
    return legs


def total_distance_m(fixes: list[FixData]) -> float:
    """Distancia recorrida en metros, ignorando el jitter del GPS."""
    return sum(distance for _, _, distance in significant_legs(fixes))


def average_speed_mps(fixes: list[FixData]) -> float:
    """Velocidad media en m/s sobre el track completo."""
    if len(fixes) < 2:
        return 0.0
    total_dist = total_distance_m(fixes)
    elapsed = (fixes[-1].captured_at - fixes[0].captured_at).total_seconds()
    return total_dist / elapsed if elapsed > 0 else 0.0


def _segment_speed_mps(a: FixData, b: FixData) -> float | None:
    """Velocidad implicada entre dos fixes (m/s). None si dt <= 0."""
    dt = (b.captured_at - a.captured_at).total_seconds()
    if dt <= 0:
        return None
    return haversine_m(a.lat, a.lon, b.lat, b.lon) / dt


def filter_gps_outliers(fixes: list[FixData], max_spike_mps: float) -> tuple[list[FixData], int]:
    """Elimina fixes outlier de un solo punto (spikes GPS que saltan y vuelven).

    Un fix se considera outlier si la velocidad implicada tanto al entrar
    (anterior->fix) como al salir (fix->siguiente) supera max_spike_mps: el patron
    tipico de un error GPS transitorio (multipath, canon urbano). Los extremos se
    conservan siempre.

    No debilita el anti-cheat: una teleportacion real es sostenida (salta y se
    queda en la nueva posicion), por lo que no presenta el patron de ida y vuelta
    y no se filtra; ademas seguira siendo detectada por el validador de velocidad.

    Args:
        fixes: Puntos GPS ordenados por tiempo.
        max_spike_mps: Velocidad maxima plausible del modo (m/s) por encima de la
            cual un segmento se considera fisicamente imposible.

    Returns:
        (fixes_limpios, cantidad_removida)
    """
    if len(fixes) < 3:
        return list(fixes), 0
    kept: list[FixData] = [fixes[0]]
    removed = 0
    for i in range(1, len(fixes) - 1):
        in_speed = _segment_speed_mps(kept[-1], fixes[i])
        out_speed = _segment_speed_mps(fixes[i], fixes[i + 1])
        if (
            in_speed is not None and out_speed is not None
            and in_speed > max_spike_mps and out_speed > max_spike_mps
        ):
            removed += 1
            continue
        kept.append(fixes[i])
    kept.append(fixes[-1])
    return kept, removed
