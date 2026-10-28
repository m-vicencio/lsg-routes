"""Dependencia de autenticacion para endpoints protegidos de LSG Routes.

El backend no verifica el JWT localmente. Delega la validacion a LSG-Auth
via GET /whoami, con cache de 60 segundos por hash del token.

El token validado queda en `user.lsg_token`: los endpoints que llaman a LSG-Core
en nombre del participante lo leen de ahi en vez de declarar una segunda
dependencia HTTPBearer, y el retry_worker lo usa cuando no hay nadie delante.
"""

import logging

import httpx
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.lsg_auth import lsg_auth_client
from app.db.session import get_db
from app.models.user import User
from app.services.user_token import store_token

logger = logging.getLogger(__name__)
_security = HTTPBearer()


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(_security),
    db: AsyncSession = Depends(get_db),
) -> User:
    """Valida el token Bearer contra LSG-Auth y retorna el usuario local.

    Crea el usuario en la base de datos local si no existe (primer login).

    Args:
        credentials: Credenciales HTTP Bearer extraidas del header Authorization.
        db: Sesion de base de datos.

    Returns:
        Instancia User del jugador autenticado, con `lsg_token` al dia.

    Raises:
        HTTPException 401: Token invalido, expirado o rechazado por LSG-Auth.
        HTTPException 503: LSG-Auth no disponible.
    """
    token = credentials.credentials

    try:
        profile = await lsg_auth_client.whoami(token)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code in (401, 403):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"code": "INVALID_TOKEN", "message": "Token invalido o expirado"},
                headers={"WWW-Authenticate": "Bearer"},
            )
        logger.error("Error al validar token en LSG-Auth: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AUTH_SERVICE_ERROR", "message": "Servicio de autenticacion no disponible"},
        )
    except httpx.RequestError as exc:
        logger.error("Error de red al validar token: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AUTH_SERVICE_ERROR", "message": "Servicio de autenticacion no disponible"},
        )

    id_players: int = profile["id_players"]

    stmt = select(User).where(User.id_players == id_players)
    result = await db.execute(stmt)
    user = result.scalar_one_or_none()

    is_new = user is None
    if is_new:
        user = User(
            id_players=id_players,
            name=profile.get("name", ""),
            email=profile.get("email", ""),
        )
        db.add(user)

    # Se guarda DESPUES de que LSG-Auth lo valide, nunca antes. Solo si cambio:
    # la app consulta a menudo y no hace falta un UPDATE por peticion.
    if is_new or user.lsg_token != token:
        store_token(user, token)
        await db.commit()
        await db.refresh(user)

    return user
