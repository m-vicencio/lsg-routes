"""Configuracion de la sesion de base de datos async con SQLAlchemy."""

from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import settings

engine = create_async_engine(settings.database_url, echo=settings.debug)
AsyncSessionLocal = async_sessionmaker(
    engine, class_=AsyncSession, expire_on_commit=False
)


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """Dependencia de FastAPI para obtener una sesion de base de datos."""
    async with AsyncSessionLocal() as session:
        yield session
