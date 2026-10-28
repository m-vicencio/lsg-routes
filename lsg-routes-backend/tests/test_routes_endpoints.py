"""Pruebas de integracion para los endpoints de rutas planificadas."""

import uuid

import pytest
import respx
from httpx import Response

from app.core.config import settings
from app.models.route import PlannedRoute

ORS_BASE = settings.ors_base_url.rstrip("/")

DRAWN_ROUTE_BODY = {
    "name": "Ruta por el parque",
    "mode": "walking",
    "origin": "DRAWN",
    "geometry": [[-33.4569, -70.6483], [-33.4550, -70.6450], [-33.4530, -70.6400]],
}

ROUTED_ROUTE_BODY = {
    "name": "Ruta calculada",
    "mode": "cycling",
    "origin": "ROUTED",
    "start_latlon": [-33.4569, -70.6483],
    "end_latlon": [-33.4450, -70.6300],
}

ORS_MOCK_RESPONSE = {
    "type": "FeatureCollection",
    "features": [{
        "type": "Feature",
        "geometry": {
            "type": "LineString",
            "coordinates": [[-70.6483, -33.4569], [-70.6400, -33.4500], [-70.6300, -33.4450]],
        },
        "properties": {"summary": {"distance": 3200.0, "duration": 720.0}},
    }],
}


async def test_create_drawn_route_returns_201(auth_client):
    """Ademas del contrato de la respuesta, verifica que la ruta dibujada estima
    distancia (Haversine) y duracion (velocidad de crucero) de forma coherente."""
    response = await auth_client.post("/lsg-routes/routes", json=DRAWN_ROUTE_BODY)

    assert response.status_code == 201
    data = response.json()
    assert data["name"] == "Ruta por el parque"
    assert data["mode"] == "walking"
    assert data["origin"] == "DRAWN"
    assert data["geometry"] == DRAWN_ROUTE_BODY["geometry"]
    assert "id" in data
    dist = data["planned_distance_m"]
    dur = data["planned_duration_s"]
    # ~970 m sobre los 3 puntos; a 5 km/h (walking) ~ 700 s. Verificamos coherencia.
    assert dist is not None and 500 < dist < 2000
    assert dur is not None and dur > 0
    # duracion ~ distancia / (5/3.6)
    assert abs(dur - dist / (5.0 / 3.6)) < 2


async def test_create_routed_route_calls_ors_and_stores_geometry(auth_client, monkeypatch):
    """La ruta ROUTED llama a ORS y almacena la geometria resultante."""
    monkeypatch.setattr(settings, "bike_activate", True)  # el cuerpo de prueba va en bicicleta
    with respx.mock:
        respx.post(f"{ORS_BASE}/v2/directions/cycling-regular/geojson").mock(
            return_value=Response(200, json=ORS_MOCK_RESPONSE)
        )
        response = await auth_client.post("/lsg-routes/routes", json=ROUTED_ROUTE_BODY)

    assert response.status_code == 201
    data = response.json()
    assert data["origin"] == "ROUTED"
    assert data["planned_distance_m"] == 3200.0
    assert data["planned_duration_s"] == 720
    # Geometria convertida a [lat, lon]
    assert data["geometry"][0] == [-33.4569, -70.6483]


@pytest.mark.parametrize(
    "body",
    [
        pytest.param({"name": "Sin geometria", "mode": "walking", "origin": "DRAWN"}, id="drawn_without_geometry"),
        pytest.param({"name": "Sin endpoints", "mode": "walking", "origin": "ROUTED"}, id="routed_without_endpoints"),
    ],
)
async def test_create_route_missing_required_field_returns_422(auth_client, body):
    response = await auth_client.post("/lsg-routes/routes", json=body)
    assert response.status_code == 422


