"""Calculador de puntos LSG para sesiones de ruta completadas."""

from app.core.config import settings

_ADHERENCE_FULL = 0.70
_ADHERENCE_PARTIAL_MIN = 0.30


def get_factor(mode: str) -> int:
    """Retorna el factor de puntos por km para el modo dado."""
    return {
        "walking": settings.points_per_km_walking,
        "cycling": settings.points_per_km_cycling,
        "jogging": settings.points_per_km_jogging,
        "hiking":  settings.points_per_km_hiking,
    }.get(mode, settings.points_per_km_walking)


def compute_points(
    mode: str,
    planned_distance_m: float,
    adherence_ratio: float,
    is_freehand: bool = False,
) -> tuple[int, dict]:
    """Calcula los puntos a acreditar segun adherencia y modo.

    Rutas FREEHAND usan umbrales mas laxos (ver settings.adherence_*_freehand).

    Args:
        mode: Modo de actividad (walking, cycling, jogging, hiking).
        planned_distance_m: Distancia de la ruta planificada en metros.
        adherence_ratio: Fraccion de la ruta cubierta [0, 1].
        is_freehand: True si la ruta fue trazada a mano libre.

    Returns:
        (awarded_points, breakdown_dict)
    """
    adherence_full = settings.adherence_full_freehand if is_freehand else _ADHERENCE_FULL
    adherence_partial_min = settings.adherence_partial_min_freehand if is_freehand else _ADHERENCE_PARTIAL_MIN

    effective_km = (planned_distance_m * adherence_ratio) / 1000.0
    factor = get_factor(mode)

    if adherence_ratio < adherence_partial_min:
        awarded = 0
        tier = "REJECTED"
    elif adherence_ratio >= adherence_full:
        awarded = int(effective_km * factor)
        tier = "FULL"
    else:
        proportional = adherence_ratio / adherence_full
        awarded = int(effective_km * factor * proportional)
        tier = "PARTIAL"

    breakdown = {
        "tier": tier,
        "mode": mode,
        "effective_distance_km": round(effective_km, 3),
        "factor_per_km": factor,
        "adherence_ratio": round(adherence_ratio, 4),
        "awarded_points": awarded,
        "is_freehand": is_freehand,
    }
    return awarded, breakdown
