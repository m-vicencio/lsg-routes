"""Pruebas unitarias del calculador de puntos.

Los esperados se derivan de `get_factor(mode)`, nunca de un numero fijo: el factor
por km es configurable por entorno (POINTS_PER_KM_*) y escribirlo a mano haria que
la suite entera fallara en cuanto alguien lo ajustara en su .env.
"""

import math

import pytest

from app.services.points import compute_points, get_factor

WALKING = get_factor("walking")


@pytest.mark.parametrize(
    "mode,distance_m,adherence,expected_tier,expected_points",
    [
        pytest.param(
            "walking", 5000.0, 0.90, "FULL",
            math.floor(5 * 0.90 * WALKING), id="full_adherence_awards_full_points",
        ),
        pytest.param(
            "walking", 5000.0, 0.50, "PARTIAL",
            math.floor(2.5 * WALKING * (0.50 / 0.70)), id="partial_adherence_awards_proportional_points",
        ),
        pytest.param("walking", 5000.0, 0.20, "REJECTED", 0, id="low_adherence_awards_zero_points"),
        pytest.param("jogging", 3000.0, 0.0, "REJECTED", 0, id="zero_adherence_is_rejected"),
        pytest.param(
            "walking", 1000.0, 0.70, "FULL",
            math.floor(1 * 0.70 * WALKING), id="full_adherence_boundary_exact_0_70",
        ),
        pytest.param("walking", 1000.0, 0.30, "PARTIAL", None, id="partial_adherence_boundary_exact_0_30"),
        pytest.param("walking", 1000.0, 0.299, "REJECTED", 0, id="just_below_partial_threshold_is_rejected"),
    ],
)
def test_compute_points_adherence_boundaries(mode, distance_m, adherence, expected_tier, expected_points):
    points, breakdown = compute_points(mode, distance_m, adherence)

    assert breakdown["tier"] == expected_tier
    if expected_points is not None:
        assert points == expected_points

    # Forma del breakdown: cualquier caso sirve para proteger las claves esperadas.
    assert "tier" in breakdown
    assert "mode" in breakdown
    assert "effective_distance_km" in breakdown
    assert "factor_per_km" in breakdown
    assert "adherence_ratio" in breakdown
    assert "awarded_points" in breakdown
