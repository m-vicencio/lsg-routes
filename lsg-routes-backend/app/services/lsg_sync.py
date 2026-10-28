"""Orquestador de sincronizacion de sesiones completadas con LSG-Core.

Flujo:
  1. Obtener/cachear el players_sensor_endpoint_id del endpoint de ese modo.
  2. POST /sensors/ingest/webhook (auditoria del estudio longitudinal).
  3. Si awarded_points > 0: POST /players/{id}/points/adjust con idempotencia.
  4. Persistir LSGSyncRecord con ambos IDs LSG.

Si el paso 2 falla no se acreditan puntos: evento auditable sin puntos > puntos
sin auditoria. Si el paso 3 falla despues del 2, se reintenta via retry_worker
con backoff exponencial.

Frontera de tokens: el ingest y las lecturas van EN NOMBRE del participante
(webhook player-self, para que el estudio lo registre a el); solo el adjust usa
la cuenta de servicio, porque exige rol admin, researcher o developer. Esa
frontera no se cruza.
"""

import logging
from datetime import datetime, timezone

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app import __version__
from app.clients import lsg_service_account
from app.clients.lsg_core import lsg_core_client
from app.core.config import settings
from app.models.route import PlannedRoute
from app.models.session import ActivityLog, LSGSyncRecord, RouteSession
from app.models.user import User
from app.services.user_token import get_user_token
from app.validators.adherence import compute_planned_distance_m

logger = logging.getLogger(__name__)


async def _stored_metrics(session: RouteSession, db: AsyncSession) -> tuple[float, float]:
    """Distancia y adherencia con que /finish sincronizo la sesion, para los reintentos.

    Reproduce el criterio de finish_session: en una ruta planificada viaja la
    distancia planificada; en un recorrido libre, la recorrida, que quedo en
    activity_logs. Sin esto el reintento mandaria 0 km y _do_ingest omitiria el evento.
    """
    log_stmt = select(ActivityLog).where(ActivityLog.session_id == session.id)
    log = (await db.execute(log_stmt)).scalar_one_or_none()
    adherence = (log.adherence_ratio if log else None) or 0.0
    if session.route_id is None:
        return (log.distance_m if log else 0.0), adherence

    route_stmt = select(PlannedRoute).where(PlannedRoute.id == session.route_id)
    route = (await db.execute(route_stmt)).scalar_one_or_none()
    if route is None:
        return 0.0, adherence
    return route.planned_distance_m or compute_planned_distance_m(route.geometry), adherence


async def _resolve_pseid(
    user: User, endpoint_id: int, token: str, db: AsyncSession
) -> int | None:
    """players_sensor_endpoint_id del endpoint dado: cache local o LSG-Core.

    LSG asigna un pseid distinto por endpoint, y hay un endpoint por modo de
    actividad, asi que la cache es un dict y no un escalar.
    """
    cache = user.players_sensor_endpoints or {}
    cached = cache.get(str(endpoint_id))
    if cached is not None:
        return cached

    try:
        sensors = await lsg_core_client.get_player_sensors(user.id_players, token)
    except (httpx.HTTPStatusError, httpx.RequestError) as exc:
        logger.error("No se pudo obtener sensores del jugador %s: %s", user.id_players, exc)
        return None

    for sensor in sensors:
        if sensor.get("id_sensor_endpoint") == endpoint_id and sensor.get("activated"):
            pseid = sensor["id_players_sensor_endpoint"]
            # Reasignar el dict entero: SQLAlchemy no detecta mutaciones in situ en JSON.
            user.players_sensor_endpoints = {**cache, str(endpoint_id): pseid}
            await db.commit()
            return pseid

    logger.warning(
        "Endpoint %s no activado para el jugador %s", endpoint_id, user.id_players
    )
    return None


async def _ledger_already_credited(session_uuid: str, player_id: int, token: str) -> bool:
    """Verifica si el adjust de esta sesion ya fue registrado en el ledger LSG."""
    try:
        ledger = await lsg_core_client.get_points_ledger(
            player_id=player_id,
            token=token,
            source_type="ADJUST",
        )
        return any(session_uuid in str(entry.get("reason", "")) for entry in ledger)
    except (httpx.HTTPStatusError, httpx.RequestError) as exc:
        logger.warning("No se pudo verificar ledger para idempotencia: %s", exc)
        return False


