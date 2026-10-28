"""Validador 2: plausibilidad de velocidad segun modo de actividad."""

from app.validators.common import FixData, haversine_m

# Limites de velocidad por modo (km/h)
# max_sustained es un promedio de sesion; max_spike es el techo instantaneo de un
# segmento. jogging: 28 de pico cubre un sprint de una persona en forma (25-30);
# cycling: 35 sostenido cubre al ciclista de club (25-32) sin dejar pasar un viaje
# urbano en vehiculo, y 70 de pico sigue admitiendo una bajada real.
SPEED_BOUNDS: dict[str, dict[str, float]] = {
    "walking":  {"max_sustained": 8.0,  "max_spike": 12.0},
    "jogging":  {"max_sustained": 16.0, "max_spike": 28.0},
    "hiking":   {"max_sustained": 8.0,  "max_spike": 14.0},
    "cycling":  {"max_sustained": 35.0, "max_spike": 70.0},
}

_SPIKE_RATIO_THRESHOLD = 0.10  # mas del 10% de segmentos en spike -> rechazo
_MIN_FIXES = 2


def validate(fixes: list[FixData], mode: str) -> dict:
    """Rechaza si la velocidad supera los limites plausibles del modo.

    Criterios:
      - > 10% de segmentos superan max_spike   -> REJECT
      - Velocidad media supera max_sustained   -> REJECT

    Args:
        fixes: Puntos GPS ordenados por tiempo.
        mode: Modo de actividad (walking, jogging, hiking, cycling).

    Returns:
        {"passed": bool, "details": dict}
    """
    if len(fixes) < _MIN_FIXES:
        return {"passed": True, "details": {"reason": "insufficient_fixes"}}

    bounds = SPEED_BOUNDS.get(mode, SPEED_BOUNDS["walking"])
    max_spike_mps = bounds["max_spike"] / 3.6
    max_sustained_mps = bounds["max_sustained"] / 3.6

    speeds: list[float] = []
    for i in range(1, len(fixes)):
        dist = haversine_m(fixes[i - 1].lat, fixes[i - 1].lon, fixes[i].lat, fixes[i].lon)
        dt = (fixes[i].captured_at - fixes[i - 1].captured_at).total_seconds()
        if dt > 0:
            speeds.append(dist / dt)

    if not speeds:
        return {"passed": True, "details": {"reason": "no_segments_with_positive_dt"}}

    spike_count = sum(1 for s in speeds if s > max_spike_mps)
    spike_ratio = spike_count / len(speeds)
    avg_speed_mps = sum(speeds) / len(speeds)

    if spike_ratio > _SPIKE_RATIO_THRESHOLD:
        return {
            "passed": False,
            "details": {
                "reason": "velocity_spike",
                "spike_ratio": round(spike_ratio, 3),
                "spike_count": spike_count,
                "max_spike_kmh": bounds["max_spike"],
            },
        }
    if avg_speed_mps > max_sustained_mps:
        return {
            "passed": False,
            "details": {
                "reason": "velocity_sustained",
                "avg_speed_kmh": round(avg_speed_mps * 3.6, 1),
                "max_sustained_kmh": bounds["max_sustained"],
            },
        }

    return {
        "passed": True,
        "details": {
            "avg_speed_kmh": round(avg_speed_mps * 3.6, 1),
            "spike_ratio": round(spike_ratio, 3),
        },
    }
