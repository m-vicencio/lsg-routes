"""Pruebas del endpoint de clima de una ruta."""

import httpx
import pytest

from app.api.routes import routes as routes_module
from app.models.route import PlannedRoute


@pytest.fixture
async def sample_route(db_session, auth_user) -> PlannedRoute:
    route = PlannedRoute(
        id_players=auth_user.id_players,
        name="Ruta clima",
        mode="walking",
        geometry=[[-33.4569, -70.6483], [-33.4500, -70.6400]],
        origin="DRAWN",
    )
    db_session.add(route)
    await db_session.commit()
    await db_session.refresh(route)
    return route


async def test_route_weather_uses_start_point(auth_client, sample_route, monkeypatch):
    """GET /routes/{id}/weather consulta Open-Meteo en el punto de inicio."""
    captured = {}

    async def mock_current(lat: float, lon: float):
        captured["lat"], captured["lon"] = lat, lon
        return {
            "temperature_c": 14.2, "apparent_temperature_c": 12.8,
            "humidity_pct": 71, "precipitation_mm": 0.0,
            "weather_code": 3, "wind_speed_kmh": 9.5, "is_day": True,
        }

    monkeypatch.setattr(routes_module.weather_client, "current", mock_current)

    resp = await auth_client.get(f"/lsg-routes/routes/{sample_route.id}/weather")

    assert resp.status_code == 200
    body = resp.json()
    # Punto de inicio = primer par de la geometria [lat, lon]
    assert captured["lat"] == -33.4569
    assert captured["lon"] == -70.6483
    assert body["latitude"] == -33.4569
    assert body["temperature_c"] == 14.2
    assert body["weather_code"] == 3
    assert body["is_day"] is True


async def test_route_weather_unknown_route_404(auth_client):
    import uuid
    resp = await auth_client.get(f"/lsg-routes/routes/{uuid.uuid4()}/weather")
    assert resp.status_code == 404


async def test_route_weather_provider_error_returns_502(auth_client, sample_route, monkeypatch):
    async def mock_current(lat: float, lon: float):
        raise httpx.RequestError("open-meteo down")

    monkeypatch.setattr(routes_module.weather_client, "current", mock_current)

    resp = await auth_client.get(f"/lsg-routes/routes/{sample_route.id}/weather")

    assert resp.status_code == 502
    assert resp.json()["detail"]["code"] == "WEATHER_SERVICE_ERROR"
