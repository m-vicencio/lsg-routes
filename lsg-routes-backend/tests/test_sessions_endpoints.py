"""Pruebas de integracion para los endpoints de sesiones de ruta."""

import uuid

import pytest

from app.models.route import PlannedRoute
from app.models.session import RouteSession


@pytest.fixture
async def sample_route(db_session, auth_user) -> PlannedRoute:
    """Ruta de prueba perteneciente al auth_user."""
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name="Ruta de prueba",
        mode="jogging",
        geometry=[[-33.4569, -70.6483], [-33.4500, -70.6400]],
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)
    return route


async def test_get_track_returns_planned_and_fixes(auth_client, db_session, sample_route, auth_user):
    """GET /sessions/{id}/track devuelve la ruta planificada y el track GPS real."""
    from datetime import datetime, timezone

    from app.models.session import TrackFix

    session = RouteSession(
        route_id=sample_route.id,
        id_players=auth_user.id_players,
        status="VALIDATED",
        mode=sample_route.mode,
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    db_session.add_all([
        TrackFix(session_id=session.id, lat=-33.4569, lon=-70.6483,
                 captured_at=datetime.now(tz=timezone.utc), client_seq=1),
        TrackFix(session_id=session.id, lat=-33.4550, lon=-70.6450,
                 captured_at=datetime.now(tz=timezone.utc), client_seq=2),
    ])
    await db_session.commit()

    response = await auth_client.get(f"/lsg-routes/sessions/{session.id}/track")

    assert response.status_code == 200
    data = response.json()
    assert data["planned_geometry"] == [[-33.4569, -70.6483], [-33.4500, -70.6400]]
    assert data["fixes"] == [[-33.4569, -70.6483], [-33.4550, -70.6450]]
    assert data["mode"] == "jogging"


async def test_get_track_empty_fixes_ok(auth_client, db_session, sample_route, auth_user):
    """Una sesion sin fixes devuelve track real vacio pero la ruta planificada presente."""
    session = RouteSession(
        route_id=sample_route.id,
        id_players=auth_user.id_players,
        status="REJECTED",
        mode=sample_route.mode,
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    response = await auth_client.get(f"/lsg-routes/sessions/{session.id}/track")

    assert response.status_code == 200
    data = response.json()
    assert data["fixes"] == []
    assert len(data["planned_geometry"]) == 2


async def test_get_track_other_user_returns_404(auth_client, db_session):
    """No se puede ver el track de una sesion ajena."""
    other_route = PlannedRoute(
        id_players=999, name="Ajena", mode="walking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]], origin="DRAWN",
    )
    db_session.add(other_route)
    await db_session.commit()
    await db_session.refresh(other_route)
    other_session = RouteSession(
        route_id=other_route.id, id_players=999, status="VALIDATED",
        mode="walking", client_session_uuid=uuid.uuid4(),
    )
    db_session.add(other_session)
    await db_session.commit()
    await db_session.refresh(other_session)

    response = await auth_client.get(f"/lsg-routes/sessions/{other_session.id}/track")

    assert response.status_code == 404


async def test_abort_session_marks_rejected(auth_client, db_session, sample_route, auth_user):
    """POST /sessions/{id}/abort marca REJECTED sin validar y registra el motivo."""
    session = RouteSession(
        route_id=sample_route.id,
        id_players=auth_user.id_players,
        status="STARTED",
        mode=sample_route.mode,
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    resp = await auth_client.post(f"/lsg-routes/sessions/{session.id}/abort")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "REJECTED"
    assert body["awarded_points"] == 0
    assert body["validators"].get("no_movement") == "FAILED"

    # El resumen persistido tambien refleja el rechazo (ValidationResult guardado)
    summary = await auth_client.get(f"/lsg-routes/sessions/{session.id}/summary")
    assert summary.json()["status"] == "REJECTED"
    assert summary.json()["validators"].get("no_movement") == "FAILED"


async def test_abort_session_is_idempotent(auth_client, db_session, sample_route, auth_user):
    """Abortar dos veces no duplica el ActivityLog (session_id es unico)."""
    session = RouteSession(
        route_id=sample_route.id, id_players=auth_user.id_players,
        status="STARTED", mode=sample_route.mode, client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    r1 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/abort")
    r2 = await auth_client.post(f"/lsg-routes/sessions/{session.id}/abort")

    assert r1.status_code == 200 and r2.status_code == 200
    assert r2.json()["status"] == "REJECTED"


async def test_create_session_returns_201(auth_client, sample_route):
    client_uuid = str(uuid.uuid4())
    response = await auth_client.post("/lsg-routes/sessions", json={
        "route_id": str(sample_route.id),
        "client_session_uuid": client_uuid,
    })

    assert response.status_code == 201
    data = response.json()
    assert data["status"] == "STARTED"
    assert data["mode"] == "jogging"
    assert data["client_session_uuid"] == client_uuid
    assert data["route_id"] == str(sample_route.id)


async def test_create_session_is_idempotent(auth_client, sample_route):
    """Mismo client_session_uuid retorna la misma sesion sin duplicar."""
    client_uuid = str(uuid.uuid4())
    body = {"route_id": str(sample_route.id), "client_session_uuid": client_uuid}

    r1 = await auth_client.post("/lsg-routes/sessions", json=body)
    r2 = await auth_client.post("/lsg-routes/sessions", json=body)

    assert r1.status_code == 201
    assert r2.status_code == 201
    assert r1.json()["id"] == r2.json()["id"]


async def test_create_session_for_nonexistent_route_returns_404(auth_client):
    response = await auth_client.post("/lsg-routes/sessions", json={
        "route_id": str(uuid.uuid4()),
        "client_session_uuid": str(uuid.uuid4()),
    })
    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "ROUTE_NOT_FOUND"


async def test_create_session_for_other_users_route_returns_404(auth_client, db_session):
    """No se puede iniciar sesion en una ruta de otro jugador."""
    other_route = PlannedRoute(
        id_players=999,
        name="Ajena",
        mode="walking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
    )
    db_session.add(other_route)
    await db_session.commit()
    await db_session.refresh(other_route)

    response = await auth_client.post("/lsg-routes/sessions", json={
        "route_id": str(other_route.id),
        "client_session_uuid": str(uuid.uuid4()),
    })
    assert response.status_code == 404


async def test_create_session_for_deleted_route_returns_404(auth_client, db_session, auth_user):
    from datetime import datetime, timezone

    deleted_route = PlannedRoute(
        id_players=auth_user.id_players,
        name="Eliminada",
        mode="hiking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
        deleted_at=datetime.now(tz=timezone.utc),
    )
    db_session.add(deleted_route)
    await db_session.commit()
    await db_session.refresh(deleted_route)

    response = await auth_client.post("/lsg-routes/sessions", json={
        "route_id": str(deleted_route.id),
        "client_session_uuid": str(uuid.uuid4()),
    })
    assert response.status_code == 404


async def test_list_sessions_returns_own(auth_client, db_session, auth_user, sample_route):
    """GET /sessions lista solo las sesiones del usuario."""
    session = RouteSession(
        route_id=sample_route.id,
        id_players=auth_user.id_players,
        status="STARTED",
        mode="jogging",
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)

    other_session = RouteSession(
        route_id=sample_route.id,
        id_players=999,
        status="STARTED",
        mode="jogging",
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(other_session)
    await db_session.commit()

    response = await auth_client.get("/lsg-routes/sessions")

    assert response.status_code == 200
    data = response.json()
    assert len(data) == 1
    assert data[0]["id_players"] == auth_user.id_players


async def test_get_session_by_id(auth_client, db_session, auth_user, sample_route):
    session = RouteSession(
        route_id=sample_route.id,
        id_players=auth_user.id_players,
        status="STARTED",
        mode="jogging",
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(session)
    await db_session.commit()
    await db_session.refresh(session)

    response = await auth_client.get(f"/lsg-routes/sessions/{session.id}")

    assert response.status_code == 200
    assert response.json()["id"] == str(session.id)


async def test_get_session_of_other_user_returns_404(auth_client, db_session, sample_route):
    other_session = RouteSession(
        route_id=sample_route.id,
        id_players=999,
        status="STARTED",
        mode="jogging",
        client_session_uuid=uuid.uuid4(),
    )
    db_session.add(other_session)
    await db_session.commit()
    await db_session.refresh(other_session)

    response = await auth_client.get(f"/lsg-routes/sessions/{other_session.id}")
    assert response.status_code == 404


async def test_get_session_invalid_uuid_returns_404(auth_client):
    response = await auth_client.get("/lsg-routes/sessions/not-a-uuid")
    assert response.status_code == 404
