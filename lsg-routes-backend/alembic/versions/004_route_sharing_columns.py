"""Columnas de compartir rutas (prototipo 0.3) en planned_routes.

Hasta ahora no tenian migracion: las agregaba un ALTER TABLE ... IF NOT EXISTS en el
arranque de la app (app/main.py), que se retiro. Las bases donde ese ALTER ya corrio
tienen las columnas pero no el indice de is_public; por eso cada paso comprueba antes.

Revision ID: 004
Revises: 003
Create Date: 2026-10-14

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "004"
down_revision: Union[str, None] = "003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

_INDEX = "ix_planned_routes_is_public"


def upgrade() -> None:
    inspector = sa.inspect(op.get_bind())
    columns = {c["name"] for c in inspector.get_columns("planned_routes")}

    if "is_public" not in columns:
        op.add_column(
            "planned_routes",
            sa.Column("is_public", sa.Boolean(), nullable=False, server_default=sa.false()),
        )
    if "copied_from_id" not in columns:
        op.add_column("planned_routes", sa.Column("copied_from_id", sa.Uuid(), nullable=True))
    if "shared_by_name" not in columns:
        op.add_column("planned_routes", sa.Column("shared_by_name", sa.String(255), nullable=True))

    if _INDEX not in {i["name"] for i in inspector.get_indexes("planned_routes")}:
        op.create_index(_INDEX, "planned_routes", ["is_public"])


def downgrade() -> None:
    op.drop_index(_INDEX, table_name="planned_routes")
    op.drop_column("planned_routes", "shared_by_name")
    op.drop_column("planned_routes", "copied_from_id")
    op.drop_column("planned_routes", "is_public")
