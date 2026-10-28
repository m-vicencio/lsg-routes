"""Pruebas de integracion para /sync, /finish y /summary."""

import json
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import respx
from httpx import Response
from sqlalchemy import select

from app.core.config import settings
from app.models.route import PlannedRoute
from app.models.session import ActivityLog, LSGSyncRecord, RouteSession, TrackFix
from tests.conftest import mock_player_sensors

LSG_CORE = settings.lsg_core_base_url.rstrip("/")
LSG_AUTH = settings.lsg_auth_base_url.rstrip("/")

# Ruta planificada que cubre ~500m en Santiago
_PLANNED_GEOMETRY = [
    [-33.4500, -70.6500],
    [-33.4510, -70.6490],
    [-33.4520, -70.6480],
    [-33.4530, -70.6470],
    [-33.4540, -70.6460],
]
# Track que sigue la ruta planificada a ~1.2 m/s (caminata lenta, 120s entre puntos ~141m)
_GOOD_FIXES = [
    {"client_seq": i, "lat": p[0], "lon": p[1],
     "accuracy_m": 5.0, "altitude_m": 520.0, "speed_mps": 1.2,
     "bearing_deg": 45.0, "is_from_mock_provider": False,
     "captured_at": (datetime(2026, 6, 1, 10, 0, tzinfo=timezone.utc) + timedelta(seconds=i * 120)).isoformat()}
    for i, p in enumerate(_PLANNED_GEOMETRY)
]
_GOOD_ACCEL = [
    {"bucket_started_at": (datetime(2026, 6, 1, 10, 0, tzinfo=timezone.utc) + timedelta(seconds=i)).isoformat(),
     "bucket_seconds": 1, "magnitude_mean": 9.9, "magnitude_variance": 0.15}
    for i in range(5)
]
_GOOD_STEPS = [
    {"steps_delta": 500,  # ~576m / 0.7m por paso ~ 823 esperados; 500 > 50%
     "captured_at": datetime(2026, 6, 1, 10, 1, tzinfo=timezone.utc).isoformat()}
]


@pytest.fixture
async def route_and_session(db_session, auth_user):
    """Ruta y sesion de prueba para el auth_user."""
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name="Ruta de prueba",
        mode="walking",
        geometry=_PLANNED_GEOMETRY,
        planned_distance_m=500.0,
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)

    session = RouteSession(
        route_id=route.id,
        id_players=auth_user.id_players,
        status="STARTED",
        mode="walking",
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)
    return route, session


# ---- /sync ----

async def test_sync_stores_fixes(auth_client, route_and_session, db_session):
    """POST /sync almacena los fixes en base de datos."""
    _, session = route_and_session
    client_ref = str(uuid.uuid4())

    response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": client_ref,
        "fixes": _GOOD_FIXES[:3],
        "accel_buckets": _GOOD_ACCEL[:2],
        "step_counts": _GOOD_STEPS,
    })

    assert response.status_code == 207
    data = response.json()
    assert data["status"] == "PROCESSED"
    assert data["fixes_stored"] == 3

    stmt = select(TrackFix).where(TrackFix.session_id == session.id)
    result = await db_session.execute(stmt)
    assert len(result.scalars().all()) == 3


async def test_sync_idempotent_same_client_ref(auth_client, route_and_session):
    """Mismo client_ref retorna DUPLICATE sin duplicar datos."""
    _, session = route_and_session
    client_ref = str(uuid.uuid4())
    body = {"client_ref": client_ref, "fixes": _GOOD_FIXES[:2]}

    r1 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json=body)
    r2 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json=body)

    assert r1.status_code == 207
    assert r2.json()["status"] == "DUPLICATE"


async def test_sync_deduplicates_fixes_by_client_seq(auth_client, route_and_session, db_session):
    """Fixes con mismo client_seq no se duplican entre batches."""
    _, session = route_and_session

    body1 = {"client_ref": str(uuid.uuid4()), "fixes": _GOOD_FIXES[:3]}
    body2 = {"client_ref": str(uuid.uuid4()), "fixes": _GOOD_FIXES[1:4]}  # seqs 1,2,3 solapados

    r1 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json=body1)
    r2 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json=body2)

    assert r1.json()["fixes_stored"] == 3
    assert r2.json()["fixes_skipped"] == 2  # seqs 1 y 2 ya existen
    assert r2.json()["fixes_stored"] == 1   # solo seq 3 es nuevo


# ---- /finish - camino feliz ----

