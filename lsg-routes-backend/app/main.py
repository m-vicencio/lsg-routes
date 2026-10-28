"""Punto de entrada principal de la aplicacion LSG Routes."""

from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import __version__
from app.api.routes import activity as activity_router
from app.api.routes import app_meta, auth, health
from app.api.routes import routes as routes_router
from app.api.routes import sessions as sessions_router
from app.core.logging import setup_logging

setup_logging()


@asynccontextmanager
async def lifespan(application: FastAPI):
    """Crea tablas faltantes, inicia y detiene el scheduler de reintentos."""
    # Crea solo las tablas que aun no existen (idempotente, no toca las existentes).
    # No agrega columnas a tablas existentes: eso es trabajo de las migraciones de
    # Alembic (ver README, "Base de datos y migraciones").
    from app.db.session import engine
    from app.models import Base
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    from app.services.retry_worker import scheduler
    scheduler.start()
    yield
    scheduler.shutdown()


app = FastAPI(
    title="LSG Routes",
    description=(
        "Sensor de rutas para la plataforma LifeSync-Games. "
        "Permite planificar y validar rutas fisicas del participante, "
        "creditar puntos LSG y sincronizar con LSG-Core."
    ),
    version=__version__,
    lifespan=lifespan,
    # Servir OpenAPI y Swagger bajo el prefijo del servicio
    docs_url="/lsg-routes/docs",
    openapi_url="/lsg-routes/openapi.json",
)

app.include_router(health.router, prefix="/lsg-routes")
app.include_router(auth.router, prefix="/lsg-routes")
app.include_router(routes_router.router, prefix="/lsg-routes")
app.include_router(sessions_router.router, prefix="/lsg-routes")
app.include_router(activity_router.router, prefix="/lsg-routes")
app.include_router(app_meta.router, prefix="/lsg-routes")
