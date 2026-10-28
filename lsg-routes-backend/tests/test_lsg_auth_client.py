"""Pruebas unitarias del cliente LSG-Auth."""

import pytest
import respx
from httpx import Response

from app.clients import lsg_auth as lsg_auth_module
from app.clients.lsg_auth import LsgAuthClient

BASE = "https://lsg-auth.test"


@pytest.fixture(autouse=True)
def clear_cache():
    """Limpia el cache de whoami antes de cada prueba."""
    lsg_auth_module._whoami_cache.clear()
    yield
    lsg_auth_module._whoami_cache.clear()


@pytest.fixture
def auth_client():
    return LsgAuthClient(BASE)


async def test_login_returns_token(auth_client):
    """Login exitoso retorna access_token y datos del player."""
    with respx.mock:
        respx.post(f"{BASE}/login").mock(
            return_value=Response(200, json={
                "access_token": "tok_abc",
                "token_type": "bearer",
                "expires_in_seconds": 7200,
                "expires_at": "2099-12-31T23:59:59+00:00",
                "player": {"id_players": 46, "name": "Test", "email": "t@t.com", "roles": ["player"]},
            })
        )
        result = await auth_client.login("t@t.com", "pass")

    assert result["access_token"] == "tok_abc"
    assert result["player"]["id_players"] == 46


async def test_login_invalid_credentials_raises(auth_client):
    """Login con credenciales invalidas lanza HTTPStatusError."""
    import httpx

    with respx.mock:
        respx.post(f"{BASE}/login").mock(return_value=Response(401, json={"detail": "Unauthorized"}))
        with pytest.raises(httpx.HTTPStatusError):
            await auth_client.login("bad@t.com", "wrong")


async def test_whoami_returns_profile(auth_client):
    """whoami con token valido retorna el perfil del usuario."""
    with respx.mock:
        respx.get(f"{BASE}/whoami").mock(
            return_value=Response(200, json={
                "id_players": 46,
                "name": "Test Player",
                "email": "t@t.com",
                "roles": ["player"],
            })
        )
        profile = await auth_client.whoami("valid_token")

    assert profile["id_players"] == 46
    assert profile["name"] == "Test Player"


async def test_whoami_uses_cache_on_second_call(auth_client):
    """La segunda llamada a whoami con el mismo token no hace request HTTP."""
    call_count = 0

    def handler(request):
        nonlocal call_count
        call_count += 1
        return Response(200, json={"id_players": 46, "name": "T", "email": "t@t.com", "roles": []})

    with respx.mock:
        respx.get(f"{BASE}/whoami").mock(side_effect=handler)
        await auth_client.whoami("same_token")
        await auth_client.whoami("same_token")

    assert call_count == 1, "La segunda llamada debe usar el cache"


async def test_whoami_different_tokens_not_cached(auth_client):
    """Tokens distintos no comparten entrada de cache."""
    call_count = 0

    def handler(request):
        nonlocal call_count
        call_count += 1
        return Response(200, json={"id_players": call_count, "name": "U", "email": "u@u.com", "roles": []})

    with respx.mock:
        respx.get(f"{BASE}/whoami").mock(side_effect=handler)
        await auth_client.whoami("token_a")
        await auth_client.whoami("token_b")

    assert call_count == 2


async def test_refresh_returns_new_token(auth_client):
    """refresh retorna un nuevo token con expires_at actualizado."""
    with respx.mock:
        respx.post(f"{BASE}/token/refresh").mock(
            return_value=Response(200, json={
                "access_token": "new_tok",
                "token_type": "bearer",
                "expires_in_seconds": 7200,
                "expires_at": "2099-12-31T23:59:59+00:00",
                "player": {"id_players": 46},
            })
        )
        result = await auth_client.refresh("old_tok")

    assert result["access_token"] == "new_tok"

