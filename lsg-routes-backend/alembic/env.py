"""Entorno de Alembic configurado para SQLAlchemy async."""

import asyncio
from logging.config import fileConfig

from alembic import context

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

from app.core.config import settings  # noqa: E402
from app.models import Base  # noqa: E402, F401 - registra todos los modelos via __init__.py

target_metadata = Base.metadata

config.set_main_option(
    "sqlalchemy.url",
    settings.database_url.replace("+asyncpg", ""),
)


def run_migrations_offline() -> None:
    """Ejecuta migraciones en modo offline sin conexion activa."""
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection) -> None:
    """Aplica las migraciones sobre la conexion provista."""
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """Ejecuta migraciones en modo online con soporte async."""
    from app.db.session import engine

    async with engine.connect() as connection:
        await connection.run_sync(do_run_migrations)
    await engine.dispose()


def run_migrations_online() -> None:
    """Punto de entrada para migraciones en modo online."""
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