async def test_finish_clean_track_syncs_to_lsg(auth_client, route_and_session):
    """Track limpio valida, calcula puntos y llama a LSG-Core."""
    _, session = route_and_session

    # Cargar telemetria
    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": _GOOD_FIXES,
        "accel_buckets": _GOOD_ACCEL,
        "step_counts": _GOOD_STEPS,
    })

    # Simula LSG-Auth (cuenta de servicio) y LSG-Core
    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json={
            "access_token": "svc_tok",
            "token_type": "bearer",
            "expires_in_seconds": 7200,
            "expires_at": "2099-12-31T23:59:59+00:00",
            "player": {"id_players": 1},
        }))
        respx.get(f"{LSG_CORE}/sensors/players/{session.id_players}").mock(
            return_value=Response(200, json=mock_player_sensors())
        )
        respx.post(f"{LSG_CORE}/sensors/ingest/webhook").mock(
            return_value=Response(200, json={"status": "ok", "id_sensor_ingest_event": 55})
        )
        respx.post(f"{LSG_CORE}/players/{session.id_players}/points/adjust").mock(
            return_value=Response(200, json={"status": "ok", "source_ref": "ADJUST-test"})
        )
        respx.get(f"{LSG_CORE}/points/ledger").mock(
            return_value=Response(200, json=[])
        )

        response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "SYNCED_TO_LSG"
    assert data["awarded_points"] >= 0
    assert data["adherence_ratio"] is not None
    assert all(v == "OK" for v in data["validators"].values())


# ---- /finish - track falsificado ----

async def test_finish_mock_location_track_is_rejected(auth_client, route_and_session):
    """Track con ubicaciones falsificadas es rechazado sin llamar a LSG-Core."""
    _, session = route_and_session

    mock_fixes = [{**f, "is_from_mock_provider": True} for f in _GOOD_FIXES]
    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": mock_fixes,
    })

    with respx.mock:
        # No debe llamar a LSG-Core si fue rechazado
        response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "REJECTED"
    assert data["awarded_points"] == 0
    assert data["validators"]["mock_location"] == "FAILED"


# ---- /finish - falla el paso 2 de LSG -> reintento sin doble credito ----

async def test_finish_lsg_step2_failure_then_retry(auth_client, route_and_session, db_session):
    """Fallo en adjust points persiste LSG_SYNC_FAILED; retry idempotente no doble-acredita."""
    _, session = route_and_session

    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": _GOOD_FIXES,
        "accel_buckets": _GOOD_ACCEL,
        "step_counts": _GOOD_STEPS,
    })

    session_uuid = str(session.id)

    # Primera llamada: ingest OK, adjust falla
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
            return_value=Response(200, json={"status": "ok", "id_sensor_ingest_event": 77})
        )
        respx.get(f"{LSG_CORE}/points/ledger").mock(return_value=Response(200, json=[]))
        respx.post(f"{LSG_CORE}/players/{session.id_players}/points/adjust").mock(
            return_value=Response(500, json={"detail": "Internal server error"})
        )

        response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert response.json()["status"] == "LSG_SYNC_FAILED"

    # Segunda llamada: el ledger ya tiene la entrada (idempotencia)
    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json={
            "access_token": "svc_tok2", "token_type": "bearer",
            "expires_in_seconds": 7200, "expires_at": "2099-12-31T23:59:59+00:00",
            "player": {"id_players": 1},
        }))
        respx.get(f"{LSG_CORE}/sensors/players/{session.id_players}").mock(
            return_value=Response(200, json=mock_player_sensors())
        )
        respx.post(f"{LSG_CORE}/sensors/ingest/webhook").mock(
            return_value=Response(200, json={"status": "ok", "id_sensor_ingest_event": 77})
        )
        # El ledger retorna una entrada que ya incluye nuestro session_uuid
        respx.get(f"{LSG_CORE}/points/ledger").mock(
            return_value=Response(200, json=[
                {"id": 99, "source_type": "ADJUST", "reason": f"session_uuid={session_uuid}"}
            ])
        )
        adjust_route = respx.post(f"{LSG_CORE}/players/{session.id_players}/points/adjust")

        response2 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    # No debe llamar a adjust porque el ledger ya tiene el credito
    assert not adjust_route.called
    assert response2.json()["status"] == "SYNCED_TO_LSG"


# ---- /finish - falla el paso 1 de LSG -> LSG_SYNC_FAILED sin llamar a adjust ----

