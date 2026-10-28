"""Worker de reintento para sincronizaciones fallidas con LSG-Core.

Ejecuta cada 60 segundos via APScheduler y reintenta LSGSyncRecord con
status=LSG_SYNC_FAILED usando backoff exponencial (max 6 intentos).
"""

import logging
from datetime import datetime, timedelta, timezone

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.core.timeutil import as_aware

logger = logging.getLogger(__name__)
scheduler = AsyncIOScheduler(timezone="UTC")

_MAX_ATTEMPTS = 6
_BASE_DELAY_S = 60


async def retry_failed_syncs() -> None:
    """Busca sincronizaciones fallidas y las reintenta con backoff exponencial."""
    from sqlalchemy import select

    from app.db.session import AsyncSessionLocal
    from app.models.session import LSGSyncRecord, RouteSession
    from app.models.user import User
    from app.services import lsg_sync
    from app.services.user_token import get_user_token

    async with AsyncSessionLocal() as db:
        stmt = select(LSGSyncRecord).where(
            LSGSyncRecord.sync_status == "LSG_SYNC_FAILED",
            LSGSyncRecord.attempts < _MAX_ATTEMPTS,
        )
        result = await db.execute(stmt)
        records = result.scalars().all()

        for record in records:
            # Backoff exponencial: esperar 2^(attempts-1) * BASE_DELAY_S entre reintentos
            last_attempt_at = as_aware(record.last_attempt_at)
            if last_attempt_at:
                backoff_s = _BASE_DELAY_S * (2 ** (record.attempts - 1))
                next_retry_at = last_attempt_at + timedelta(seconds=backoff_s)
                if datetime.now(tz=timezone.utc) < next_retry_at:
                    continue

            session_stmt = select(RouteSession).where(RouteSession.id == record.session_id)
            session_result = await db.execute(session_stmt)
            session = session_result.scalar_one_or_none()

            if session is None:
                logger.warning("Sesion %s no encontrada para reintento", record.session_id)
                continue

            # El ingest va en nombre del participante, asi que sin token suyo no
            # hay nada que reintentar. Se salta en silencio (sin gastar intento):
            # POST /sessions/{id}/finish reintenta cuando el usuario vuelva.
            user_stmt = select(User).where(User.id_players == session.id_players)
            user = (await db.execute(user_stmt)).scalar_one_or_none()
            user_token = await get_user_token(user, db) if user is not None else None
            if user_token is None:
                logger.debug(
                    "Sin token vigente del jugador %s; sync de %s aplazado",
                    session.id_players, record.session_id,
                )
                continue

            logger.info(
                "Reintentando sync sesion %s (intento %s/%s)",
                record.session_id, record.attempts + 1, _MAX_ATTEMPTS,
            )

            try:
                await lsg_sync.sync_to_lsg(
                    session=session,
                    awarded_points=record.awarded_points or 0,
                    adherence_ratio=0.0,
                    validators_summary={"retry": True},
                    breakdown={"retry": True},
                    planned_distance_m=None,  # se reconstruye desde la BD
                    db=db,
                    user_token=user_token,
                )
            except Exception as exc:
                logger.error("Error en reintento de sync %s: %s", record.session_id, exc)


scheduler.add_job(retry_failed_syncs, "interval", seconds=60, id="retry_failed_syncs")
