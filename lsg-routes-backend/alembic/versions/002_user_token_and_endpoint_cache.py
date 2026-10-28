"""Token del usuario persistido y cache de endpoints por modo de actividad.

El sensor LSG-Routes pasa a tener un sensor_endpoint por modo (caminata, trote,
senderismo, bicicleta), asi que la cache escalar players_sensor_endpoint_id ya no
alcanza: LSG asigna un id_players_sensor_endpoint distinto por endpoint. Se
sustituye por un JSON {"<id_sensor_endpoint>": <pseid>}. Como es solo cache, no
se migran datos: se repuebla desde GET /sensors/players/{id}.

Ademas se guarda el ultimo access_token validado del jugador para que el
retry_worker pueda ingestar en su nombre sin nadie delante.

Revision ID: 002
Revises: 001
Create Date: 2026-07-30

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision: str = "002"
down_revision: Union[str, None] = "001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("users", sa.Column("players_sensor_endpoints", JSONB(), nullable=True))
    op.add_column("users", sa.Column("lsg_token", sa.Text(), nullable=True))
    op.add_column(
        "users", sa.Column("lsg_token_expires_at", sa.DateTime(timezone=True), nullable=True)
    )
    op.drop_column("users", "players_sensor_endpoint_id")


def downgrade() -> None:
    op.add_column("users", sa.Column("players_sensor_endpoint_id", sa.Integer(), nullable=True))
    op.drop_column("users", "lsg_token_expires_at")
    op.drop_column("users", "lsg_token")
    op.drop_column("users", "players_sensor_endpoints")
