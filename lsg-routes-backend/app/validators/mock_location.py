"""Validador 1: deteccion de ubicaciones simuladas (mock provider)."""

from app.validators.common import FixData


def validate(fixes: list[FixData]) -> dict:
    """Rechaza si algun GPS fix fue generado por un proveedor simulado.

    Args:
        fixes: Lista de puntos GPS de la sesion.

    Returns:
        {"passed": bool, "details": dict}
    """
    mock_count = sum(1 for f in fixes if f.is_from_mock_provider)
    if mock_count > 0:
        return {
            "passed": False,
            "details": {"reason": "mock_location_detected", "mock_fix_count": mock_count},
        }
    return {"passed": True, "details": {}}
