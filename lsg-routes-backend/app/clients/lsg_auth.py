"""Cliente async para LSG-Auth (emisor JWT del ecosistema LifeSync-Games).

Contratos verificados contra https://lsg.diinf.usach.cl/lsg-auth/openapi.json (v1.2.0.1)

Endpoints utilizados:
  POST /login           -> {access_token, token_type, expires_in_seconds, expires_at, player}
  GET  /whoami          -> perfil del portador del token
  POST /token/refresh   -> token renovado (misma estructura que login)

El token de la cuenta de servicio vive en app/clients/lsg_service_account.py.
"""

import hashlib
from datetime import datetime, timedelta, timezone

import httpx

from app.core.config import settings

# Cache en memoria: sha256(token) -> (profile_dict, expiry_utc)
_whoami_cache: dict[str, tuple[dict, datetime]] = {}
_CACHE_TTL_SECONDS = 60


class LsgAuthClient:
    """Envoltura async sobre la API de autenticacion LSG-Auth."""

    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")

    async def login(self, username: str, password: str) -> dict:
        """Autentica con email y contrasena.

        Args:
            username: Email del usuario.
            password: Contrasena del usuario.

        Returns:
            Payload completo: access_token, expires_at, player, etc.

        Raises:
            httpx.HTTPStatusError: Si las credenciales son invalidas (401).
        """
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/login",
                data={"username": username, "password": password},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()

    async def refresh(self, token: str) -> dict:
        """Renueva un token JWT sin re-autenticar.

        Args:
            token: Token JWT vigente.

        Returns:
            Nuevo payload de token con access_token y expires_at actualizados.
        """
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/token/refresh",
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()

    async def whoami(self, token: str) -> dict:
        """Retorna el perfil del portador del token, con cache de 60 segundos.

        Args:
            token: Token JWT del usuario.

        Returns:
            Perfil: id_players, name, email, roles.

        Raises:
            httpx.HTTPStatusError: Si el token es invalido o expiro (401/403).
        """
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        now = datetime.now(tz=timezone.utc)

        if token_hash in _whoami_cache:
            profile, expires_at = _whoami_cache[token_hash]
            if now < expires_at:
                return profile

        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self._base_url}/whoami",
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            profile = response.json()

        _whoami_cache[token_hash] = (profile, now + timedelta(seconds=_CACHE_TTL_SECONDS))
        return profile


# Instancia singleton utilizada por toda la aplicacion
lsg_auth_client = LsgAuthClient(settings.lsg_auth_base_url)
