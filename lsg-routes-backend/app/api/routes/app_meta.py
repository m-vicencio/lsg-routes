"""Metadatos de la aplicacion: version del APK disponible para actualizacion sideload."""

from fastapi import APIRouter

from app.core.config import settings

router = APIRouter(prefix="/app", tags=["app"])


@router.get("/version")
async def get_app_version() -> dict:
    """Retorna la version mas reciente del APK y su URL de descarga.

    La app movil consulta este endpoint al iniciar para detectar actualizaciones.
    Requiere configuracion via variables de entorno en produccion.

    Viaja tambien la bandera de bicicleta: es la unica llamada que la app ya hace al
    arrancar y sin token, asi que evita un endpoint nuevo solo para un booleano.
    """
    return {
        "latest": settings.app_latest_version,
        "url": settings.app_download_url,
        "sha256": settings.app_sha256,
        "minimum_required": settings.app_minimum_version,
        "bike_activate": settings.bike_activate,
    }
