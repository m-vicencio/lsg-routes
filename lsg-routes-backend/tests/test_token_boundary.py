"""Frontera de tokens LSG y endpoints por modo de actividad.

Dos invariantes que este proyecto no puede perder:

  1. El ingest (y las lecturas) van a nombre del PARTICIPANTE; solo el adjust usa
     la cuenta de servicio, porque exige rol admin, researcher o developer. Si alguien vuelve a
     mandarlo todo con la cuenta de servicio, el estudio longitudinal deja de
     registrar quien hizo cada ruta.
  2. Cada modo de actividad viaja por su propio id_sensor_endpoint: es el unico
     campo que el mod de BG3 puede leer para clasificar la actividad.
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
import respx
from httpx import Response
from sqlalchemy import select

from app.core.config import settings
from app.models.route import PlannedRoute
from app.models.session import LSGSyncRecord, RouteSession
from app.models.user import User
from app.services.lsg_sync import sync_to_lsg
from tests.conftest import USER_TOKEN

LSG_CORE = settings.lsg_core_base_url.rstrip("/")
LSG_AUTH = settings.lsg_auth_base_url.rstrip("/")

SERVICE_TOKEN = "service-token-xyz"
_GEOMETRY = [[-33.4500, -70.6500], [-33.4540, -70.6460]]

# modo -> (id_sensor_endpoint, id_players_sensor_endpoint)
_ENDPOINTS = {"walking": (101, 11), "jogging": (102, 22), "cycling": (103, 33)}


@pytest.fixture
def endpoints_per_mode(monkeypatch):
    """Configura un endpoint distinto por modo, como en produccion."""
    monkeypatch.setattr(
        settings,
        "lsg_routes_endpoint_ids",
        {mode: eid for mode, (eid, _) in _ENDPOINTS.items()},
    )
    return _ENDPOINTS


@pytest.fixture
def service_account(monkeypatch):
    """Cuenta de servicio configurada, con su cache limpio."""
    from app.clients import lsg_service_account

    monkeypatch.setattr(settings, "lsg_service_email", "svc@lsg.cl")
    monkeypatch.setattr(settings, "lsg_service_password", "secret")
    lsg_service_account.invalidate_service_token()
    yield
    lsg_service_account.invalidate_service_token()


async def _make_session(db_session, auth_user, mode: str) -> RouteSession:
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name=f"Ruta {mode}",
        mode=mode,
        geometry=_GEOMETRY,
        planned_distance_m=2000.0,
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)

    session = RouteSession(
        route_id=route.id,
        id_players=auth_user.id_players,
        status="VALIDATED",
        mode=mode,
        client_session_uuid=uuid.uuid4(),
        ended_at=datetime.now(tz=timezone.utc),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)
    return session


def _mock_lsg(mode: str, capture: dict) -> None:
    """Mocks de LSG-Auth + LSG-Core que anotan el Bearer de cada llamada."""
    endpoint_id, pseid = _ENDPOINTS[mode]

    respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json={
        "access_token": SERVICE_TOKEN,
        "token_type": "bearer",
        "expires_in_seconds": 7200,
        "expires_at": (datetime.now(tz=timezone.utc) + timedelta(hours=2)).isoformat(),
        "player": {"id_players": 1, "roles": ["researcher"]},
    }))

    def on_sensors(request):
        capture["sensors_auth"] = request.headers["Authorization"]
        return Response(200, json=[
            {"id_players_sensor_endpoint": pseid,
             "id_sensor_endpoint": endpoint_id,
             "activated": True}
        ])

    def on_ingest(request):
        import json
        capture["ingest_auth"] = request.headers["Authorization"]
        capture["ingest_body"] = json.loads(request.content)
        return Response(200, json={"status": "ok", "id_sensor_ingest_event": 55})

    def on_adjust(request):
        capture["adjust_auth"] = request.headers["Authorization"]
        return Response(200, json={"status": "ok", "source_ref": "ADJUST-test"})

    def on_ledger(request):
        capture["ledger_auth"] = request.headers["Authorization"]
        return Response(200, json=[])

    respx.get(url__startswith=f"{LSG_CORE}/sensors/players/").mock(side_effect=on_sensors)
    respx.post(f"{LSG_CORE}/sensors/ingest/webhook").mock(side_effect=on_ingest)
    respx.post(url__regex=rf"{LSG_CORE}/players/\d+/points/adjust").mock(side_effect=on_adjust)
    respx.get(f"{LSG_CORE}/points/ledger").mock(side_effect=on_ledger)


# ---- Invariante 1: quien firma cada llamada ----

async def test_ingest_uses_user_token_adjust_uses_service_token(
    db_session, auth_user, endpoints_per_mode, service_account
):
    """El webhook va a nombre del participante; solo el adjust usa la cuenta de servicio."""
    session = await _make_session(db_session, auth_user, "jogging")
    capture: dict = {}

    with respx.mock:
        _mock_lsg("jogging", capture)
        record = await sync_to_lsg(
            session=session, awarded_points=24, adherence_ratio=0.95,
            validators_summary={}, breakdown={}, planned_distance_m=2000.0,
            db=db_session, user_token=USER_TOKEN,
        )

    assert record.sync_status == "SYNCED_TO_LSG"
    assert capture["ingest_auth"] == f"Bearer {USER_TOKEN}"
    assert capture["sensors_auth"] == f"Bearer {USER_TOKEN}"
    assert capture["adjust_auth"] == f"Bearer {SERVICE_TOKEN}"
    # Quien verifica la idempotencia debe ser quien acredita.
    assert capture["ledger_auth"] == capture["adjust_auth"]


async def test_adjust_falls_back_to_user_token_without_service_account(
    db_session, auth_user, endpoints_per_mode, monkeypatch
):
    """Sin cuenta de servicio configurada, el adjust cae al token del usuario."""
    monkeypatch.setattr(settings, "lsg_service_email", "")
    monkeypatch.setattr(settings, "lsg_service_password", "")
    session = await _make_session(db_session, auth_user, "walking")
    capture: dict = {}

    with respx.mock:
        _mock_lsg("walking", capture)
        record = await sync_to_lsg(
            session=session, awarded_points=16, adherence_ratio=0.9,
            validators_summary={}, breakdown={}, planned_distance_m=2000.0,
            db=db_session, user_token=USER_TOKEN,
        )

    assert record.sync_status == "SYNCED_TO_LSG"
    assert capture["adjust_auth"] == f"Bearer {USER_TOKEN}"


# ---- Invariante 2: un endpoint por modo ----

def test_endpoint_id_for_resolves_each_mode(endpoints_per_mode):
    """Cada modo sale por su propio endpoint."""
    for mode, (endpoint_id, _) in _ENDPOINTS.items():
        assert settings.endpoint_id_for(mode) == endpoint_id


def test_endpoint_id_for_unknown_mode_falls_back(endpoints_per_mode, monkeypatch):
    """Un modo sin entrada en el mapa usa el endpoint por defecto."""
    monkeypatch.setattr(settings, "lsg_routes_sensor_endpoint_id", 999)
    assert settings.endpoint_id_for("hiking") == 999


def test_configured_endpoint_ids_are_unique_and_sorted(endpoints_per_mode):
    """ensure_sensor_linked activa cada endpoint una sola vez."""
    assert settings.configured_endpoint_ids == [101, 102, 103]


async def test_ingest_uses_the_endpoint_of_the_session_mode(
    db_session, auth_user, endpoints_per_mode, service_account
):
    """El evento llega etiquetado con el endpoint del modo: es lo que lee el mod."""
    session = await _make_session(db_session, auth_user, "cycling")
    capture: dict = {}

    with respx.mock:
        _mock_lsg("cycling", capture)
        await sync_to_lsg(
            session=session, awarded_points=8, adherence_ratio=0.9,
            validators_summary={}, breakdown={}, planned_distance_m=2000.0,
            db=db_session, user_token=USER_TOKEN,
        )

    endpoint_id, pseid = _ENDPOINTS["cycling"]
    assert capture["ingest_body"]["sensor_endpoint_id"] == endpoint_id
    assert capture["ingest_body"]["players_sensor_endpoint_id"] == pseid
    # parsed_value son km recorridos: el escalar comparable que consume el mod.
    assert capture["ingest_body"]["parsed_value"] == 2.0


async def test_pseid_cache_keeps_one_entry_per_endpoint(
    db_session, auth_user, endpoints_per_mode, service_account
):
    """Dos modos del mismo jugador cachean dos pseid distintos, no se pisan."""
    for mode in ("walking", "jogging"):
        session = await _make_session(db_session, auth_user, mode)
        with respx.mock:
            _mock_lsg(mode, {})
            await sync_to_lsg(
                session=session, awarded_points=5, adherence_ratio=0.9,
                validators_summary={}, breakdown={}, planned_distance_m=2000.0,
                db=db_session, user_token=USER_TOKEN,
            )

    await db_session.refresh(auth_user)
    assert auth_user.players_sensor_endpoints == {"101": 11, "102": 22}


# ---- Sin token del participante no se gasta presupuesto de reintentos ----

async def test_sync_without_user_token_does_not_burn_an_attempt(
    db_session, auth_user, endpoints_per_mode
):
    """Token muerto -> LSG_SYNC_FAILED sin consumir intentos.

    Si contara como intento, una noche con el token caducado agotaria los 6
    reintentos y la sesion quedaria varada para siempre.
    """
    session = await _make_session(db_session, auth_user, "walking")
    auth_user.lsg_token = None
    auth_user.lsg_token_expires_at = None
    await db_session.commit()

    with respx.mock:  # ninguna llamada a LSG debe salir
        record = await sync_to_lsg(
            session=session, awarded_points=16, adherence_ratio=0.9,
            validators_summary={}, breakdown={}, planned_distance_m=2000.0,
            db=db_session,
        )

    assert record.sync_status == "LSG_SYNC_FAILED"
    assert record.last_error == {"reason": "user_token_expired"}
    assert record.attempts == 0


async def test_retry_worker_skips_sessions_without_user_token(db_session, auth_user, monkeypatch):
    """El worker aplaza en silencio lo que no puede ingestar en nombre del usuario."""
    from app.services import retry_worker

    session = await _make_session(db_session, auth_user, "walking")
    db_session.add(LSGSyncRecord(
        session_id=session.id,
        awarded_points=16,
        sync_status="LSG_SYNC_FAILED",
        attempts=1,
        last_attempt_at=datetime.now(tz=timezone.utc) - timedelta(hours=2),
    ))
    auth_user.lsg_token = None
    await db_session.commit()

    class _Factory:
        def __call__(self):
            return self

        async def __aenter__(self):
            return db_session

        async def __aexit__(self, *exc):
            return False

    monkeypatch.setattr("app.db.session.AsyncSessionLocal", _Factory())

    with respx.mock:  # ninguna llamada a LSG debe salir
        await retry_worker.retry_failed_syncs()

    stmt = select(LSGSyncRecord).where(LSGSyncRecord.session_id == session.id)
    record = (await db_session.execute(stmt)).scalar_one()
    assert record.attempts == 1  # intacto


async def test_get_current_user_stores_the_validated_token(client, db_session):
    """El token se guarda DESPUES de que LSG-Auth lo valide, para el retry_worker."""
    fresh_token = "token-recien-emitido"

    with respx.mock:
        respx.get(f"{LSG_AUTH}/whoami").mock(return_value=Response(200, json={
            "id_players": 77, "name": "Nuevo", "email": "nuevo@usach.cl", "roles": ["player"],
        }))
        resp = await client.get(
            "/lsg-routes/auth/me", headers={"Authorization": f"Bearer {fresh_token}"}
        )

    assert resp.status_code == 200
    user = (await db_session.execute(select(User).where(User.id_players == 77))).scalar_one()
    assert user.lsg_token == fresh_token
