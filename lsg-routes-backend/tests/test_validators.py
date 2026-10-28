"""Pruebas unitarias de la cadena anti-cheat y el calculo de adherencia."""

from datetime import datetime, timedelta, timezone

import pytest

from app.validators.common import AccelData, FixData, StepData
from app.validators import (
    mock_location,
    velocity,
    acceleration,
    gps_accel_coherence,
    step_correlation,
)
from app.validators.adherence import compute_adherence, compute_planned_distance_m


def _ts(offset_s: float = 0) -> datetime:
    return datetime(2026, 6, 1, 12, 0, 0, tzinfo=timezone.utc) + timedelta(seconds=offset_s)


def _fix(lat: float, lon: float, t: float = 0, mock: bool = False, speed: float | None = None) -> FixData:
    return FixData(lat=lat, lon=lon, captured_at=_ts(t), speed_mps=speed, is_from_mock_provider=mock)


def _walking_fixes(step_deg: float, n: int = 10) -> list[FixData]:
    return [_fix(-33.4 + i * step_deg, -70.6, t=float(i)) for i in range(n)]


# ---- mock_location ----

@pytest.mark.parametrize(
    "fixes,expected_passed,expected_mock_count",
    [
        pytest.param([_fix(-33.4, -70.6, t=i) for i in range(5)], True, None, id="passes_clean_track"),
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.41, -70.61, t=1, mock=True)], False, 1,
            id="rejects_one_mock_fix",
        ),
        pytest.param(
            [_fix(-33.4, -70.6, t=i, mock=True) for i in range(3)], False, None,
            id="rejects_all_mock_fixes",
        ),
    ],
)
def test_mock_location(fixes, expected_passed, expected_mock_count):
    result = mock_location.validate(fixes)
    assert result["passed"] is expected_passed
    if expected_mock_count is not None:
        assert result["details"]["mock_fix_count"] == expected_mock_count


# ---- velocity ----

@pytest.mark.parametrize(
    "fixes,mode,expected_passed,expected_reasons",
    [
        # ~1 m/s (3.6 km/h) entre fixes cada segundo
        pytest.param(_walking_fixes(0.000009), "walking", True, None, id="passes_normal_walking_speed"),
        # Velocidad enorme: 100m en 1 segundo = 100 m/s = 360 km/h
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.401, -70.6, t=1)], "walking", False,
            ("velocity_spike", "velocity_sustained"), id="rejects_walking_above_spike_threshold",
        ),
        # Velocidad promedio ~3.3 m/s = 12 km/h, max walking=8 km/h
        pytest.param(_walking_fixes(0.00003), "walking", False, None, id="rejects_sustained_overspeed"),
        # ~7 m/s = 25 km/h, dentro del limite sostenido de cycling (35 km/h)
        pytest.param(_walking_fixes(0.0000629), "cycling", True, None, id="passes_cycling_speed"),
        # ~11 m/s = 40 km/h sostenidos: por sobre el techo de cycling (35 km/h)
        pytest.param(
            _walking_fixes(0.0001), "cycling", False, ("velocity_sustained",),
            id="rejects_cycling_at_vehicle_speed",
        ),
        pytest.param([_fix(-33.4, -70.6)], "walking", True, None, id="insufficient_fixes_always_passes"),
    ],
)
def test_velocity(fixes, mode, expected_passed, expected_reasons):
    result = velocity.validate(fixes, mode)
    assert result["passed"] is expected_passed
    if expected_reasons is not None:
        assert result["details"]["reason"] in expected_reasons


# ---- acceleration ----

def test_acceleration_passes_smooth_track():
    fixes = [_fix(-33.4 + i * 0.000009, -70.6, t=float(i)) for i in range(5)]
    result = acceleration.validate(fixes)
    assert result["passed"] is True


