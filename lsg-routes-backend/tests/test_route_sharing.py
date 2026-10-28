"""Pruebas del feed comunitario de rutas (compartir / copiar / visibilidad)."""

import pytest

from app.api.routes import routes as routes_module
from app.api.routes.routes import _trim_geometry
from app.models.route import PlannedRoute
from app.validators.common import haversine_m

GEOMETRY = [[-33.45, -70.65], [-33.46, -70.66]]


async def _make_route(db_session, id_players: int, name: str, is_public: bool, shared_by: str | None = None) -> PlannedRoute:
    route = PlannedRoute(
        id_players=id_players,
        name=name,
        mode="walking",
        geometry=GEOMETRY,
        planned_distance_m=1500.0,
        planned_duration_s=900,
        origin="DRAWN",
        is_public=is_public,
        shared_by_name=shared_by,
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)
    return route


@pytest.mark.asyncio
async def test_public_feed_includes_own_public_excludes_private(auth_client, db_session, auth_user):
    # Otro jugador con una ruta pública y otra privada
    public_other = await _make_route(db_session, 99, "Ruta de Otro", is_public=True, shared_by="Otro")
    await _make_route(db_session, 99, "Privada de Otro", is_public=False)
    # Una ruta pública del propio usuario: SÍ debe aparecer, marcada is_mine
    mine_public = await _make_route(db_session, auth_user.id_players, "Mía Pública", is_public=True)
    # Una ruta privada propia: NO debe aparecer
    await _make_route(db_session, auth_user.id_players, "Mía Privada", is_public=False)

    resp = await auth_client.get("/lsg-routes/routes/public")
    assert resp.status_code == 200
    body = resp.json()
    by_id = {r["id"]: r for r in body}
    assert str(public_other.id) in by_id
    assert str(mine_public.id) in by_id
    assert len(by_id) == 2  # ambas públicas, ninguna privada
    assert by_id[str(mine_public.id)]["is_mine"] is True
    assert by_id[str(public_other.id)]["is_mine"] is False


@pytest.mark.asyncio
async def test_copy_public_route_clones_into_caller(auth_client, db_session, auth_user):
    source = await _make_route(db_session, 99, "Sendero Cerro", is_public=True, shared_by="Otro")

    resp = await auth_client.post(f"/lsg-routes/routes/{source.id}/copy")
    assert resp.status_code == 201
    body = resp.json()
    assert body["id_players"] == auth_user.id_players
    assert body["id"] != str(source.id)
    assert body["geometry"] == GEOMETRY
    assert body["is_public"] is False


@pytest.mark.asyncio
async def test_copy_is_idempotent(auth_client, db_session):
    source = await _make_route(db_session, 99, "Ruta", is_public=True)
    first = await auth_client.post(f"/lsg-routes/routes/{source.id}/copy")
    second = await auth_client.post(f"/lsg-routes/routes/{source.id}/copy")
    assert first.json()["id"] == second.json()["id"]


@pytest.mark.asyncio
async def test_cannot_copy_private_route_of_other(auth_client, db_session):
    source = await _make_route(db_session, 99, "Privada", is_public=False)
    resp = await auth_client.post(f"/lsg-routes/routes/{source.id}/copy")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_make_public_sets_shared_by_name(auth_client, db_session, auth_user):
    route = await _make_route(db_session, auth_user.id_players, "Mi Ruta", is_public=False)
    resp = await auth_client.patch(
        f"/lsg-routes/routes/{route.id}/visibility", json={"is_public": True}
    )
    assert resp.status_code == 200
    assert resp.json()["is_public"] is True
    assert resp.json()["shared_by_name"] == auth_user.name


