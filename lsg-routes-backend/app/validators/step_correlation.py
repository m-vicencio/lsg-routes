"""Validador 5: correlacion entre pasos contados y distancia GPS recorrida."""

from app.validators.common import FixData, StepData, total_distance_m

# Longitud de paso promedio por modo (metros por paso)
_STEP_LENGTH_M: dict[str, float] = {
    "walking": 0.70,
    "jogging": 1.00,
    "hiking":  0.65,
}
_APPLICABLE_MODES = frozenset(_STEP_LENGTH_M.keys())
_STEP_RATIO_THRESHOLD = 0.50   # minimo 50% de los pasos esperados
_MIN_DISTANCE_M = 50.0         # no validar en tracks muy cortos


def validate(fixes: list[FixData], step_counts: list[StepData], mode: str) -> dict:
    """Rechaza si el conteo de pasos es < 50% del esperado para la distancia GPS.

    Solo aplica para modos pedestres. Requiere datos de step counter.

    Args:
        fixes: Puntos GPS de la sesion.
        step_counts: Deltas de pasos capturados.
        mode: Modo de actividad.

    Returns:
        {"passed": bool, "details": dict}
    """
    if mode not in _APPLICABLE_MODES:
        return {"passed": True, "details": {"reason": "not_applicable_for_mode"}}

    if not step_counts:
        return {"passed": True, "details": {"reason": "no_step_data"}}

    if len(fixes) < 2:
        return {"passed": True, "details": {"reason": "insufficient_fixes"}}

    distance = total_distance_m(fixes)
    if distance < _MIN_DISTANCE_M:
        return {"passed": True, "details": {"reason": "track_too_short"}}

    total_steps = sum(s.steps_delta for s in step_counts)
    step_length = _STEP_LENGTH_M[mode]
    expected_steps = distance / step_length
    ratio = total_steps / expected_steps if expected_steps > 0 else 0.0

    if ratio < _STEP_RATIO_THRESHOLD:
        return {
            "passed": False,
            "details": {
                "reason": "insufficient_steps",
                "total_steps": total_steps,
                "expected_steps": round(expected_steps),
                "step_ratio": round(ratio, 3),
                "threshold": _STEP_RATIO_THRESHOLD,
            },
        }

    return {
        "passed": True,
        "details": {
            "total_steps": total_steps,
            "expected_steps": round(expected_steps),
            "step_ratio": round(ratio, 3),
        },
    }
