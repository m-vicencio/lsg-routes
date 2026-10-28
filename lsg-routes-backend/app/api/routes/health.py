"""Endpoints de salud del servicio LSG Routes."""

from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.session import get_db

router = APIRouter(tags=["health"])


@router.get("/health")
async def health_check():
    """Verificacion basica de que el servicio esta en linea."""
    return {"status": "ok"}


@router.get("/health/full")
async def health_check_full(db: AsyncSession = Depends(get_db)):
    """Verificacion completa incluyendo conectividad con la base de datos."""
    try:
        await db.execute(text("SELECT 1"))
        db_status = "ok"
    except Exception as exc:
        db_status = f"error: {exc}"

    overall = "ok" if db_status == "ok" else "degraded"
    return {"status": overall, "checks": {"database": db_status}}
