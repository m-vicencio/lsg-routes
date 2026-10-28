"""Pruebas del cliente de clima: Open-Meteo + fallback MET Norway + cache + reintentos."""

import httpx
import pytest
import respx
from httpx import Response

from app.clients import weather as weather_module
from app.clients.weather import WeatherClient, _symbol_to_wmo

BASE = "https://open-meteo.test"
MET = "https://met.test"

_OK_BODY = {
    "current": {
        "temperature_2m": 14.2,
        "apparent_temperature": 12.0,
        "relative_humidity_2m": 70,
        "precipitation": 0.0,
        "weather_code": 3,
        "wind_speed_10m": 9.0,
        "is_day": 1,
    }
}

_MET_BODY = {
    "properties": {
        "timeseries": [
            {
                "data": {
                    "instant": {"details": {
                        "air_temperature": 11.5,
                        "relative_humidity": 80.0,
                        "wind_speed": 3.0,  # m/s
                    }},
                    "next_1_hours": {
                        "summary": {"symbol_code": "cloudy"},
                        "details": {"precipitation_amount": 0.2},
                    },
                }
            }
        ]
    }
}


def _client() -> WeatherClient:
    return WeatherClient(BASE, met_no_base_url=MET, user_agent="LSG-Routes/test")


@pytest.fixture(autouse=True)
def _clear_weather_cache():
    weather_module._cache.clear()
    yield
    weather_module._cache.clear()


async def test_open_meteo_success_maps_fields():
    with respx.mock:
        respx.get(f"{BASE}/v1/forecast").mock(return_value=Response(200, json=_OK_BODY))
        out = await _client().current(-33.45, -70.65)
    assert out["temperature_c"] == 14.2
    assert out["weather_code"] == 3
    assert out["is_day"] is True


async def test_open_meteo_retries_on_502_then_succeeds():
    with respx.mock:
        respx.get(f"{BASE}/v1/forecast").mock(
            side_effect=[Response(502), Response(200, json=_OK_BODY)]
        )
        out = await _client().current(-33.45, -70.65)
    assert out["temperature_c"] == 14.2


async def test_falls_back_to_met_no_when_open_meteo_down():
    """Open-Meteo caido -> usa MET Norway y mapea symbol_code a WMO."""
    with respx.mock:
        respx.get(f"{BASE}/v1/forecast").mock(return_value=Response(502))
        respx.get(f"{MET}/weatherapi/locationforecast/2.0/compact").mock(
            return_value=Response(200, json=_MET_BODY)
        )
        out = await _client().current(-33.45, -70.65)
    assert out["temperature_c"] == 11.5
    assert out["humidity_pct"] == 80
    assert out["weather_code"] == 3            # "cloudy" -> overcast (WMO 3)
    assert out["wind_speed_kmh"] == round(3.0 * 3.6, 1)
    assert out["apparent_temperature_c"] is None


async def test_raises_when_both_providers_fail_and_no_cache():
    with respx.mock:
        respx.get(f"{BASE}/v1/forecast").mock(return_value=Response(502))
        respx.get(f"{MET}/weatherapi/locationforecast/2.0/compact").mock(return_value=Response(502))
        with pytest.raises(httpx.RequestError):
            await _client().current(-33.45, -70.65)


async def test_serves_cache_when_both_fail():
    client = _client()
    with respx.mock:
        om = respx.get(f"{BASE}/v1/forecast")
        om.mock(return_value=Response(200, json=_OK_BODY))
        first = await client.current(-33.45, -70.65)
        assert first["temperature_c"] == 14.2

        # Ahora ambos caen: debe devolver el cacheado
        om.mock(return_value=Response(502))
        respx.get(f"{MET}/weatherapi/locationforecast/2.0/compact").mock(return_value=Response(502))
        cached = await client.current(-33.45, -70.65)
        assert cached["temperature_c"] == 14.2


def test_symbol_to_wmo_categories():
    assert _symbol_to_wmo("clearsky_day") == 0
    assert _symbol_to_wmo("fair_night") == 0
    assert _symbol_to_wmo("partlycloudy_day") == 2
    assert _symbol_to_wmo("cloudy") == 3
    assert _symbol_to_wmo("fog") == 45
    assert _symbol_to_wmo("lightrain") == 51
    assert _symbol_to_wmo("rain") == 61
    assert _symbol_to_wmo("heavyrainshowers_day") == 61
    assert _symbol_to_wmo("snow") == 71
    assert _symbol_to_wmo("rainandthunder") == 95
    assert _symbol_to_wmo(None) is None
