"""Puntos de recorrido libre: clasificacion por velocidad, tramo a tramo.

Un recorrido libre no tiene ruta de referencia, asi que no hay adherencia que
medir ni un modo de actividad declarado de antemano. En su lugar se clasifica
cada segmento GPS segun su velocidad y se acumula distancia por actividad; los
tramos detenidos o por sobre el techo de bicicleta no suman nada.

Los puntos rinden points_free_roam_multiplier del factor del modo: recorrer una
ruta planificada debe seguir siendo lo que mas conviene.
"""

from app.core.config import settings
from app.services.points import get_factor
from app.validators.common import FixData, significant_legs

# Techo de cada banda en km/h; el modo aplica desde el techo anterior hasta el propio.
# Por debajo de 1.5 se considera detenido y por sobre el techo mas alto, exceso: ambos suman 0.
STOPPED_KMH = 1.5
CYCLING_MAX_KMH = 35.0
SPEED_BANDS: list[tuple[float, str]] = [
    (7.0,  "walking"),
    (15.0, "jogging"),
    (CYCLING_MAX_KMH, "cycling"),
]


def active_bands() -> list[tuple[float, str]]:
    """Bandas vigentes. Sin bike_activate, la bicicleta no es una actividad."""
    if settings.bike_activate:
        return SPEED_BANDS
    return [band for band in SPEED_BANDS if band[1] != "cycling"]


def excess_kmh() -> float:
    """Velocidad desde la cual un tramo se descarta por exceso (techo de la ultima banda)."""
    return active_bands()[-1][0]


def classify(speed_kmh: float) -> str | None:
    """Modo de actividad implicado por una velocidad. None si esta detenido o en exceso."""
    if speed_kmh < STOPPED_KMH:
        return None
    for ceiling, mode in active_bands():
        if speed_kmh < ceiling:
            return mode
    return None


def compute_free_roam_points(fixes: list[FixData]) -> tuple[int, dict]:
    """Calcula los puntos de un recorrido libre clasificando segmento por segmento.

    Args:
        fixes: Puntos GPS ordenados por tiempo (ya filtrados de outliers).

    Returns:
        (awarded_points, breakdown_dict) con la distancia repartida por modo, el
        modo dominante (el de mayor distancia) y el tramo descartado por exceso.
    """
    distance_by_mode: dict[str, float] = {}
    excess_distance_m = 0.0
    excess_seconds = 0.0

    # Solo los tramos que se distinguen del ruido del GPS: un jitter de pocos metros a
    # 1 Hz implica ~20 km/h y se cobraria como bicicleta estando el jugador quieto.
    for prev, cur, dist in significant_legs(fixes):
        dt = (cur.captured_at - prev.captured_at).total_seconds()
        if dt <= 0:
            continue
        speed_kmh = (dist / dt) * 3.6
        mode = classify(speed_kmh)
        if mode is not None:
            distance_by_mode[mode] = distance_by_mode.get(mode, 0.0) + dist
        elif speed_kmh >= excess_kmh():
            excess_distance_m += dist
            excess_seconds += dt

    multiplier = settings.points_free_roam_multiplier
    awarded = int(sum(
        (meters / 1000.0) * get_factor(mode) * multiplier
        for mode, meters in distance_by_mode.items()
    ))
    dominant_mode = max(distance_by_mode, key=distance_by_mode.get, default=None)

    breakdown = {
        "tier": "FREE_ROAM",
        "distance_by_mode": {m: round(d, 1) for m, d in distance_by_mode.items()},
        "dominant_mode": dominant_mode,
        "excess_distance_m": round(excess_distance_m, 1),
        "excess_seconds": round(excess_seconds, 1),
        "multiplier": multiplier,
        "awarded_points": awarded,
    }
    return awarded, breakdown
