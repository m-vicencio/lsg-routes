"""Configuracion compartida de pruebas para LSG Routes.

Usa SQLite en memoria para no requerir PostgreSQL durante los tests.
"""

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import settings
from app.db.session import get_db
from app.main import app
from app.models import Base  # noqa: F401 - registra todos los modelos
from app.models.user import User

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"


def mock_player_sensors() -> list[dict]:
    """Respuesta de GET /sensors/players/{id}: un registro por endpoint activado.

    Se deriva de la configuracion en vez de fijar un id a mano. El codigo resuelve
    el endpoint con `settings.endpoint_id_for(mode)`, asi que un mock que declare
    solo `lsg_routes_sensor_endpoint_id` (el de respaldo) coincide unicamente
    cuando no hay mapa por modo (o sea, en CI, que no tiene .env) y deja sin
    probar el camino que corre en produccion.
    """
    ids = {*settings.configured_endpoint_ids, settings.lsg_routes_sensor_endpoint_id}
    return [
        {"id_players_sensor_endpoint": 100 + eid, "id_sensor_endpoint": eid, "activated": True}
        for eid in sorted(ids)
    ]


@pytest.fixture(autouse=True)
def _force_production_sync_order():
    """Fija el orden de sincronización LSG al de producción (ingest->adjust).

    El contenedor de desarrollo inyecta DEV_ADJUST_BEFORE_INGEST=true en el entorno;
    sin esto, las pruebas heredarían ese valor y validarían el orden equivocado.
    """
    from app.core.config import settings

    original = settings.dev_adjust_before_ingest
    settings.dev_adjust_before_ingest = False
    yield
    settings.dev_adjust_before_ingest = original


@pytest.fixture
async def db_engine():
    """Motor SQLite en memoria con el esquema creado."""
    engine = create_async_engine(TEST_DB_URL, echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest.fixture
async def db_session(db_engine):
    """Sesion de base de datos aislada para cada prueba."""
    factory = async_sessionmaker(db_engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        yield session


@pytest.fixture
async def client(db_session):
    """Cliente HTTP de pruebas con base de datos y cache de tokens limpios."""
    from app.clients import lsg_auth as lsg_auth_module
    lsg_auth_module._whoami_cache.clear()

    async def override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac
    app.dependency_overrides.clear()


#: Token del participante de prueba. get_current_user lo guarda en cada peticion
#: real; las llamadas a LSG-Core en su nombre deben viajar con este, no con el de
#: la cuenta de servicio.
USER_TOKEN = "user-token-46"


@pytest.fixture
async def auth_user(db_session) -> User:
    """Crea y persiste un usuario de prueba en la base de datos."""
    from datetime import datetime, timedelta, timezone

    user = User(
        id_players=46,
        name="Test Player",
        email="test@usach.cl",
        lsg_token=USER_TOKEN,
        lsg_token_expires_at=datetime.now(tz=timezone.utc) + timedelta(hours=2),
    )
    db_session.add(user)
    await db_session.commit()
    await db_session.refresh(user)
    return user


@pytest.fixture
async def auth_client(db_session, auth_user):
    """Cliente HTTP con get_current_user mockeado al auth_user.

    Evita llamadas reales a LSG-Auth en pruebas de endpoints protegidos.
    """
    from app.core.security import get_current_user

    async def override_get_current_user():
        return auth_user

    async def override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[get_current_user] = override_get_current_user

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac

    app.dependency_overrides.clear()
