"""Pruebas del recorrido libre: clasificacion por velocidad, puntos y ciclo /finish."""

import math
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import respx
from httpx import Response

from app.core.config import settings
from app.models.session import RouteSession
from app.services.free_roam import classify, compute_free_roam_points, excess_kmh
from app.services.points import get_factor
from app.validators.common import FixData
from tests.conftest import mock_player_sensors

LSG_CORE = settings.lsg_core_base_url.rstrip("/")
LSG_AUTH = settings.lsg_auth_base_url.rstrip("/")


def _expected(**distance_m_by_mode: float) -> int:
    """Puntos de un recorrido libre segun la config vigente.

    Derivado, no escrito a mano: POINTS_PER_KM_* es ajustable por entorno y con el
    numero fijo bastaba tunearlo en el .env para que media suite fallara.
    """
    total = sum(
        (meters / 1000) * get_factor(mode) * settings.points_free_roam_multiplier
        for mode, meters in distance_m_by_mode.items()
    )
    return math.floor(total)

_T0 = datetime(2026, 6, 1, 10, 0, tzinfo=timezone.utc)
# Metros por grado de latitud con el mismo radio que usa haversine_m: moverse solo
# en latitud hace que la distancia del tramo sea exactamente R * dphi.
_M_PER_DEG = 6_371_000 * math.pi / 180


