"""Ciclo de vida del token LSG de cada participante, en un solo sitio.

Orden de resolucion, de barato a caro:
  1. Token sano (>5 min de vida) -> usarlo tal cual.
  2. Por expirar pero vivo       -> POST /token/refresh (deslizarlo).
  3. Muerto                      -> None; el caller no sincroniza todavia.

LSG-Auth no emite refresh token durable: /token/refresh exige el Bearer todavia
vivo. Cuando muere del todo no hay recuperacion automatica posible sin guardar la
contrasena, y aqui no se guarda: en el camino normal el participante esta
presente (POST /sessions/{id}/finish reintenta el sync), asi que la siguiente
peticion suya trae un token fresco.
"""

import logging

import httpx
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.lsg_auth import lsg_auth_client
from app.core.timeutil import as_aware, parse_expires_at, token_expiry, utcnow
from app.models.user import User

logger = logging.getLogger(__name__)

# Margen bajo el cual el token no aguanta la operacion en curso.
_REFRESH_MARGIN_S = 300


def store_token(user: User, token: str, expires_at: str | None = None) -> None:
    """Guarda el token del usuario y su caducidad.

    Args:
        expires_at: El valor exacto de LSG-Auth cuando se tiene (login/refresh).
            Si no, se lee el claim `exp` del propio JWT.
    """
    user.lsg_token = token
    user.lsg_token_expires_at = (
        parse_expires_at(expires_at) if expires_at else token_expiry(token)
    )


async def get_user_token(user: User, db: AsyncSession) -> str | None:
    """Token utilizable del participante, o None si hay que esperar a que vuelva.

    Nunca lanza por errores de LSG: el caller trata None como "no se puede
    sincronizar todavia".

    No se bloquea por usuario: con un solo worker de uvicorn (el del compose)
    la carrera entre el scheduler y una peticion en vuelo solo cuesta un
    /token/refresh de mas. Con varios workers haria falta SELECT ... FOR UPDATE.
    """
    if user.lsg_token is None:
        return None

    expires_at = as_aware(user.lsg_token_expires_at)
    now = utcnow()

    if expires_at is not None:
        if (expires_at - now).total_seconds() >= _REFRESH_MARGIN_S:
            return user.lsg_token
        if expires_at <= now:
            user.lsg_token = None
            await db.commit()
            return None

    # Por expirar (o sin caducidad conocida): deslizarlo es mas barato que nada.
    try:
        result = await lsg_auth_client.refresh(user.lsg_token)
    except httpx.RequestError:
        # LSG inalcanzable: el token puede seguir siendo valido, no lo tiremos.
        return user.lsg_token
    except httpx.HTTPStatusError:
        logger.info("Token de %s rechazado por LSG-Auth; se descarta", user.id_players)
        user.lsg_token = None
        await db.commit()
        return None

    store_token(user, result["access_token"], result.get("expires_at"))
    await db.commit()
    return user.lsg_token
