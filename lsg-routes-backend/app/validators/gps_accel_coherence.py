"""Validador 4: coherencia entre movimiento GPS y varianza del acelerometro."""

from app.validators.common import AccelData, FixData, average_speed_mps

_MOTION_THRESHOLD_MPS = 0.5   # velocidad GPS por encima de la cual se considera "en movimiento"
_VARIANCE_NEAR_ZERO = 0.01    # varianza del acelerometro considerada "estatico"
_APPLICABLE_MODES = frozenset({"walking", "jogging", "hiking"})


def validate(fixes: list[FixData], accel_samples: list[AccelData], mode: str) -> dict:
    """Rechaza si GPS reporta movimiento pero el acelerometro indica dispositivo estatico.

    Solo aplica para modos pedestres (walking, jogging, hiking).

    Args:
        fixes: Puntos GPS de la sesion.
        accel_samples: Muestras de acelerometro de la sesion.
        mode: Modo de actividad.

    Returns:
        {"passed": bool, "details": dict}
    """
    if mode not in _APPLICABLE_MODES:
        return {"passed": True, "details": {"reason": "not_applicable_for_mode"}}

    if not accel_samples:
        return {"passed": True, "details": {"reason": "no_accel_data"}}

    if len(fixes) < 2:
        return {"passed": True, "details": {"reason": "insufficient_fixes"}}

    gps_speed = average_speed_mps(fixes)
    if gps_speed < _MOTION_THRESHOLD_MPS:
        return {"passed": True, "details": {"reason": "device_not_moving"}}

    avg_variance = sum(s.magnitude_variance for s in accel_samples) / len(accel_samples)

    if avg_variance < _VARIANCE_NEAR_ZERO:
        return {
            "passed": False,
            "details": {
                "reason": "gps_motion_but_static_device",
                "avg_gps_speed_mps": round(gps_speed, 2),
                "avg_accel_variance": round(avg_variance, 5),
                "variance_threshold": _VARIANCE_NEAR_ZERO,
            },
        }

    return {
        "passed": True,
        "details": {
            "avg_gps_speed_mps": round(gps_speed, 2),
            "avg_accel_variance": round(avg_variance, 4),
        },
    }