async def test_finish_lsg_step1_failure_skips_adjust(auth_client, route_and_session):
    """Si el ingest (paso 1) falla, la sesion queda en LSG_SYNC_FAILED sin acreditar puntos
    y sin llamar a adjust (paso 2)."""
    _, session = route_and_session

    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": _GOOD_FIXES,
        "accel_buckets": _GOOD_ACCEL,
        "step_counts": _GOOD_STEPS,
    })

    adjust_called = False

    def track_adjust(request):
        nonlocal adjust_called
        adjust_called = True
        return Response(200, json={"status": "ok", "source_ref": "ADJUST-x"})

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
            return_value=Response(503, json={"detail": "Service unavailable"}))
        respx.post(f"{LSG_CORE}/players/{session.id_players}/points/adjust").mock(
            side_effect=track_adjust)

        response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert response.json()["status"] == "LSG_SYNC_FAILED"
    assert not adjust_called, "adjust NO debe llamarse si el ingest fallo"


# ---- reintentos: el evento de sensor se registra exactamente una vez ----

def _mock_lsg(session, ingest: Response, adjust: Response):
    """Mocks de LSG-Auth y LSG-Core; devuelve las rutas de ingest y adjust."""
    respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json={
        "access_token": "svc_tok", "token_type": "bearer",
        "expires_in_seconds": 7200, "expires_at": "2099-12-31T23:59:59+00:00",
        "player": {"id_players": 1},
    }))
    respx.get(f"{LSG_CORE}/sensors/players/{session.id_players}").mock(
        return_value=Response(200, json=mock_player_sensors()))
    respx.get(f"{LSG_CORE}/points/ledger").mock(return_value=Response(200, json=[]))
    ingest_route = respx.post(f"{LSG_CORE}/sensors/ingest/webhook").mock(return_value=ingest)
    adjust_route = respx.post(
        f"{LSG_CORE}/players/{session.id_players}/points/adjust").mock(return_value=adjust)
    return ingest_route, adjust_route


async def _upload_good_track(auth_client, session):
    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": _GOOD_FIXES,
        "accel_buckets": _GOOD_ACCEL,
        "step_counts": _GOOD_STEPS,
    })


async def test_retry_after_ingest_failure_still_registers_the_event(auth_client, route_and_session):
    """Si el primer intento fallo en el ingest, el reintento debe registrar el evento.

    Antes el reintento mandaba 0 km y _do_ingest lo omitia: se acreditaban los puntos
    pero el evento nunca llegaba a LSG-Core (ni al estudio ni al mod de BG3).
    """
    _, session = route_and_session
    await _upload_good_track(auth_client, session)

    with respx.mock:
        _mock_lsg(session, ingest=Response(503), adjust=Response(200, json={"source_ref": "A"}))
        first = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")
    assert first.json()["status"] == "LSG_SYNC_FAILED"

    with respx.mock:
        ingest, _ = _mock_lsg(
            session,
            ingest=Response(200, json={"status": "ok", "id_sensor_ingest_event": 88}),
            adjust=Response(200, json={"source_ref": "A"}),
        )
        second = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert second.json()["status"] == "SYNCED_TO_LSG"
    assert ingest.call_count == 1
    body = json.loads(ingest.calls.last.request.content)
    assert body["parsed_value"] == pytest.approx(0.5)  # 500 m planificados


async def test_retry_after_adjust_failure_does_not_ingest_twice(auth_client, route_and_session):
    """Con el ingest ya registrado, el reintento solo repite el adjust."""
    _, session = route_and_session
    await _upload_good_track(auth_client, session)

    with respx.mock:
        _mock_lsg(
            session,
            ingest=Response(200, json={"status": "ok", "id_sensor_ingest_event": 77}),
            adjust=Response(500),
        )
        first = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")
    assert first.json()["status"] == "LSG_SYNC_FAILED"

    with respx.mock:
        ingest, adjust = _mock_lsg(
            session,
            ingest=Response(200, json={"status": "ok", "id_sensor_ingest_event": 78}),
            adjust=Response(200, json={"source_ref": "A"}),
        )
        second = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert second.json()["status"] == "SYNCED_TO_LSG"
    assert not ingest.called
    assert adjust.call_count == 1


# ---- /sync sobre una sesion cerrada ----

async def test_sync_rejects_new_batches_after_close(auth_client, route_and_session):
    """Una sesion cerrada no acepta telemetria nueva, pero un reenvio sigue siendo DUPLICATE."""
    _, session = route_and_session
    batch = {"client_ref": str(uuid.uuid4()), "fixes": _GOOD_FIXES[:2]}
    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json=batch)
    await auth_client.post(f"/lsg-routes/sessions/{session.id}/abort")

    resent = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json=batch)
    late = await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()), "fixes": _GOOD_FIXES[2:],
    })

    assert resent.json()["status"] == "DUPLICATE"
    assert late.status_code == 409
    assert late.json()["detail"]["code"] == "SESSION_CLOSED"


# ---- POST /sessions con un id ajeno ----