def test_acceleration_rejects_teleport():
    # Caminata normal -> teleportacion: cambio de velocidad > 10 m/s²
    fixes = [
        _fix(-33.4000, -70.6, t=0),
        _fix(-33.40001, -70.6, t=1),   # ~11 m en 1s -> 11 m/s (trote normal)
        _fix(-33.4020, -70.6, t=2),    # ~221 m en 1s -> 221 m/s (teleport)
    ]
    result = acceleration.validate(fixes)
    assert result["passed"] is False


def test_acceleration_insufficient_fixes_passes():
    result = acceleration.validate([_fix(-33.4, -70.6)])
    assert result["passed"] is True


def test_acceleration_tolerates_isolated_spike():
    """Un unico outlier en un track largo no rechaza (un outlier genera 2 spikes
    de aceleracion -subida y bajada-; 2/28 ~ 7% < 10%)."""
    fixes = [_fix(-33.4 + i * 0.000009, -70.6, t=float(i)) for i in range(30)]
    # Un solo fix desplazado en el medio (salta y vuelve)
    fixes[15] = _fix(-33.4 + 15 * 0.000009 + 0.0003, -70.6, t=15.0)
    result = acceleration.validate(fixes)
    assert result["passed"] is True
    assert result["details"]["spike_ratio"] <= 0.10


# ---- filter_gps_outliers ----

def test_filter_removes_single_outlier():
    """Un fix que salta lejos y vuelve (in y out > max_spike) se elimina."""
    from app.validators.common import filter_gps_outliers

    # max_spike walking = 12 km/h = 3.33 m/s
    max_spike_mps = 12.0 / 3.6
    fixes = [_fix(-33.4 + i * 0.000009, -70.6, t=float(i)) for i in range(5)]
    # Reemplazar el fix central por un outlier muy alejado (salta y vuelve)
    fixes[2] = _fix(-33.4 + 0.002, -70.6, t=2.0)  # ~222 m de salto
    cleaned, removed = filter_gps_outliers(fixes, max_spike_mps)
    assert removed == 1
    assert len(cleaned) == 4
    assert fixes[2] not in cleaned


def test_filter_keeps_clean_track():
    """Un track caminando normal no pierde fixes."""
    from app.validators.common import filter_gps_outliers

    fixes = [_fix(-33.4 + i * 0.000009, -70.6, t=float(i)) for i in range(6)]
    cleaned, removed = filter_gps_outliers(fixes, 12.0 / 3.6)
    assert removed == 0
    assert len(cleaned) == 6


def test_filter_keeps_endpoints_and_short_tracks():
    """Tracks de <3 fixes se devuelven intactos (no hay punto interior que evaluar)."""
    from app.validators.common import filter_gps_outliers

    fixes = [_fix(-33.4, -70.6, t=0), _fix(-33.5, -70.6, t=1)]
    cleaned, removed = filter_gps_outliers(fixes, 12.0 / 3.6)
    assert removed == 0
    assert cleaned == fixes


def test_filter_preserves_sustained_teleport():
    """Una teleportacion sostenida (salta y se queda) NO se filtra: in alto, out normal."""
    from app.validators.common import filter_gps_outliers

    # Camina, salta muy lejos en t=2 y SIGUE desde alli (no vuelve)
    fixes = [
        _fix(-33.4000, -70.6, t=0),
        _fix(-33.4000 + 0.000009, -70.6, t=1),
        _fix(-33.4200, -70.6, t=2),               # salto grande (entrada rapida)
        _fix(-33.4200 + 0.000009, -70.6, t=3),    # continua desde la nueva posicion (salida normal)
    ]
    cleaned, removed = filter_gps_outliers(fixes, 12.0 / 3.6)
    assert removed == 0  # el patron de ida-y-vuelta no se cumple -> lo detecta velocidad


# ---- gps_accel_coherence ----