async def _do_ingest(
    session: "RouteSession",
    user: User,
    endpoint_id: int,
    pseid: int,
    raw_payload: dict,
    parsed_value: float,
    sync_record: "LSGSyncRecord",
    user_token: str,
    db: AsyncSession,
) -> "LSGSyncRecord":
    if parsed_value <= 0:
        return sync_record
    # Un reintento tras un adjust fallido no debe registrar el evento dos veces.
    if sync_record.sensor_event_id is not None:
        return sync_record

    try:
        ingest_result = await lsg_core_client.ingest_sensor_event(
            player_id=session.id_players,
            sensor_endpoint_id=endpoint_id,
            players_sensor_endpoint_id=pseid,
            raw_payload=raw_payload,
            parsed_value=parsed_value,
            occurred_at=session.ended_at or datetime.now(tz=timezone.utc),
            token=user_token,
        )
        sync_record.sensor_event_id = ingest_result["id_sensor_ingest_event"]
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 401:
            user.lsg_token = None  # el token del participante murio
        logger.error("Fallo ingest webhook para sesion %s: %s", session.id, exc)
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {"step": "ingest", "error": str(exc)}
        await db.commit()
    except httpx.RequestError as exc:
        logger.error("Fallo ingest webhook para sesion %s: %s", session.id, exc)
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {"step": "ingest", "error": str(exc)}
        await db.commit()
    return sync_record


async def _do_adjust(
    session: "RouteSession",
    awarded_points: int,
    sync_record: "LSGSyncRecord",
    user_token: str,
    db: AsyncSession,
) -> "LSGSyncRecord":
    if awarded_points <= 0:
        return sync_record

    # La cuenta de servicio (rol admin, researcher o developer) acredita en nombre del jugador; sin
    # configurar -> fallback al token del propio usuario. El mismo token consulta
    # el ledger, para que quien verifica la idempotencia sea quien acredita.
    try:
        service_token = await lsg_service_account.get_service_token()
    except (httpx.HTTPStatusError, httpx.RequestError) as exc:
        logger.error("Fallo el login de la cuenta de servicio: %s", exc)
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {"step": "adjust", "error": f"service_login_failed: {exc}"}
        await db.commit()
        return sync_record
    adjust_token = service_token or user_token

    session_uuid = str(session.id)
    if await _ledger_already_credited(session_uuid, session.id_players, adjust_token):
        logger.info("Sesion %s ya acreditada en ledger, omitiendo adjust", session.id)
        return sync_record

    reason = (
        f"lsg-routes route_id={session.route_id or 'free_roam'} "
        f"session_uuid={session_uuid} "
        f"sensor_event_id={sync_record.sensor_event_id}"
    )
    try:
        adjust_result = await lsg_core_client.adjust_points(
            player_id=session.id_players,
            amount=awarded_points,
            reason=reason,
            token=adjust_token,
        )
        sync_record.points_ledger_id = adjust_result.get("source_ref")
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 401 and service_token is not None:
            lsg_service_account.invalidate_service_token()  # forzar re-login
        logger.error("Fallo adjust points para sesion %s: %s", session.id, exc)
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {
            "step": "adjust",
            "sensor_event_id": sync_record.sensor_event_id,
            "error": str(exc),
        }
        await db.commit()
    except httpx.RequestError as exc:
        logger.error("Fallo adjust points para sesion %s: %s", session.id, exc)
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {
            "step": "adjust",
            "sensor_event_id": sync_record.sensor_event_id,
            "error": str(exc),
        }
        await db.commit()
    return sync_record


