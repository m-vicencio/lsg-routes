"""Pruebas de integracion para los endpoints de autenticacion de LSG Routes."""

import pytest
import respx
from httpx import Response
from sqlalchemy import select

from app.clients import lsg_auth as lsg_auth_module
from app.core.config import settings
from app.models.user import User

LSG_AUTH = settings.lsg_auth_base_url.rstrip("/")

MOCK_LOGIN_RESPONSE = {
    "access_token": "test_jwt_token",
    "token_type": "bearer",
    "expires_in_seconds": 7200,
    "expires_at": "2099-12-31T23:59:59+00:00",
    "player": {
        "id_players": 46,
        "name": "Test Player",
        "email": "test@usach.cl",
        "roles": ["player"],
    },
}

MOCK_WHOAMI_RESPONSE = {
    "id_players": 46,
    "name": "Test Player",
    "email": "test@usach.cl",
    "roles": ["player"],
}


async def test_login_returns_token(client):
    """POST /auth/login retorna el token JWT de LSG-Auth."""
    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json=MOCK_LOGIN_RESPONSE))
        response = await client.post(
            "/lsg-routes/auth/login",
            json={"email": "test@usach.cl", "password": "secret123"},
        )

    assert response.status_code == 200
    data = response.json()
    assert data["access_token"] == "test_jwt_token"
    assert data["token_type"] == "bearer"
    assert "expires_at" in data


async def test_login_creates_user_in_db(client, db_session):
    """POST /auth/login crea el usuario local si no existe."""
    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json=MOCK_LOGIN_RESPONSE))
        await client.post(
            "/lsg-routes/auth/login",
            json={"email": "test@usach.cl", "password": "secret123"},
        )

    result = await db_session.execute(select(User).where(User.id_players == 46))
    user = result.scalar_one_or_none()
    assert user is not None
    assert user.name == "Test Player"
    assert user.email == "test@usach.cl"


async def test_login_updates_existing_user(client, db_session):
    """POST /auth/login actualiza nombre/email si el usuario ya existe."""
    user = User(id_players=46, name="Nombre Antiguo", email="viejo@usach.cl")
    db_session.add(user)
    await db_session.commit()

    updated_response = {**MOCK_LOGIN_RESPONSE}
    updated_response["player"] = {**MOCK_LOGIN_RESPONSE["player"], "name": "Nombre Nuevo"}

    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(return_value=Response(200, json=updated_response))
        await client.post(
            "/lsg-routes/auth/login",
            json={"email": "test@usach.cl", "password": "secret123"},
        )

    await db_session.refresh(user)
    assert user.name == "Nombre Nuevo"


async def test_login_invalid_credentials_returns_401(client):
    """POST /auth/login retorna 401 con codigo INVALID_CREDENTIALS."""
    with respx.mock:
        respx.post(f"{LSG_AUTH}/login").mock(
            return_value=Response(401, json={"detail": "Invalid credentials"})
        )
        response = await client.post(
            "/lsg-routes/auth/login",
            json={"email": "bad@usach.cl", "password": "wrong"},
        )

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "INVALID_CREDENTIALS"


async def test_get_me_with_valid_token(client, db_session):
    """GET /auth/me retorna el perfil del usuario autenticado."""
    user = User(id_players=46, name="Test Player", email="test@usach.cl")
    db_session.add(user)
    await db_session.commit()

    with respx.mock:
        respx.get(f"{LSG_AUTH}/whoami").mock(return_value=Response(200, json=MOCK_WHOAMI_RESPONSE))
        response = await client.get(
            "/lsg-routes/auth/me",
            headers={"Authorization": "Bearer test_jwt_token"},
        )

    assert response.status_code == 200
    data = response.json()
    assert data["id_players"] == 46
    assert data["name"] == "Test Player"
    assert data["email"] == "test@usach.cl"


async def test_get_me_with_invalid_token_returns_401(client):
    """GET /auth/me con token invalido retorna 401."""
    with respx.mock:
        respx.get(f"{LSG_AUTH}/whoami").mock(
            return_value=Response(401, json={"detail": "Token expired"})
        )
        response = await client.get(
            "/lsg-routes/auth/me",
            headers={"Authorization": "Bearer invalid_or_expired_token"},
        )

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "INVALID_TOKEN"


async def test_get_me_creates_user_if_missing(client, db_session):
    """GET /auth/me crea el usuario local si el token es valido pero no existe en DB."""
    with respx.mock:
        respx.get(f"{LSG_AUTH}/whoami").mock(return_value=Response(200, json=MOCK_WHOAMI_RESPONSE))
        response = await client.get(
            "/lsg-routes/auth/me",
            headers={"Authorization": "Bearer fresh_token"},
        )

    assert response.status_code == 200
    result = await db_session.execute(select(User).where(User.id_players == 46))
    user = result.scalar_one_or_none()
    assert user is not None


async def test_refresh_token(client):
    """POST /auth/refresh retorna el nuevo token."""
    with respx.mock:
        respx.post(f"{LSG_AUTH}/token/refresh").mock(
            return_value=Response(200, json={
                "access_token": "renewed_token",
                "token_type": "bearer",
                "expires_in_seconds": 7200,
                "expires_at": "2099-12-31T23:59:59+00:00",
                "player": {"id_players": 46},
            })
        )
        response = await client.post(
            "/lsg-routes/auth/refresh",
            headers={"Authorization": "Bearer old_token"},
        )

    assert response.status_code == 200
    assert response.json()["access_token"] == "renewed_token"