@pytest.mark.parametrize(
    "fixes,accel,mode,expected_passed,expected_reason",
    [
        pytest.param(
            [_fix(-33.4, -70.6), _fix(-33.41, -70.61, t=10)], [AccelData(0.5, 0.05, _ts(), 1)], "cycling",
            True, "not_applicable_for_mode", id="passes_for_cycling",
        ),
        pytest.param(
            # ~100m en 10s -> 10 m/s, varianza alta -> dispositivo en movimiento
            [_fix(-33.4, -70.6, t=0), _fix(-33.4009, -70.6, t=10)], [AccelData(1.0, 0.5, _ts(), 1)], "walking",
            True, None, id="passes_with_high_variance",
        ),
        pytest.param(
            # GPS indica movimiento rapido, acelerometro varianza casi cero -> estatico
            [_fix(-33.4, -70.6, t=0), _fix(-33.4009, -70.6, t=10)], [AccelData(9.8, 0.0001, _ts(), 1)], "walking",
            False, "gps_motion_but_static_device", id="rejects_gps_motion_but_static_device",
        ),
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.401, -70.6, t=5)], [], "jogging",
            True, None, id="passes_no_accel_data",
        ),
    ],
)
def test_gps_accel_coherence(fixes, accel, mode, expected_passed, expected_reason):
    result = gps_accel_coherence.validate(fixes, accel, mode)
    assert result["passed"] is expected_passed
    if expected_reason is not None:
        assert result["details"]["reason"] == expected_reason


# ---- step_correlation ----

@pytest.mark.parametrize(
    "fixes,steps,mode,expected_passed",
    [
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.41, -70.6, t=60)], [StepData(50, _ts())], "cycling",
            True, id="passes_for_cycling",
        ),
        # ~100 m de track, zancada de caminata 0.7 m -> ~143 pasos esperados; 100/143 = 0.70 >= 0.50
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.4009, -70.6, t=60)], [StepData(100, _ts())], "walking",
            True, id="passes_with_enough_steps",
        ),
        # ~100m, ~143 pasos esperados, pero solo 20 -> ratio 0.14 < 0.50
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.4009, -70.6, t=60)], [StepData(20, _ts())], "walking",
            False, id="rejects_too_few_steps",
        ),
        pytest.param(
            [_fix(-33.4, -70.6, t=0), _fix(-33.41, -70.6, t=60)], [], "jogging",
            True, id="passes_no_step_data",
        ),
    ],
)
def test_step_correlation(fixes, steps, mode, expected_passed):
    result = step_correlation.validate(fixes, steps, mode)
    assert result["passed"] is expected_passed


# ---- adherence ----

def test_adherence_full_coverage():
    """Track idéntico a la ruta -> adherencia ~1.0."""
    geometry = [[-33.4000, -70.6000], [-33.4050, -70.5950], [-33.4100, -70.5900]]
    fixes = [_fix(p[0], p[1], t=float(i * 30)) for i, p in enumerate(geometry)]
    result = compute_adherence(geometry, fixes, corridor_m=50.0)
    assert result["adherence_ratio"] > 0.80


def test_adherence_no_overlap():
    """Track completamente fuera de la ruta planificada -> adherencia ~0."""
    geometry = [[-33.4000, -70.6000], [-33.4100, -70.5900]]
    # Fixes a ~5km de distancia
    fixes = [_fix(-33.50, -70.70, t=0), _fix(-33.51, -70.71, t=60)]
    result = compute_adherence(geometry, fixes, corridor_m=50.0)
    assert result["adherence_ratio"] < 0.05


def test_adherence_returns_zero_on_insufficient_data():
    geometry = [[-33.4, -70.6]]  # Solo un punto
    fixes = [_fix(-33.4, -70.6)]
    result = compute_adherence(geometry, fixes)
    assert result["adherence_ratio"] == 0.0


def test_compute_planned_distance_m():
    geometry = [[-33.4000, -70.6000], [-33.4100, -70.6000]]  # ~1.1 km norte-sur
    distance = compute_planned_distance_m(geometry)
    assert 1000 < distance < 1200