async def sync_to_lsg(
    session: RouteSession,
    awarded_points: int,
    adherence_ratio: float,
    validators_summary: dict,
    breakdown: dict,
    planned_distance_m: float | None,
    db: AsyncSession,
    user_token: str | None = None,
) -> LSGSyncRecord:
    """Registra la sesion en LSG-Core y acredita los puntos al jugador.

    Args:
        session: Sesion de ruta completada y validada.
        awarded_points: Puntos calculados a acreditar.
        adherence_ratio: Fraccion de la ruta cubierta.
        validators_summary: Resultados de los validadores {name: details}.
        breakdown: Desglose del calculo de puntos.
        planned_distance_m: Distancia planificada en metros. None en los
            reintentos: se reconstruye desde la BD junto con la adherencia.
        db: Sesion de base de datos.
        user_token: Token del participante. Si no se pasa (retry_worker), se
            resuelve del guardado en User.

    Returns:
        LSGSyncRecord creado o actualizado.
    """
    # Evitar registros duplicados por sesion
    stmt = select(LSGSyncRecord).where(LSGSyncRecord.session_id == session.id)
    result = await db.execute(stmt)
    existing = result.scalar_one_or_none()

    sync_record = existing or LSGSyncRecord(
        session_id=session.id,
        awarded_points=awarded_points,
        point_dimension_id=settings.lsg_routes_point_dimension_id,
        sync_status="PENDING",
        attempts=0,
    )
    if not existing:
        db.add(sync_record)

    user_stmt = select(User).where(User.id_players == session.id_players)
    user = (await db.execute(user_stmt)).scalar_one_or_none()

    if user_token is None:
        user_token = await get_user_token(user, db) if user is not None else None
    if user_token is None:
        # Sin token no hay nada que reintentar: no se gasta un intento del
        # presupuesto, o una noche con el token muerto dejaria la sesion varada
        # para siempre. Vuelve a intentarse cuando el participante regrese.
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {"reason": "user_token_expired"}
        await db.commit()
        return sync_record

    sync_record.last_attempt_at = datetime.now(tz=timezone.utc)
    sync_record.attempts = (sync_record.attempts or 0) + 1
    # Reiniciar el estado para este intento; en reintentos el registro reusado
    # conserva LSG_SYNC_FAILED del intento previo y contaminaria los chequeos.
    sync_record.sync_status = "PENDING"
    sync_record.last_error = None

    if user is None:
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {"reason": "user_not_found"}
        await db.commit()
        return sync_record

    # Cada modo viaja por su propio endpoint: es lo unico que un consumidor
    # externo (el mod de BG3) puede leer sin parsear el raw_payload.
    endpoint_id = settings.endpoint_id_for(session.mode)
    pseid = await _resolve_pseid(user, endpoint_id, user_token, db)
    if pseid is None:
        sync_record.sync_status = "LSG_SYNC_FAILED"
        sync_record.last_error = {
            "reason": "players_sensor_endpoint_not_found",
            "endpoint_id": endpoint_id,
        }
        await db.commit()
        return sync_record

    if planned_distance_m is None:
        planned_distance_m, adherence_ratio = await _stored_metrics(session, db)

    distance_km = round(planned_distance_m / 1000, 3)
    raw_payload = {
        "mode": session.mode,
        "distance_km": distance_km,
        "adherence_ratio": adherence_ratio,
        "awarded_points": awarded_points,
        "anti_cheat": validators_summary,
        "breakdown": breakdown,
        "route_id": str(session.route_id) if session.route_id else "free_roam",
        "session_id": str(session.id),
        "app_version": __version__,
    }
    # parsed_value es el escalar comparable que lee el consumidor externo junto al
    # id_sensor_endpoint; para los endpoints de LSG-Routes son km recorridos.
    parsed_value = float(distance_km)

    # dev_adjust_before_ingest invierte el orden solo en desarrollo (ver core/config.py).
    if settings.dev_adjust_before_ingest:
        sync_record = await _do_adjust(session, awarded_points, sync_record, user_token, db)
        if sync_record.sync_status == "LSG_SYNC_FAILED":
            return sync_record
        sync_record = await _do_ingest(
            session, user, endpoint_id, pseid, raw_payload, parsed_value,
            sync_record, user_token, db,
        )
    else:
        sync_record = await _do_ingest(
            session, user, endpoint_id, pseid, raw_payload, parsed_value,
            sync_record, user_token, db,
        )
        if sync_record.sync_status == "LSG_SYNC_FAILED":
            return sync_record
        sync_record = await _do_adjust(session, awarded_points, sync_record, user_token, db)

    # Si la segunda llamada fallo, conservar LSG_SYNC_FAILED (no marcar como sincronizado)
    if sync_record.sync_status == "LSG_SYNC_FAILED":
        return sync_record

    sync_record.sync_status = "SYNCED_TO_LSG"
    sync_record.last_error = None
    await db.commit()
    return sync_record
