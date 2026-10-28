"""Endpoints de autenticacion de LSG Routes.

El backend actua como proxy de LSG-Auth: no emite tokens propios.
Los tokens de LSG-Auth se usan directamente en todos los endpoints protegidos.
"""

import logging

import httpx
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.lsg_auth import lsg_auth_client
from app.clients.lsg_core import lsg_core_client
from app.core.security import get_current_user
from app.db.session import get_db
from app.models.user import User
from app.services.user_token import store_token

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/auth", tags=["auth"])
_security = HTTPBearer()


class LoginRequest(BaseModel):
    """Esquema de solicitud de inicio de sesion."""

    email: str
    password: str


class TokenResponse(BaseModel):
    """Esquema de respuesta con token JWT."""

    access_token: str
    token_type: str
    expires_at: str


@router.post("/login", response_model=TokenResponse)
async def login(
    body: LoginRequest,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db),
) -> TokenResponse:
    """Autentica al usuario contra LSG-Auth y registra/actualiza el usuario local.

    El token retornado es el JWT de LSG-Auth y debe usarse en todos los endpoints
    protegidos via header Authorization: Bearer <token>.
    """
    try:
        result = await lsg_auth_client.login(body.email, body.password)
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 401:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"code": "INVALID_CREDENTIALS", "message": "Email o contrasena incorrectos"},
            )
        logger.error("Error en LSG-Auth durante login: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AUTH_SERVICE_ERROR", "message": "Servicio de autenticacion no disponible"},
        )
    except httpx.RequestError as exc:
        logger.error("Error de red en login: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AUTH_SERVICE_ERROR", "message": "Servicio de autenticacion no disponible"},
        )

    player = result["player"]
    stmt = select(User).where(User.id_players == player["id_players"])
    db_result = await db.execute(stmt)
    user = db_result.scalar_one_or_none()

    if user is None:
        user = User(
            id_players=player["id_players"],
            name=player.get("name", ""),
            email=player.get("email", ""),
        )
        db.add(user)
    else:
        user.name = player.get("name", user.name)
        user.email = player.get("email", user.email)

    # Aqui la caducidad viene exacta de LSG-Auth, mejor que leerla del JWT.
    store_token(user, result["access_token"], result.get("expires_at"))
    await db.commit()

    background_tasks.add_task(
        lsg_core_client.ensure_sensor_linked, user.id_players, result["access_token"]
    )

    return TokenResponse(
        access_token=result["access_token"],
        token_type="bearer",
        expires_at=result["expires_at"],
    )


@router.post("/refresh", response_model=TokenResponse)
async def refresh_token(
    credentials: HTTPAuthorizationCredentials = Depends(_security),
    db: AsyncSession = Depends(get_db),
) -> TokenResponse:
    """Renueva el token JWT pasando por LSG-Auth sin re-autenticar."""
    try:
        result = await lsg_auth_client.refresh(credentials.credentials)
    except httpx.HTTPStatusError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "INVALID_TOKEN", "message": "Token invalido o expirado"},
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Actualizar el token guardado; si no, el que use el retry_worker envejece
    # mientras la app renueva el suyo tan campante.
    player_id = (result.get("player") or {}).get("id_players")
    if player_id is not None:
        stmt = select(User).where(User.id_players == player_id)
        user = (await db.execute(stmt)).scalar_one_or_none()
        if user is not None:
            store_token(user, result["access_token"], result.get("expires_at"))
            await db.commit()

    return TokenResponse(
        access_token=result["access_token"],
        token_type="bearer",
        expires_at=result["expires_at"],
    )


@router.get("/me")
async def get_me(current_user: User = Depends(get_current_user)) -> dict:
    """Retorna el perfil del usuario autenticado desde la base de datos local."""
    return {
        "id": current_user.id,
        "id_players": current_user.id_players,
        "name": current_user.name,
        "email": current_user.email,
        "created_at": current_user.created_at,
        "last_sync_at": current_user.last_sync_at,
    }
