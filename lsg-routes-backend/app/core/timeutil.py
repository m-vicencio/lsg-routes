"""Helpers de tiempo. Todo lo que se compare debe ser timezone-aware."""

import base64
import binascii
import json
from datetime import datetime, timezone


def utcnow() -> datetime:
    return datetime.now(tz=timezone.utc)


def as_aware(value: datetime | None) -> datetime | None:
    """Interpreta como UTC un datetime naive.

    LSG-Auth devuelve `expires_at` en ISO, pero no garantiza el offset. Un naive
    colandose hasta una comparacion con `utcnow()` lanza TypeError y tumba la
    ruta entera, asi que se normaliza al entrar.
    """
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def parse_expires_at(raw: str) -> datetime:
    """`expires_at` de LSG-Auth -> datetime aware."""
    return as_aware(datetime.fromisoformat(raw))


def token_expiry(token: str) -> datetime | None:
    """Caducidad del claim `exp` de un JWT, sin verificar la firma.

    Solo se usa para saber cuando caduca el token que cacheamos: LSG-Auth ya lo
    valido via /whoami antes de llegar aqui, asi que leer el claim no es una
    decision de seguridad y evita gastar una llamada HTTP extra por peticion.

    Returns:
        La caducidad en UTC, o None si el token no es un JWT legible.
    """
    try:
        payload_b64 = token.split(".")[1]
        # base64url sin padding: JWT lo omite, b64decode lo exige.
        padded = payload_b64 + "=" * (-len(payload_b64) % 4)
        exp = json.loads(base64.urlsafe_b64decode(padded))["exp"]
        return datetime.fromtimestamp(int(exp), tz=timezone.utc)
    except (IndexError, KeyError, ValueError, TypeError, binascii.Error, UnicodeDecodeError):
        return None