async def test_create_session_rejects_an_id_already_in_use(auth_client, route_and_session):
    """El id lo propone el cliente: si ya existe con otro client_session_uuid, 409 y no 500."""
    route, session = route_and_session
    response = await auth_client.post("/lsg-routes/sessions", json={
        "id": str(session.id),
        "route_id": str(route.id),
        "client_session_uuid": str(uuid.uuid4()),
    })

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "SESSION_ID_CONFLICT"


# ---- /summary ----

async def test_summary_endpoint_returns_session_state(auth_client, route_and_session):
    """GET /sessions/{id}/summary retorna el estado actual de la sesion."""
    _, session = route_and_session
    response = await auth_client.get(f"/lsg-routes/sessions/{session.id}/summary")

    assert response.status_code == 200
    data = response.json()
    assert data["session_id"] == str(session.id)
    assert data["status"] == "STARTED"


# ---- ventana temporal real (rutas offline) ----

# Los fixes de prueba abarcan de 10:00:00 a 10:08:00 UTC: 4 tramos de 120 s.
_TRACK_START = datetime(2026, 6, 1, 10, 0, tzinfo=timezone.utc)
_TRACK_SECONDS = 480


def _as_utc(value: datetime) -> datetime:
    """SQLite devuelve datetimes naive; el codigo de produccion los asume UTC."""
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


async def test_finish_mide_la_ventana_real_del_track_y_no_la_del_servidor(
    auth_client, route_and_session, db_session
):
    """La duracion sale de los fixes, no del reloj del servidor.

    Una ruta grabada sin conexion se crea en el backend recien al sincronizar, asi que
    started_at y ended_at caian con segundos de diferencia: una caminata de 8 minutos
    se registraba como 1 s.
    """
    _, session = route_and_session

    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": _GOOD_FIXES,
        "accel_buckets": _GOOD_ACCEL,
        "step_counts": _GOOD_STEPS,
    })

    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json={
            "access_token": "svc_tok", "token_type": "bearer",
            "expires_in_seconds": 7200, "expires_at": "2099-12-31T23:59:59+00:00",
            "player": {"id_players": 1},
        }))
        respx.get(f"{LSG_CORE}/sensors/players/{session.id_players}").mock(
            return_value=Response(200, json=mock_player_sensors()))
        respx.post(f"{LSG_CORE}/sensors/ingest/webhook").mock(
            return_value=Response(200, json={"status": "ok", "id_sensor_ingest_event": 55}))
        respx.post(f"{LSG_CORE}/players/{session.id_players}/points/adjust").mock(
            return_value=Response(200, json={"status": "ok", "source_ref": "ADJUST-test"}))
        respx.get(f"{LSG_CORE}/points/ledger").mock(return_value=Response(200, json=[]))

        response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/finish")

    assert response.status_code == 200
    data = response.json()
    assert _as_utc(datetime.fromisoformat(data["started_at"])) == _TRACK_START
    assert _as_utc(datetime.fromisoformat(data["ended_at"])) == (
        _TRACK_START + timedelta(seconds=_TRACK_SECONDS)
    )

    log = (await db_session.execute(
        select(ActivityLog).where(ActivityLog.session_id == session.id)
    )).scalar_one()
    assert log.duration_s == _TRACK_SECONDS
    # El historial debe fechar la actividad cuando ocurrio, no cuando volvio la red
    assert _as_utc(log.created_at) == _TRACK_START + timedelta(seconds=_TRACK_SECONDS)


async def test_abort_tambien_usa_la_ventana_real_del_track(
    auth_client, route_and_session, db_session
):
    """El mismo error estaba en /abort, que tampoco pasa por los validadores."""
    _, session = route_and_session

    await auth_client.post(f"/lsg-routes/sessions/{session.id}/sync", json={
        "client_ref": str(uuid.uuid4()),
        "fixes": _GOOD_FIXES,
        "accel_buckets": [],
        "step_counts": [],
    })

    response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/abort")

    assert response.status_code == 200
    log = (await db_session.execute(
        select(ActivityLog).where(ActivityLog.session_id == session.id)
    )).scalar_one()
    assert log.status == "REJECTED"
    assert log.duration_s == _TRACK_SECONDS


async def test_sesion_sin_fixes_no_inventa_una_ventana(
    auth_client, route_and_session, db_session
):
    """Sin track no hay hora real que recuperar: la duracion honesta es 0."""
    _, session = route_and_session

    response = await auth_client.post(f"/lsg-routes/sessions/{session.id}/abort")

    assert response.status_code == 200
    log = (await db_session.execute(
        select(ActivityLog).where(ActivityLog.session_id == session.id)
    )).scalar_one()
    assert log.duration_s == 0