@pytest.mark.asyncio
async def test_visibility_only_owner(auth_client, db_session):
    other = await _make_route(db_session, 99, "De Otro", is_public=False)
    resp = await auth_client.patch(
        f"/lsg-routes/routes/{other.id}/visibility", json={"is_public": True}
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_public_route_allows_non_owner(auth_client, db_session):
    source = await _make_route(db_session, 99, "Pública", is_public=True, shared_by="Otro")
    resp = await auth_client.get(f"/lsg-routes/routes/{source.id}")
    assert resp.status_code == 200
    assert resp.json()["shared_by_name"] == "Otro"


# --- Recorte de extremos al compartir -------------------------------------------------

# ~1.1 km al sur y ~0.9 km al oeste: ~2 km en total
LONG_GEOMETRY = [[-33.45, -70.65], [-33.46, -70.65], [-33.46, -70.66]]


async def _make_trimmed_route(db_session, id_players: int, start_m: int = 200, end_m: int = 200) -> PlannedRoute:
    route = PlannedRoute(
        id_players=id_players, name="Desde casa", mode="walking", geometry=LONG_GEOMETRY,
        planned_distance_m=2000.0, planned_duration_s=1440, origin="DRAWN",
        is_public=True, shared_by_name="Otro", hide_start_m=start_m, hide_end_m=end_m,
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)
    return route


def test_trim_geometry_cuts_by_distance():
    assert _trim_geometry(LONG_GEOMETRY, 0, 0) == LONG_GEOMETRY
    trimmed = _trim_geometry(LONG_GEOMETRY, 200, 300)
    assert abs(haversine_m(*LONG_GEOMETRY[0], *trimmed[0]) - 200) < 1
    assert abs(haversine_m(*LONG_GEOMETRY[-1], *trimmed[-1]) - 300) < 1
    assert trimmed[1:-1] == LONG_GEOMETRY[1:-1]
    # Una recta de 2 puntos tambien se recorta (interpolando), no queda vacia
    assert len(_trim_geometry(GEOMETRY, 100, 100)) == 2
    assert _trim_geometry(LONG_GEOMETRY, 1500, 1500) == []


@pytest.mark.asyncio
async def test_non_owner_gets_trimmed_route(auth_client, db_session):
    route = await _make_trimmed_route(db_session, 99)

    body = (await auth_client.get(f"/lsg-routes/routes/{route.id}")).json()
    assert body["geometry"][0] != LONG_GEOMETRY[0]
    assert body["geometry"][-1] != LONG_GEOMETRY[-1]
    assert body["hide_start_m"] == 0 and body["hide_end_m"] == 0
    assert body["planned_distance_m"] < 2000.0

    feed = (await auth_client.get("/lsg-routes/routes/public")).json()
    assert feed[0]["geometry"] == body["geometry"]


@pytest.mark.asyncio
async def test_owner_gets_full_route(auth_client, db_session, auth_user):
    route = await _make_trimmed_route(db_session, auth_user.id_players)
    body = (await auth_client.get(f"/lsg-routes/routes/{route.id}")).json()
    assert body["geometry"] == LONG_GEOMETRY
    assert body["hide_start_m"] == 200


@pytest.mark.asyncio
async def test_copy_stores_trimmed_geometry(auth_client, db_session):
    source = await _make_trimmed_route(db_session, 99)
    resp = await auth_client.post(f"/lsg-routes/routes/{source.id}/copy")
    copy = await db_session.get(PlannedRoute, routes_module._uuid.UUID(resp.json()["id"]))
    await db_session.refresh(copy)
    assert copy.geometry[0] != LONG_GEOMETRY[0]
    assert copy.geometry[-1] != LONG_GEOMETRY[-1]
    assert copy.hide_start_m == 0 and copy.hide_end_m == 0


@pytest.mark.asyncio
async def test_weather_for_non_owner_hides_real_start(auth_client, db_session, monkeypatch):
    route = await _make_trimmed_route(db_session, 99)

    async def mock_current(lat: float, lon: float):
        return {
            "temperature_c": 14.0, "apparent_temperature_c": 13.0, "humidity_pct": 70,
            "precipitation_mm": 0.0, "weather_code": 0, "wind_speed_kmh": 5.0, "is_day": True,
        }

    monkeypatch.setattr(routes_module.weather_client, "current", mock_current)
    body = (await auth_client.get(f"/lsg-routes/routes/{route.id}/weather")).json()
    assert [body["latitude"], body["longitude"]] != LONG_GEOMETRY[0]


@pytest.mark.asyncio
async def test_publish_with_trim(auth_client, db_session, auth_user):
    route = await _make_route(db_session, auth_user.id_players, "Mi Ruta", is_public=False)
    url = f"/lsg-routes/routes/{route.id}/visibility"

    too_long = await auth_client.patch(url, json={"is_public": True, "hide_start_m": 2000, "hide_end_m": 2000})
    assert too_long.status_code == 400
    assert too_long.json()["detail"]["code"] == "TRIM_TOO_LONG"

    ok = await auth_client.patch(url, json={"is_public": True, "hide_start_m": 100, "hide_end_m": 0})
    assert ok.status_code == 200
    assert ok.json()["hide_start_m"] == 100
    # Volver a privada conserva el recorte
    private = await auth_client.patch(url, json={"is_public": False})
    assert private.json()["hide_start_m"] == 100
