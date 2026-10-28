"""Cliente async para LSG-Core-API.

Contratos verificados contra https://lsg.diinf.usach.cl/lsg-core-api/openapi.json
(v1.2.3); el manual de la plataforma va por detras de ese openapi

Endpoints utilizados:
  POST /sensors/ingest/webhook          -> {status, id_sensor_ingest_event}
  POST /players/{id}/points/adjust      -> {status, source_ref}
  GET  /sensors/players/{id}            -> lista de sensores activos del jugador
  GET  /players/{id}/points/balance     -> saldo por dimension
  GET  /points/ledger                   -> historial de movimientos (para idempotencia)

Todos los metodos reciben el token explicitamente: quien llama decide si va el
token del propio usuario o el de la cuenta de servicio, y esa decision queda
visible en el call site. Ver app/clients/lsg_service_account.py para la frontera.
"""

import logging
from datetime import datetime

import httpx

from app.clients import lsg_service_account
from app.core.config import settings

logger = logging.getLogger(__name__)


class LsgCoreClient:
    """Envoltura async sobre los endpoints de LSG-Core utilizados por LSG-Routes."""

    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")

    async def ingest_sensor_event(
        self,
        player_id: int,
        sensor_endpoint_id: int,
        players_sensor_endpoint_id: int,
        raw_payload: dict,
        parsed_value: float,
        occurred_at: datetime,
        token: str,
    ) -> dict:
        """Registra un evento de sensor en LSG-Core via webhook.

        Args:
            player_id: ID del jugador en LSG.
            sensor_endpoint_id: Endpoint del modo de actividad (settings.endpoint_id_for).
            players_sensor_endpoint_id: ID del enlace jugador-endpoint (de GET /sensors/players/{id}).
            raw_payload: Datos crudos de la ruta para auditoria del estudio longitudinal.
            parsed_value: Escalar comparable del evento (km recorridos).
            occurred_at: Momento UTC en que ocurrio el evento.
            token: JWT del propio usuario. El webhook es player-self: el
                participante ingesta su propia data y asi queda registrada a su nombre.

        Returns:
            {"status": "ok", "id_sensor_ingest_event": <int>}
        """
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/sensors/ingest/webhook",
                json={
                    "player_id": player_id,
                    "sensor_endpoint_id": sensor_endpoint_id,
                    "players_sensor_endpoint_id": players_sensor_endpoint_id,
                    "raw_payload": raw_payload,
                    "parsed_value": parsed_value,
                    "status": "OK",
                    "occurred_at": occurred_at.isoformat(),
                },
                headers={"Authorization": f"Bearer {token}"},
                timeout=30.0,
            )
            response.raise_for_status()
            return response.json()

    async def adjust_points(
        self,
        player_id: int,
        amount: int,
        reason: str,
        token: str,
    ) -> dict:
        """Acredita puntos al jugador via POST /players/{id}/points/adjust.

        Exige rol admin/researcher/developer, asi que va con la cuenta de
        servicio: es la unica llamada del flujo normal que un participante de rol
        `player` no puede hacer por si mismo.

        LSG-Core 1.2.3 marco point_dimension_id como [DEPRECADO] a favor de
        attribute_id (Fisico=2 para rutas); se manda el viejo solo como respaldo
        si el nuevo no esta configurado.

        Args:
            player_id: ID del jugador en LSG.
            amount: Cantidad de puntos (siempre positivo).
            reason: Motivo del ajuste; debe incluir session_uuid para la
                trazabilidad del estudio longitudinal y la idempotencia via ledger.
            token: JWT de la cuenta de servicio.

        Returns:
            {"status": "ok", "source_ref": "ADJUST-<uuid>"}
        """
        body: dict = {"direction": "CREDIT", "amount": amount, "reason": reason}
        if settings.lsg_routes_attribute_id:
            body["attribute_id"] = settings.lsg_routes_attribute_id
        else:
            body["point_dimension_id"] = settings.lsg_routes_point_dimension_id

        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/players/{player_id}/points/adjust",
                json=body,
                headers={"Authorization": f"Bearer {token}"},
                timeout=30.0,
            )
            response.raise_for_status()
            return response.json()

    async def get_player_sensors(self, player_id: int, token: str) -> list:
        """Obtiene los sensores activos del jugador desde LSG-Core.

        El campo id_players_sensor_endpoint es necesario para el webhook de ingest.

        Args:
            player_id: ID del jugador en LSG.
            token: JWT del propio usuario (el endpoint es de lectura para todos los roles).

        Returns:
            Lista de sensores activos con id_players_sensor_endpoint y activated.
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self._base_url}/sensors/players/{player_id}",
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()

    async def link_sensor_to_player(self, player_id: int, sensor_id: int, token: str) -> dict:
        """Vincula el sensor LSG-Routes al jugador (POST /sensors/players/{id}/link).

        Exige rol admin, researcher o developer -> token de la cuenta de servicio.
        """
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/sensors/players/{player_id}/link",
                json={"sensor_id": sensor_id},
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()

    async def link_sensor_endpoint_to_player(
        self, player_id: int, sensor_endpoint_id: int, token: str
    ) -> dict:
        """Activa un endpoint del sensor para el jugador (POST .../link-endpoint).

        Devuelve el id_players_sensor_endpoint que el webhook exige.
        Exige rol admin, researcher o developer -> token de la cuenta de servicio.
        """
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/sensors/players/{player_id}/link-endpoint",
                json={"sensor_endpoint_id": sensor_endpoint_id, "activated": True},
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()

    async def ensure_sensor_linked(self, player_id: int, user_token: str) -> None:
        """Garantiza que el jugador tenga activados todos los endpoints del sensor.

        Llamado como tarea de fondo en cada login; si ya estan activos no hace nada.

        La lectura va con el token del propio usuario, pero el alta (link y
        link-endpoint) exige rol admin, researcher o developer, asi que usa la cuenta de
        servicio. Sin ella configurada cae al token del usuario, que solo
        funciona si esa cuenta tiene uno de esos roles.
        """
        wanted = settings.configured_endpoint_ids
        if not wanted:
            return

        try:
            sensors = await self.get_player_sensors(player_id, user_token)
        except Exception as exc:
            logger.error("Error obteniendo sensores del jugador %s: %s", player_id, exc)
            return

        active = {s.get("id_sensor_endpoint") for s in sensors if s.get("activated")}
        missing = [eid for eid in wanted if eid not in active]
        if not missing:
            return

        try:
            admin_token = await lsg_service_account.get_service_token() or user_token
        except (httpx.HTTPStatusError, httpx.RequestError) as exc:
            logger.error("No se pudo autenticar la cuenta de servicio para el alta: %s", exc)
            return

        try:
            await self.link_sensor_to_player(player_id, settings.lsg_routes_sensor_id, admin_token)
            logger.info("Sensor vinculado al jugador %s", player_id)
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code != 409:
                logger.error("Error vinculando sensor al jugador %s: %s", player_id, exc)
                return
        except httpx.RequestError as exc:
            logger.error("Error de red vinculando sensor al jugador %s: %s", player_id, exc)
            return

        # Un endpoint por modo: si uno falla, los demas deben activarse igual.
        for endpoint_id in missing:
            try:
                await self.link_sensor_endpoint_to_player(player_id, endpoint_id, admin_token)
                logger.info("Endpoint %s activado para jugador %s", endpoint_id, player_id)
            except Exception as exc:
                logger.error(
                    "Error activando endpoint %s para jugador %s: %s", endpoint_id, player_id, exc
                )

    async def get_points_balance(self, player_id: int, token: str) -> list:
        """Obtiene el saldo de puntos del jugador por dimension desde LSG-Core.

        Corresponde a GET /players/{id}/points/balance. El saldo es el perfil
        global del jugador en LSG (todas las dimensiones), no solo lo otorgado
        por el sensor LSG-Routes.

        Args:
            player_id: ID del jugador en LSG.
            token: JWT del propio usuario.

        Returns:
            Lista de {id_players, id_point_dimension, dimension_name, ..., balance}.
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self._base_url}/players/{player_id}/points/balance",
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()

    async def get_points_ledger(
        self,
        player_id: int,
        token: str,
        source_type: str | None = "ADJUST",
        from_ts: str | None = None,
    ) -> list:
        """Consulta el ledger de puntos del jugador.

        Usado tanto para verificar idempotencia (source_type=ADJUST) como para el
        historial de transacciones del perfil (source_type=None -> todos los tipos).

        Args:
            player_id: ID del jugador en LSG.
            token: JWT a usar. Para la verificacion de idempotencia debe ser el
                mismo que hace el adjust, para que quien verifica sea quien acredita.
            source_type: Tipo de movimiento (ADJUST, SENSOR, etc.). None omite el filtro.
            from_ts: Filtrar desde esta fecha (formato YYYY-MM-DD HH:MM:SS).

        Returns:
            Lista de movimientos de puntos.
        """
        params: dict = {"player_id": player_id}
        if source_type is not None:
            params["source_type"] = source_type
        if from_ts:
            params["from_ts"] = from_ts
        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self._base_url}/points/ledger",
                params=params,
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            response.raise_for_status()
            return response.json()


lsg_core_client = LsgCoreClient(settings.lsg_core_base_url)
