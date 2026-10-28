"""Pruebas unitarias del cliente OpenRouteService."""

import pytest
import respx
from httpx import Response

from app.clients.ors import OrsClient, ORS_PROFILE

ORS_BASE = "https://ors.test"

# Respuesta GeoJSON tipica de ORS
def make_ors_response(coords_lon_lat: list, distance_m: float, duration_s: float) -> dict:
    return {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "geometry": {"type": "LineString", "coordinates": coords_lon_lat},
            "properties": {
                "summary": {"distance": distance_m, "duration": duration_s},
                "segments": [],
            },
        }],
    }


@pytest.fixture
def ors():
    return OrsClient(ORS_BASE, "test_api_key")


async def test_route_between_returns_geometry_distance_duration(ors):
    """route_between retorna geometria, distancia y duracion correctas."""
    ors_coords = [[-70.6483, -33.4569], [-70.6400, -33.4500], [-70.6300, -33.4450]]
    with respx.mock:
        respx.post(f"{ORS_BASE}/v2/directions/foot-walking/geojson").mock(
            return_value=Response(200, json=make_ors_response(ors_coords, 1500.0, 1080.0))
        )
        result = await ors.route_between(
            start_latlon=(-33.4569, -70.6483),
            end_latlon=(-33.4450, -70.6300),
            mode="walking",
        )

    assert result["distance_m"] == 1500.0
    assert result["duration_s"] == 1080
    # Verifica conversion [lon, lat] -> [lat, lon]
    assert result["geometry"][0] == [-33.4569, -70.6483]
    assert result["geometry"][1] == [-33.4500, -70.6400]


async def test_route_between_uses_cycling_regular_for_cycling(ors):
    """El perfil elegido llega a la URL.

    Basta una variante: que cada modo tenga su perfil ya lo fija
    test_ors_profile_map_covers_all_modes, sin montar HTTP.
    """
    with respx.mock:
        route = respx.post(f"{ORS_BASE}/v2/directions/cycling-regular/geojson").mock(
            return_value=Response(200, json=make_ors_response([[-70.6, -33.4]], 3000.0, 600.0))
        )
        await ors.route_between((-33.4, -70.6), (-33.4, -70.6), mode="cycling")

    assert route.called


async def test_route_between_sends_correct_coordinate_order(ors):
    """ORS recibe coordenadas en orden [lon, lat]."""
    captured = None

    def capture(request):
        nonlocal captured
        import json
        captured = json.loads(request.content)
        return Response(200, json=make_ors_response([[-70.6, -33.4]], 100.0, 60.0))

    with respx.mock:
        respx.post(f"{ORS_BASE}/v2/directions/foot-walking/geojson").mock(side_effect=capture)
        await ors.route_between(
            start_latlon=(-33.4569, -70.6483),
            end_latlon=(-33.4450, -70.6300),
            mode="walking",
        )

    # ORS espera [lon, lat], no [lat, lon]
    assert captured["coordinates"][0] == [-70.6483, -33.4569]
    assert captured["coordinates"][1] == [-70.6300, -33.4450]


async def test_route_between_raises_on_ors_error(ors):
    """route_between propaga HTTPStatusError cuando ORS falla."""
    import httpx

    with respx.mock:
        respx.post(f"{ORS_BASE}/v2/directions/foot-walking/geojson").mock(
            return_value=Response(403, json={"error": {"code": 2003, "message": "API key invalid"}})
        )
        with pytest.raises(httpx.HTTPStatusError):
            await ors.route_between((-33.4, -70.6), (-33.4, -70.6), mode="walking")


def test_ors_profile_map_covers_all_modes():
    """Todos los modos LSG estan mapeados a un perfil ORS."""
    for mode in ("walking", "jogging", "hiking", "cycling"):
        assert mode in ORS_PROFILE
