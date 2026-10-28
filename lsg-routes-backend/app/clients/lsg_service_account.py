"""Token de la cuenta de servicio LSG (rol admin, researcher o developer).

A diferencia del token del usuario, aqui SI tenemos la contrasena (variables de
entorno), asi que un token muerto se recupera con re-login: no hace falta
/token/refresh.

Se usa UNICAMENTE donde LSG-Core exige un rol que un participante no tiene:
  POST /players/{id}/points/adjust        (admin, researcher, developer)
  POST /sensors/players/{id}/link         (admin, researcher, developer)
  POST /sensors/players/{id}/link-endpoint (admin, researcher, developer)

Todo lo demas (ingest webhook, lectura de sensores, ledger, saldo) viaja con el
token del propio usuario, para que el estudio longitudinal registre al
participante y no a la cuenta de servicio.
"""

import logging
from datetime import datetime

from app.clients.lsg_auth import lsg_auth_client
from app.core.config import settings
from app.core.timeutil import parse_expires_at, utcnow

logger = logging.getLogger(__name__)

# No hace falta lock: dos logins concurrentes solo cuestan una peticion de mas, y da
# igual cual de las dos escrituras quede en la cache.
_token: str | None = None
_expires_at: datetime | None = None

# Margen bajo el cual el token no aguanta la operacion en curso.
_REFRESH_MARGIN_S = 300


async def get_service_token() -> str | None:
    """Token de la cuenta de servicio, re-logueado si expiro.

    Devuelve None si la cuenta no esta configurada -> el caller cae al token del
    propio usuario, que solo funciona si esa cuenta tiene uno de esos roles.

    Raises:
        httpx.HTTPStatusError / httpx.RequestError: si el login falla (el caller
            lo trata como error transitorio y reintenta).
    """
    global _token, _expires_at
    if not settings.lsg_service_email or not settings.lsg_service_password:
        return None
    if (
        _token is not None
        and _expires_at is not None
        and (_expires_at - utcnow()).total_seconds() >= _REFRESH_MARGIN_S
    ):
        return _token
    result = await lsg_auth_client.login(
        settings.lsg_service_email, settings.lsg_service_password
    )
    _token = result["access_token"]
    _expires_at = parse_expires_at(result["expires_at"])
    logger.info("Token de cuenta de servicio renovado exitosamente")
    return _token


def invalidate_service_token() -> None:
    """Descarta el token cacheado (p. ej. tras un 401) para forzar re-login."""
    global _token, _expires_at
    _token = None
    _expires_at = None
