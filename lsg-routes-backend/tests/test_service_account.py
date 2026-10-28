"""Pruebas del token de la cuenta de servicio LSG."""

from datetime import datetime, timedelta, timezone

import pytest
import respx
from httpx import Response

from app.clients import lsg_service_account
from app.core.config import settings

AUTH_BASE = "https://lsg.diinf.usach.cl/lsg-auth"


@pytest.fixture(autouse=True)
def _clean_cache():
    """El cache es estado de modulo: aislarlo entre pruebas."""
    lsg_service_account.invalidate_service_token()
    yield
    lsg_service_account.invalidate_service_token()


def _login_response(token: str, expires_at: str) -> Response:
    return Response(200, json={
        "access_token": token,
        "token_type": "bearer",
        "expires_in_seconds": 7200,
        "expires_at": expires_at,
        "player": {"id_players": 1, "roles": ["researcher"]},
    })


async def test_returns_none_when_not_configured(monkeypatch):
    """Sin credenciales -> None, y el caller cae al token del usuario."""
    monkeypatch.setattr(settings, "lsg_service_email", "")
    monkeypatch.setattr(settings, "lsg_service_password", "")

    assert await lsg_service_account.get_service_token() is None


async def test_logs_in_once_and_caches(monkeypatch):
    """Un token vivo se reutiliza sin volver a loguear."""
    monkeypatch.setattr(settings, "lsg_service_email", "svc@lsg.cl")
    monkeypatch.setattr(settings, "lsg_service_password", "secret")
    far = (datetime.now(tz=timezone.utc) + timedelta(hours=2)).isoformat()

    with respx.mock:
        route = respx.post(f"{AUTH_BASE}/login").mock(
            return_value=_login_response("svc-vivo", far)
        )
        assert await lsg_service_account.get_service_token() == "svc-vivo"
        assert await lsg_service_account.get_service_token() == "svc-vivo"
        assert route.call_count == 1


async def test_relogins_when_expiring(monkeypatch):
    """Con menos de 5 min de vida se re-loguea en vez de arriesgar la operacion."""
    monkeypatch.setattr(settings, "lsg_service_email", "svc@lsg.cl")
    monkeypatch.setattr(settings, "lsg_service_password", "secret")
    soon = (datetime.now(tz=timezone.utc) + timedelta(minutes=4)).isoformat()
    far = (datetime.now(tz=timezone.utc) + timedelta(hours=2)).isoformat()

    with respx.mock:
        respx.post(f"{AUTH_BASE}/login").mock(
            side_effect=[_login_response("svc-corto", soon), _login_response("svc-largo", far)]
        )
        assert await lsg_service_account.get_service_token() == "svc-corto"
        assert await lsg_service_account.get_service_token() == "svc-largo"


async def test_invalidate_forces_relogin(monkeypatch):
    """Tras un 401 se descarta el cache y el siguiente uso re-loguea."""
    monkeypatch.setattr(settings, "lsg_service_email", "svc@lsg.cl")
    monkeypatch.setattr(settings, "lsg_service_password", "secret")
    far = (datetime.now(tz=timezone.utc) + timedelta(hours=2)).isoformat()

    with respx.mock:
        route = respx.post(f"{AUTH_BASE}/login").mock(
            side_effect=[_login_response("svc-1", far), _login_response("svc-2", far)]
        )
        assert await lsg_service_account.get_service_token() == "svc-1"
        lsg_service_account.invalidate_service_token()
        assert await lsg_service_account.get_service_token() == "svc-2"
        assert route.call_count == 2


async def test_naive_expires_at_does_not_crash(monkeypatch):
    """LSG-Auth no garantiza offset en expires_at; un naive se trata como UTC."""
    monkeypatch.setattr(settings, "lsg_service_email", "svc@lsg.cl")
    monkeypatch.setattr(settings, "lsg_service_password", "secret")
    naive = (datetime.now(tz=timezone.utc) + timedelta(hours=2)).replace(tzinfo=None).isoformat()

    with respx.mock:
        respx.post(f"{AUTH_BASE}/login").mock(return_value=_login_response("svc-naive", naive))
        assert await lsg_service_account.get_service_token() == "svc-naive"
        # La segunda llamada compara con utcnow(): sin normalizar lanzaria TypeError.
        assert await lsg_service_account.get_service_token() == "svc-naive"