@pytest.mark.parametrize(
    "bike_activate,expected_status",
    [
        pytest.param(False, 400, id="rejected_when_bike_disabled"),
        pytest.param(True, 201, id="allowed_when_bike_enabled"),
    ],
)
async def test_create_cycling_route_gated_by_bike_flag(auth_client, monkeypatch, bike_activate, expected_status):
    """Sin BIKE_ACTIVATE el backend rechaza el modo, aunque la app lo esconda."""
    monkeypatch.setattr(settings, "bike_activate", bike_activate)
    body = {**DRAWN_ROUTE_BODY, "mode": "cycling"}

    response = await auth_client.post("/lsg-routes/routes", json=body)

    assert response.status_code == expected_status
    if not bike_activate:
        assert response.json()["detail"]["code"] == "MODE_DISABLED"


@pytest.mark.parametrize(
    "id_players,deleted,expected_count",
    [
        pytest.param(None, False, 1, id="returns_own_routes"),  # None = auth_user.id_players
        pytest.param(None, True, 0, id="excludes_deleted"),
        pytest.param(999, False, 0, id="excludes_other_users"),
    ],
)
async def test_list_routes(auth_client, db_session, auth_user, id_players, deleted, expected_count):
    """GET /routes retorna solo las rutas propias, activas, del usuario autenticado."""
    from datetime import datetime, timezone

    route = PlannedRoute(
        id_players=id_players if id_players is not None else auth_user.id_players,
        name="Mi ruta",
        mode="walking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
        deleted_at=datetime.now(tz=timezone.utc) if deleted else None,
    )
    db_session.add(route)
    await db_session.commit()

    response = await auth_client.get("/lsg-routes/routes")

    assert response.status_code == 200
    data = response.json()
    assert len(data) == expected_count
    if expected_count == 1:
        assert data[0]["name"] == "Mi ruta"


async def test_get_route_by_id(auth_client, db_session, auth_user):
    """GET /routes/{id} retorna la ruta correcta del usuario."""
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name="Detalle ruta",
        mode="cycling",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)

    response = await auth_client.get(f"/lsg-routes/routes/{route.id}")

    assert response.status_code == 200
    assert response.json()["name"] == "Detalle ruta"


async def test_get_route_of_other_user_returns_404(auth_client, db_session):
    """GET /routes/{id} retorna 404 para rutas de otros usuarios."""
    other_route = PlannedRoute(
        id_players=999,
        name="Ruta ajena",
        mode="walking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
    )
    db_session.add(other_route)
    await db_session.commit()
    await db_session.refresh(other_route)

    response = await auth_client.get(f"/lsg-routes/routes/{other_route.id}")

    assert response.status_code == 404


async def test_get_route_invalid_uuid_returns_404(auth_client):
    response = await auth_client.get("/lsg-routes/routes/not-a-uuid")
    assert response.status_code == 404


async def test_delete_route_returns_204(auth_client, db_session, auth_user):
    """DELETE /routes/{id} elimina suavemente la ruta."""
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name="A eliminar",
        mode="walking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)

    response = await auth_client.delete(f"/lsg-routes/routes/{route.id}")
    assert response.status_code == 204

    # Verificar soft delete
    await db_session.refresh(route)
    assert route.deleted_at is not None


async def test_deleted_route_not_in_list(auth_client, db_session, auth_user):
    """Una ruta eliminada no aparece en GET /routes."""
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name="Sera eliminada",
        mode="walking",
        geometry=[[-33.4, -70.6], [-33.5, -70.5]],
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)

    await auth_client.delete(f"/lsg-routes/routes/{route.id}")
    response = await auth_client.get("/lsg-routes/routes")

    assert response.json() == []


async def test_delete_other_users_route_returns_404(auth_client, db_session):
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

    response = await auth_client.delete(f"/lsg-routes/routes/{other_route.id}")
    assert response.status_code == 404


async def test_ors_error_returns_503(auth_client):
    """Si ORS falla, el endpoint retorna 503."""
    with respx.mock:
        respx.post(f"{ORS_BASE}/v2/directions/foot-walking/geojson").mock(
            return_value=Response(403, json={"error": {"message": "Invalid key"}})
        )
        response = await auth_client.post("/lsg-routes/routes", json={
            "name": "Falla ORS",
            "mode": "walking",
            "origin": "ROUTED",
            "start_latlon": [-33.4, -70.6],
            "end_latlon": [-33.5, -70.5],
        })

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "ORS_ERROR"
