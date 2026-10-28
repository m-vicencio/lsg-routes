"""Piso de ruido de total_distance_m: el jitter del GPS no debe generar distancia."""

from datetime import datetime, timedelta, timezone

from app.validators.common import FixData, drop_imprecise_fixes, total_distance_m

_T0 = datetime(2026, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
_LAT0 = -33.45
_LON0 = -70.65
_M_PER_DEG_LAT = 111_320.0


def _fix(north_m: float, t: int, accuracy_m: float | None = 10.0) -> FixData:
    """Fix a north_m metros al norte del origen, capturado en el segundo t."""
    return FixData(
        lat=_LAT0 + north_m / _M_PER_DEG_LAT,
        lon=_LON0,
        captured_at=_T0 + timedelta(seconds=t),
        accuracy_m=accuracy_m,
    )


def test_detenido_con_jitter_no_suma_distancia():
    """60 s parado con el GPS oscilando +-8 m dentro de su propia precision (10 m)."""
    fixes = [_fix(8.0 if t % 2 else -8.0, t) for t in range(60)]

    assert total_distance_m(fixes) == 0.0


def test_caminata_real_se_cuenta_completa():
    """Caminar a 1,4 m/s durante 10 min: 840 m reales, aunque cada paso de 1 Hz (1,4 m)
    quede muy por debajo del piso de ruido. Se admite perder el tramo final que no
    alcanza a superar el piso (<20 m con accuracy de 10 m)."""
    fixes = [_fix(1.4 * t, t) for t in range(601)]

    distance = total_distance_m(fixes)

    assert 820.0 <= distance <= 840.0


def test_sin_precision_declarada_no_filtra():
    """Fixes antiguos sin accuracy_m conservan el calculo crudo."""
    fixes = [_fix(8.0 if t % 2 else -8.0, t, accuracy_m=None) for t in range(60)]

    # 59 segmentos de 16 m entre posiciones alternadas
    assert total_distance_m(fixes) > 900.0


def test_precision_pesima_no_se_traga_un_tramo_real():
    """Con accuracy absurda (200 m) el techo de 30 m evita perder 100 m reales.

    Es la contracara del techo, y la razon de drop_imprecise_fixes: el piso no puede
    defenderse de esos fixes, asi que se descartan antes de llegar aqui.
    """
    fixes = [_fix(0.0, 0, accuracy_m=200.0), _fix(100.0, 60, accuracy_m=200.0)]

    assert total_distance_m(fixes) > 99.0


def test_descarta_los_fixes_sin_precision_utilizable():
    """Sobre el tope no sirven ni para medir; los que no declaran precision se conservan."""
    fixes = [_fix(0.0, 0, accuracy_m=10.0), _fix(100.0, 1, accuracy_m=200.0),
             _fix(0.0, 2, accuracy_m=None)]

    kept, dropped = drop_imprecise_fixes(fixes)

    assert dropped == 1
    assert [f.accuracy_m for f in kept] == [10.0, None]
