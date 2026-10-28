"""Recorrido libre: route_id pasa a ser opcional en sesiones y logs de actividad.

Una sesion sin route_id es un recorrido libre (sin ruta planificada, sin
adherencia, con puntos por tramos segun la velocidad observada). No hay datos
que migrar: las sesiones existentes ya tienen su route_id.

Revision ID: 003
Revises: 002
Create Date: 2026-08-02

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "003"
down_revision: Union[str, None] = "002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.alter_column("route_sessions", "route_id", existing_type=sa.Uuid(), nullable=True)
    # activity_logs nacio con create_all al arrancar (prototipo 0.2) y ninguna migracion
    # anterior la crea: en una base migrada solo con Alembic todavia no existe.
    # Donde ya existe se respeta tal cual.
    if not sa.inspect(op.get_bind()).has_table("activity_logs"):
        _create_activity_logs()
    else:
        op.alter_column("activity_logs", "route_id", existing_type=sa.Uuid(), nullable=True)


def _create_activity_logs() -> None:
    """Misma forma que app.models.session.ActivityLog, ya con route_id opcional."""
    op.create_table(
        "activity_logs",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("id_players", sa.Integer(), nullable=False),
        sa.Column("session_id", sa.Uuid(), nullable=False),
        sa.Column("route_id", sa.Uuid(), nullable=True),
        sa.Column("mode", sa.String(50), nullable=False),
        sa.Column("status", sa.String(50), nullable=False),
        sa.Column("distance_m", sa.Float(), nullable=False),
        sa.Column("duration_s", sa.Integer(), nullable=False),
        sa.Column("avg_speed_kmh", sa.Float(), nullable=False),
        sa.Column("adherence_ratio", sa.Float(), nullable=True),
        sa.Column("awarded_points", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_activity_logs_id_players", "activity_logs", ["id_players"])
    op.create_index("ix_activity_logs_session_id", "activity_logs", ["session_id"], unique=True)


def downgrade() -> None:
    # Las sesiones libres no tienen ruta a la que volver: se eliminan para poder
    # restaurar la restriccion NOT NULL.
    op.execute("DELETE FROM activity_logs WHERE route_id IS NULL")
    op.execute("DELETE FROM route_sessions WHERE route_id IS NULL")
    op.alter_column("activity_logs", "route_id", existing_type=sa.Uuid(), nullable=False)
    op.alter_column("route_sessions", "route_id", existing_type=sa.Uuid(), nullable=False)
