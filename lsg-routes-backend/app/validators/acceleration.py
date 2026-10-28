"""Validador 3: deteccion de teleportacion por aceleracion instantanea imposible."""

from app.validators.common import FixData, haversine_m

_MAX_ACCEL_MPS2 = 10.0  # Umbral: cambio de velocidad > 10 m/s² entre fixes consecutivos
_SPIKE_RATIO_THRESHOLD = 0.10  # mas del 10% de segmentos en spike -> rechazo


def validate(fixes: list[FixData]) -> dict:
    """Rechaza si una fraccion significativa de segmentos tiene aceleracion imposible.

    Calcula la aceleracion derivada del GPS entre fixes consecutivos. En vez de
    rechazar al primer spike (sensible a un unico fix GPS ruidoso), rechaza solo si
    mas del 10% de los segmentos superan 10 m/s². Una teleportacion real produce
    spikes sostenidos (ratio alto); el ruido GPS aislado queda por debajo del umbral.
    Nota: los outliers de un solo punto (salto y vuelta) ya se filtran aguas arriba
    (filter_gps_outliers) antes de llegar aqui.

    Args:
        fixes: Puntos GPS ordenados por tiempo.

    Returns:
        {"passed": bool, "details": dict}
    """
    if len(fixes) < 3:
        return {"passed": True, "details": {"reason": "insufficient_fixes"}}

    accels: list[float] = []
    prev_speed: float | None = None

    for i in range(1, len(fixes)):
        dist = haversine_m(fixes[i - 1].lat, fixes[i - 1].lon, fixes[i].lat, fixes[i].lon)
        dt = (fixes[i].captured_at - fixes[i - 1].captured_at).total_seconds()
        if dt <= 0:
            continue

        curr_speed = dist / dt
        if prev_speed is not None:
            accels.append(abs(curr_speed - prev_speed) / dt)
        prev_speed = curr_speed

    if not accels:
        return {"passed": True, "details": {"reason": "no_segments_with_positive_dt"}}

    spike_count = sum(1 for a in accels if a > _MAX_ACCEL_MPS2)
    spike_ratio = spike_count / len(accels)
    max_accel = max(accels)

    if spike_ratio > _SPIKE_RATIO_THRESHOLD:
        return {
            "passed": False,
            "details": {
                "reason": "instantaneous_acceleration",
                "spike_ratio": round(spike_ratio, 3),
                "spike_count": spike_count,
                "max_accel_mps2": round(max_accel, 2),
                "threshold_mps2": _MAX_ACCEL_MPS2,
            },
        }

    return {
        "passed": True,
        "details": {
            "spike_ratio": round(spike_ratio, 3),
            "max_accel_mps2": round(max_accel, 2),
        },
    }
