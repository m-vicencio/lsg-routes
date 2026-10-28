"""Esquema inicial de LSG Routes: todas las tablas del dominio.

Revision ID: 001
Revises:
Create Date: 2026-06-01

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB, UUID

revision: str = "001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Crea todas las tablas del esquema inicial."""
    op.create_table(
        "users",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("id_players", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("last_sync_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("players_sensor_endpoint_id", sa.Integer(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("id_players"),
        sa.UniqueConstraint("email"),
    )
    op.create_index("ix_users_id_players", "users", ["id_players"])
    op.create_index("ix_users_email", "users", ["email"])

    op.create_table(
        "planned_routes",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("id_players", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("mode", sa.String(50), nullable=False),
        sa.Column("geometry", JSONB(), nullable=False),
        sa.Column("planned_distance_m", sa.Float(), nullable=True),
        sa.Column("planned_duration_s", sa.Integer(), nullable=True),
        sa.Column("origin", sa.String(20), nullable=False),
        sa.Column("source_payload", JSONB(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_planned_routes_id_players", "planned_routes", ["id_players"])

    op.create_table(
        "route_sessions",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("route_id", UUID(as_uuid=True), nullable=False),
        sa.Column("id_players", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(50), nullable=False),
        sa.Column(
            "started_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("mode", sa.String(50), nullable=False),
        sa.Column("client_session_uuid", UUID(as_uuid=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("client_session_uuid"),
        sa.ForeignKeyConstraint(["route_id"], ["planned_routes.id"]),
    )
    op.create_index("ix_route_sessions_id_players", "route_sessions", ["id_players"])
    op.create_index("ix_route_sessions_route_id", "route_sessions", ["route_id"])
    op.create_index(
        "ix_route_sessions_client_session_uuid", "route_sessions", ["client_session_uuid"]
    )

    op.create_table(
        "track_fixes",
        sa.Column("id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", UUID(as_uuid=True), nullable=False),
        sa.Column("lat", sa.Float(), nullable=False),
        sa.Column("lon", sa.Float(), nullable=False),
        sa.Column("accuracy_m", sa.Float(), nullable=True),
        sa.Column("altitude_m", sa.Float(), nullable=True),
        sa.Column("speed_mps", sa.Float(), nullable=True),
        sa.Column("bearing_deg", sa.Float(), nullable=True),
        sa.Column("is_from_mock_provider", sa.Boolean(), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("client_seq", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["session_id"], ["route_sessions.id"]),
    )
    op.create_index("ix_track_fixes_session_id", "track_fixes", ["session_id"])

    op.create_table(
        "accel_samples",
        sa.Column("id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", UUID(as_uuid=True), nullable=False),
        sa.Column("magnitude_mean", sa.Float(), nullable=False),
        sa.Column("magnitude_variance", sa.Float(), nullable=False),
        sa.Column("bucket_started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("bucket_seconds", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["session_id"], ["route_sessions.id"]),
    )
    op.create_index("ix_accel_samples_session_id", "accel_samples", ["session_id"])

    op.create_table(
        "step_counts",
        sa.Column("id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", UUID(as_uuid=True), nullable=False),
        sa.Column("steps_delta", sa.Integer(), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["session_id"], ["route_sessions.id"]),
    )
    op.create_index("ix_step_counts_session_id", "step_counts", ["session_id"])

    op.create_table(
        "ingest_batches",
        sa.Column("id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", UUID(as_uuid=True), nullable=False),
        sa.Column("client_ref", UUID(as_uuid=True), nullable=False),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("processed", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("client_ref"),
        sa.ForeignKeyConstraint(["session_id"], ["route_sessions.id"]),
    )
    op.create_index("ix_ingest_batches_session_id", "ingest_batches", ["session_id"])
    op.create_index("ix_ingest_batches_client_ref", "ingest_batches", ["client_ref"])

    op.create_table(
        "validation_results",
        sa.Column("id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", UUID(as_uuid=True), nullable=False),
        sa.Column("validator_name", sa.String(100), nullable=False),
        sa.Column("passed", sa.Boolean(), nullable=False),
        sa.Column("details", JSONB(), nullable=True),
        sa.Column(
            "computed_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["session_id"], ["route_sessions.id"]),
    )
    op.create_index("ix_validation_results_session_id", "validation_results", ["session_id"])

    op.create_table(
        "lsg_sync_records",
        sa.Column("id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", UUID(as_uuid=True), nullable=False),
        sa.Column("sensor_event_id", sa.BigInteger(), nullable=True),
        sa.Column("points_ledger_id", sa.String(255), nullable=True),
        sa.Column("awarded_points", sa.Integer(), nullable=True),
        sa.Column("point_dimension_id", sa.Integer(), nullable=True),
        sa.Column("sync_status", sa.String(50), nullable=False),
        sa.Column("last_attempt_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("last_error", JSONB(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("session_id"),
        sa.ForeignKeyConstraint(["session_id"], ["route_sessions.id"]),
    )
    op.create_index("ix_lsg_sync_records_session_id", "lsg_sync_records", ["session_id"])


def downgrade() -> None:
    """Elimina todas las tablas del esquema inicial."""
    op.drop_table("lsg_sync_records")
    op.drop_table("validation_results")
    op.drop_table("ingest_batches")
    op.drop_table("step_counts")
    op.drop_table("accel_samples")
    op.drop_table("track_fixes")
    op.drop_table("route_sessions")
    op.drop_table("planned_routes")
    op.drop_table("users")
