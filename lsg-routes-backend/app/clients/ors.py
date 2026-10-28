"""Cliente async para OpenRouteService (calculo de rutas A->B).

Endpoint utilizado: POST /v2/directions/{profile}/geojson
Documentacion: https://openrouteservice.org/dev/#/api-docs/v2/directions/{profile}/geojson/post

Mapeo de modos LSG a perfiles ORS:
  walking  -> foot-walking
  jogging  -> foot-walking  (ORS no diferencia trote de caminata)
  hiking   -> foot-hiking
  cycling  -> cycling-regular
"""

import logging

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)

# Mapeo de modo LSG a perfil ORS
ORS_PROFILE: dict[str, str] = {
    "walking": "foot-walking",
    "jogging": "foot-walking",
    "hiking": "foot-hiking",
    "cycling": "cycling-regular",
}


class OrsClient:
    """Envoltura async sobre la API de direcciones de OpenRouteService."""

    def __init__(self, base_url: str, api_key: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key

    async def route_between(
        self,
        start_latlon: tuple[float, float],
        end_latlon: tuple[float, float],
        mode: str,
    ) -> dict:
        """Calcula la ruta optima entre dos puntos.

        Args:
            start_latlon: Coordenadas de inicio (lat, lon).
            end_latlon: Coordenadas de destino (lat, lon).
            mode: Modo de actividad LSG (walking, jogging, hiking, cycling).

        Returns:
            dict con:
              geometry    - lista [[lat, lon], ...] de la ruta
              distance_m  - distancia total en metros
              duration_s  - duracion estimada en segundos

        Raises:
            httpx.HTTPStatusError: Si ORS retorna error (clave invalida, limite, etc.).
            httpx.RequestError: Si ORS no es accesible.
        """
        profile = ORS_PROFILE.get(mode, "foot-walking")
        # ORS usa orden [lon, lat]
        coordinates = [
            [start_latlon[1], start_latlon[0]],
            [end_latlon[1], end_latlon[0]],
        ]

        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/v2/directions/{profile}/geojson",
                json={"coordinates": coordinates},
                headers={"Authorization": self._api_key},
                timeout=30.0,
            )
            response.raise_for_status()
            data = response.json()

        feature = data["features"][0]
        # Convertir [lon, lat] de ORS a [lat, lon] para almacenamiento interno
        geometry = [
            [coord[1], coord[0]]
            for coord in feature["geometry"]["coordinates"]
        ]
        summary = feature["properties"]["summary"]

        return {
            "geometry": geometry,
            "distance_m": summary["distance"],
            "duration_s": int(summary["duration"]),
        }


ors_client = OrsClient(settings.ors_base_url, settings.ors_api_key)
