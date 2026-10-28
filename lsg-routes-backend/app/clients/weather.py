"""Cliente async de clima actual por lat/lon, sin API key.

Fuente primaria: Open-Meteo (https://open-meteo.com/en/docs), que devuelve codigos WMO.
Respaldo: MET Norway / Yr (https://api.met.no), usado cuando Open-Meteo no responde
desde esta red. MET Norway exige un User-Agent descriptivo y usa `symbol_code` en vez
de codigos WMO, que mapeamos a la misma categoria WMO para no cambiar la app.

Encima de ambos hay un cache en memoria (2 h) por coordenada: si todo falla, se sirve
el ultimo clima exitoso en vez de ocultar la tarjeta.
"""

import asyncio
import logging
import time

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)

# Variables "current" de Open-Meteo
_CURRENT_VARS = (
    "temperature_2m,apparent_temperature,relative_humidity_2m,"
    "precipitation,weather_code,wind_speed_10m,is_day"
)

# Reintentos ante 5xx/timeout intermitentes
_MAX_ATTEMPTS = 3
_RETRY_BASE_DELAY_S = 0.4

# Cache de ultimo clima exitoso por coordenada (redondeada ~1km)
_CACHE_TTL_S = 2 * 60 * 60
_cache: dict[tuple[float, float], tuple[float, dict]] = {}


def _symbol_to_wmo(symbol: str | None) -> int | None:
    """Mapea un symbol_code de MET Norway a un codigo WMO de la misma categoria.

    Las categorias coinciden con las que la app agrupa para emoji/etiqueta
    (despejado/nublado/lluvia/nieve/niebla/tormenta).
    """
    if not symbol:
        return None
    s = symbol.split("_")[0].lower()  # quita sufijo _day/_night
    if "thunder" in s:
        return 95   # tormenta
    if "snow" in s:
        return 71   # nieve
    if "sleet" in s:
        return 66   # aguanieve -> categoria lluvia
    if "fog" in s:
        return 45   # niebla
    if "rain" in s or "drizzle" in s:
        return 51 if "light" in s else 61  # llovizna / lluvia
    if "partlycloudy" in s:
        return 2    # parcialmente nublado
    if "cloudy" in s:
        return 3    # nublado
    if "clear" in s or "fair" in s:
        return 0    # despejado
    return None


class WeatherClient:
    """Clima actual con Open-Meteo como fuente primaria y MET Norway como fallback."""

    def __init__(
        self,
        base_url: str,
        met_no_base_url: str = "https://api.met.no",
        user_agent: str = "LSG-Routes/1.0",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._met_base_url = met_no_base_url.rstrip("/")
        self._user_agent = user_agent

    async def current(self, lat: float, lon: float) -> dict:
        """Clima actual en (lat, lon).

        Intenta Open-Meteo; si falla, MET Norway; si ambos fallan, sirve el cache
        reciente. Solo lanza si no hay ningun dato disponible.

        Returns:
            dict con temperatura, sensacion termica, humedad, precipitacion, codigo
            WMO, velocidad del viento (km/h) y si es de dia.

        Raises:
            httpx.RequestError: Si ambos proveedores fallan y no hay cache.
        """
        key = (round(lat, 2), round(lon, 2))
        async with httpx.AsyncClient() as client:
            result = await self._fetch_open_meteo(client, lat, lon)
            if result is None:
                logger.warning("Open-Meteo no disponible; intentando MET Norway")
                result = await self._fetch_met_no(client, lat, lon)

        if result is None:
            cached = _cache.get(key)
            if cached is not None and (time.monotonic() - cached[0]) < _CACHE_TTL_S:
                logger.warning("Clima en vivo no disponible; usando cache para %s", key)
                return cached[1]
            raise httpx.RequestError("Clima no disponible (Open-Meteo y MET Norway fallaron)")

        _cache[key] = (time.monotonic(), result)
        return result

    async def _get_json_with_retry(
        self, client: httpx.AsyncClient, url: str, params: dict, headers: dict
    ) -> dict | None:
        """GET con reintentos. Devuelve el JSON o None si no se obtuvo respuesta valida."""
        for attempt in range(_MAX_ATTEMPTS):
            try:
                response = await client.get(url, params=params, headers=headers, timeout=8.0)
                if response.status_code < 400:
                    return response.json()
                if response.status_code < 500:
                    return None  # 4xx: reintentar no ayuda
            except httpx.RequestError:
                pass
            if attempt < _MAX_ATTEMPTS - 1:
                await asyncio.sleep(_RETRY_BASE_DELAY_S * (attempt + 1))
        return None

    async def _fetch_open_meteo(self, client: httpx.AsyncClient, lat: float, lon: float) -> dict | None:
        data = await self._get_json_with_retry(
            client,
            f"{self._base_url}/v1/forecast",
            {
                "latitude": lat,
                "longitude": lon,
                "current": _CURRENT_VARS,
                "wind_speed_unit": "kmh",
                "timezone": "auto",
            },
            {},
        )
        if not data:
            return None
        cur = data.get("current", {})
        return {
            "temperature_c": cur.get("temperature_2m"),
            "apparent_temperature_c": cur.get("apparent_temperature"),
            "humidity_pct": cur.get("relative_humidity_2m"),
            "precipitation_mm": cur.get("precipitation"),
            "weather_code": cur.get("weather_code"),
            "wind_speed_kmh": cur.get("wind_speed_10m"),
            "is_day": bool(cur.get("is_day", 1)),
        }

    async def _fetch_met_no(self, client: httpx.AsyncClient, lat: float, lon: float) -> dict | None:
        data = await self._get_json_with_retry(
            client,
            f"{self._met_base_url}/weatherapi/locationforecast/2.0/compact",
            {"lat": lat, "lon": lon},
            {"User-Agent": self._user_agent},
        )
        if not data:
            return None
        try:
            ts = data["properties"]["timeseries"][0]["data"]
        except (KeyError, IndexError, TypeError):
            return None

        instant = ts.get("instant", {}).get("details", {})
        nxt = ts.get("next_1_hours") or ts.get("next_6_hours") or {}
        symbol = (nxt.get("summary") or {}).get("symbol_code")
        precip = (nxt.get("details") or {}).get("precipitation_amount")
        wind_ms = instant.get("wind_speed")
        humidity = instant.get("relative_humidity")

        return {
            "temperature_c": instant.get("air_temperature"),
            "apparent_temperature_c": None,  # compact no la trae
            "humidity_pct": round(humidity) if humidity is not None else None,
            "precipitation_mm": precip,
            "weather_code": _symbol_to_wmo(symbol),
            "wind_speed_kmh": round(wind_ms * 3.6, 1) if wind_ms is not None else None,
            "is_day": not str(symbol or "").endswith("_night"),
        }


weather_client = WeatherClient(
    settings.weather_base_url,
    settings.met_no_base_url,
    settings.weather_user_agent,
)
