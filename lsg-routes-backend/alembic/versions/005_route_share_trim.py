"""Recorte de extremos al compartir una ruta (hide_start_m / hide_end_m).

Las rutas que ya eran publicas quedan sin recorte (0/0); el dueno decide si lo agrega.

Revision ID: 005
Revises: 004
Create Date: 2026-10-23

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "005"
down_revision: Union[str, None] = "004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

_COLUMNS = ("hide_start_m", "hide_end_m")


def upgrade() -> None:
    existing = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("planned_routes")}
    for name in _COLUMNS:
        if name not in existing:
            op.add_column(
                "planned_routes",
                sa.Column(name, sa.Integer(), nullable=False, server_default="0"),
            )


def downgrade() -> None:
    for name in _COLUMNS:
        op.drop_column("planned_routes", name)