def _leg(fixes: list[FixData], mps: float, seconds: int, step_s: int = 10) -> list[FixData]:
    """Agrega un tramo a velocidad constante partiendo del ultimo fix."""
    lat = fixes[-1].lat if fixes else -33.45
    t = (fixes[-1].captured_at - _T0).total_seconds() if fixes else 0.0
    for _ in range(seconds // step_s):
        lat += (mps * step_s) / _M_PER_DEG
        t += step_s
        fixes.append(FixData(lat=lat, lon=-70.65, captured_at=_T0 + timedelta(seconds=t)))
    return fixes


def _start() -> list[FixData]:
    return [FixData(lat=-33.45, lon=-70.65, captured_at=_T0)]


@pytest.fixture(autouse=True)
def _bike_on(monkeypatch):
    """Casi todo este modulo describe la tabla completa de bandas, bicicleta incluida.

    Las pruebas de la bandera apagada vuelven a poner el valor en False; el
    monkeypatch posterior gana y ambos se deshacen al terminar.
    """
    monkeypatch.setattr(settings, "bike_activate", True)


# ---- classify ----

@pytest.mark.parametrize(
    "speed_kmh,expected_band",
    [
        pytest.param(1.4, None, id="detenido"),
        pytest.param(1.6, "walking", id="walking_floor"),
        pytest.param(6.9, "walking", id="walking_ceiling"),
        pytest.param(7.1, "jogging", id="jogging_floor"),
        pytest.param(14.9, "jogging", id="jogging_ceiling"),
        pytest.param(15.1, "cycling", id="cycling_floor"),
        pytest.param(34.9, "cycling", id="cycling_ceiling"),
        pytest.param(35.1, None, id="exceso"),
    ],
)
def test_classify_bands(speed_kmh, expected_band):
    assert classify(speed_kmh) == expected_band


# ---- bandera BIKE_ACTIVATE ----

def test_sin_bike_activate_la_bicicleta_no_es_una_actividad(monkeypatch):
    monkeypatch.setattr(settings, "bike_activate", False)

    assert classify(14.9) == "jogging"   # las bandas de abajo no cambian
    assert classify(15.1) is None        # lo que era bicicleta pasa a ser exceso
    assert classify(34.9) is None
    assert excess_kmh() == 15.0


def test_sin_bike_activate_los_tramos_en_bici_no_pagan(monkeypatch):
    """El mismo recorrido mixto del test de mas abajo, con la bandera apagada."""
    monkeypatch.setattr(settings, "bike_activate", False)
    fixes = _leg(_leg(_start(), mps=1.5, seconds=600), mps=6.0, seconds=600)

    points, breakdown = compute_free_roam_points(fixes)

    assert "cycling" not in breakdown["distance_by_mode"]
    assert breakdown["excess_distance_m"] == 3600.0
    assert breakdown["dominant_mode"] == "walking"
    assert points == _expected(walking=900.0)  # solo el tramo caminando


# ---- compute_free_roam_points ----

def test_walking_only_uses_half_the_walking_factor():
    # 1.5 m/s (5.4 km/h) durante 600 s = 900 m, a mitad del factor de caminata
    fixes = _leg(_start(), mps=1.5, seconds=600)
    points, breakdown = compute_free_roam_points(fixes)

    assert breakdown["dominant_mode"] == "walking"
    assert breakdown["distance_by_mode"]["walking"] == 900.0
    assert points == _expected(walking=900.0)


def test_mixed_session_splits_distance_by_activity():
    # 900 m caminando + 3600 m en bici, cada tramo con su propio factor
    fixes = _leg(_leg(_start(), mps=1.5, seconds=600), mps=6.0, seconds=600)
    points, breakdown = compute_free_roam_points(fixes)

    assert breakdown["distance_by_mode"]["walking"] == 900.0
    assert breakdown["distance_by_mode"]["cycling"] == 3600.0
    assert breakdown["dominant_mode"] == "cycling"
    assert points == _expected(walking=900.0, cycling=3600.0)


def test_overspeed_segments_score_nothing():
    # 900 m caminando + 900 m a 54 km/h: el tramo en exceso no suma puntos
    fixes = _leg(_leg(_start(), mps=1.5, seconds=600), mps=15.0, seconds=60)
    points, breakdown = compute_free_roam_points(fixes)

    assert breakdown["excess_distance_m"] == 900.0
    assert breakdown["excess_seconds"] == 60.0
    assert "cycling" not in breakdown["distance_by_mode"]
    assert points == _expected(walking=900.0)  # solo el tramo caminando


def test_jitter_estando_quieto_no_paga_como_bicicleta():
    """Quieto 5 min con el GPS oscilando +-3 m a 1 Hz (precision declarada 8 m).

    Sin piso de ruido cada oscilacion implica ~21 km/h y se cobraba como bicicleta.
    """
    fixes = [
        FixData(
            lat=-33.45 + (3.0 if t % 2 else -3.0) / _M_PER_DEG,
            lon=-70.65,
            captured_at=_T0 + timedelta(seconds=t),
            accuracy_m=8.0,
        )
        for t in range(300)
    ]
    points, breakdown = compute_free_roam_points(fixes)

    assert points == 0
    assert breakdown["distance_by_mode"] == {}
    assert breakdown["dominant_mode"] is None


def test_caminata_real_con_precision_declarada_sigue_puntuando():
    """La misma caminata del primer test, ahora con accuracy_m: se sigue pagando."""
    fixes = _leg(_start(), mps=1.5, seconds=600)
    for fix in fixes:
        fix.accuracy_m = 8.0
    points, breakdown = compute_free_roam_points(fixes)

    assert breakdown["dominant_mode"] == "walking"
    assert breakdown["distance_by_mode"]["walking"] >= 850.0
    # Sobre la distancia realmente medida: el piso de ruido recorta algunos metros.
    assert points == _expected(walking=breakdown["distance_by_mode"]["walking"])


def test_stopped_session_awards_nothing():
    fixes = [FixData(lat=-33.45, lon=-70.65, captured_at=_T0 + timedelta(seconds=i * 10))
             for i in range(10)]
    points, breakdown = compute_free_roam_points(fixes)

    assert points == 0
    assert breakdown["dominant_mode"] is None


# ---- endpoints ----

async def test_create_session_without_route_id(auth_client):
    """POST /sessions sin route_id crea un recorrido libre."""
    response = await auth_client.post("/lsg-routes/sessions", json={
        "client_session_uuid": str(uuid.uuid4()),
    })

    assert response.status_code == 201
    data = response.json()
    assert data["route_id"] is None
    assert data["status"] == "STARTED"


async def test_free_roam_track_has_no_planned_geometry(auth_client, db_session, auth_user):
    """GET /sessions/{id}/track de un recorrido libre no trae ruta planificada."""
    session = RouteSession(
        route_id=None,
        id_players=auth_user.id_players,
        status="VALIDATED",
        mode="walking",
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    response = await auth_client.get(f"/lsg-routes/sessions/{session.id}/track")

    assert response.status_code == 200
    assert response.json()["planned_geometry"] == []


async def test_finish_free_roam_awards_points_without_adherence(auth_client, db_session, auth_user):
    """/finish de un recorrido libre puntua por tramos y adopta el modo dominante."""
    session = RouteSession(
        route_id=None,
        id_players=auth_user.id_players,
        status="STARTED",
        mode="walking",  # placeholder: /finish debe reemplazarlo por el dominante
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    # Mayoria del recorrido en bicicleta -> el modo dominante debe quedar en cycling
    fixes = _leg(_leg(_start(), mps=1.5, seconds=600), mps=6.0, seconds=600)
    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": [
            {"client_seq": i, "lat": f.lat, "lon": f.lon,
             "is_from_mock_provider": False, "captured_at": f.captured_at.isoformat()}
            for i, f in enumerate(fixes)
        ],
    })

    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json={
            "access_token": "svc_tok", "token_type": "bearer",
            "expires_in_seconds": 7200, "expires_at": "2099-12-31T23:59:59+00:00",
            "player": {"id_players": 1},
        }))
        respx.get(f"{LSG_CORE}/sensors/players/{session.id_players}").mock(
            return_value=Response(200, json=mock_player_sensors())
        )
        respx.post(f"{LSG_CORE}/sensors/ingest/webhook").mock(
            return_value=Response(200, json={"status": "ok", "id_sensor_ingest_event": 90})
        )
        respx.post(f"{LSG_CORE}/players/{session.id_players}/points/adjust").mock(
            return_value=Response(200, json={"status": "ok", "source_ref": "ADJUST-free"})
        )
        respx.get(f"{LSG_CORE}/points/ledger").mock(return_value=Response(200, json=[]))

        response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "SYNCED_TO_LSG"
    assert data["awarded_points"] == _expected(walking=900.0, cycling=3600.0)
    assert data["adherence_ratio"] is None
    assert data["mode"] == "cycling"
    # La cadena reducida no corre velocity ni los validadores que exigen un modo fijo
    assert set(data["validators"]) == {"mock_location", "acceleration"}
