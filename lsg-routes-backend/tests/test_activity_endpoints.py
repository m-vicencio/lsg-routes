"""Pruebas de los endpoints de actividad/perfil."""

import httpx
import pytest

from app.api.routes import activity as activity_module
from tests.conftest import USER_TOKEN


async def test_activity_me_returns_full_history_without_limit(auth_client, db_session, auth_user):
    """Sin `limit`, /activity/me devuelve todas las actividades, mas reciente primero."""
    import uuid
    from datetime import datetime, timedelta, timezone

    from app.models.session import ActivityLog

    base = datetime(2026, 6, 1, 12, 0, 0, tzinfo=timezone.utc)
    for i in range(15):
        db_session.add(ActivityLog(
            id_players=auth_user.id_players,
            session_id=uuid.uuid4(),
            route_id=uuid.uuid4(),
            mode="walking",
            status="VALIDATED",
            distance_m=1000.0,
            duration_s=600,
            avg_speed_kmh=6.0,
            adherence_ratio=0.9,
            awarded_points=10,
            created_at=base + timedelta(minutes=i),
        ))
    await db_session.commit()

    resp = await auth_client.get("/lsg-routes/activity/me")

    assert resp.status_code == 200
    body = resp.json()
    assert body["total_activities"] == 15
    assert len(body["recent"]) == 15  # sin recorte
    # Mas reciente primero
    assert body["recent"][0]["created_at"] > body["recent"][-1]["created_at"]


async def test_activity_me_respects_explicit_limit(auth_client, db_session, auth_user):
    """Con `limit`, /activity/me acota la lista pero los totales cubren todo."""
    import uuid

    from app.models.session import ActivityLog

    for _ in range(5):
        db_session.add(ActivityLog(
            id_players=auth_user.id_players,
            session_id=uuid.uuid4(),
            route_id=uuid.uuid4(),
            mode="walking", status="VALIDATED",
            distance_m=500.0, duration_s=300, avg_speed_kmh=6.0,
            adherence_ratio=0.9, awarded_points=5,
        ))
    await db_session.commit()

    resp = await auth_client.get("/lsg-routes/activity/me?limit=2")

    assert resp.status_code == 200
    body = resp.json()
    assert body["total_activities"] == 5
    assert len(body["recent"]) == 2


async def test_points_balance_sums_dimensions(auth_client, monkeypatch):
    """GET /activity/points-balance retorna el saldo por dimension y el total sumado."""
    async def mock_get_points_balance(player_id: int, token: str):
        assert player_id == 46  # auth_user.id_players
        assert token == USER_TOKEN  # a nombre del participante, no de la cuenta de servicio
        return [
            {"id_players": 46, "id_point_dimension": 1, "balance": 131},
            {"id_players": 46, "id_point_dimension": 2, "balance": 114},
            {"id_players": 46, "id_point_dimension": 3, "balance": 89},
        ]

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_balance", mock_get_points_balance
    )

    resp = await auth_client.get("/lsg-routes/activity/points-balance")

    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 334
    assert len(body["dimensions"]) == 3
    # Sin nombre en el saldo (LSG-Core < 1.2.3), la app cae a "Dimension {id}"
    assert body["dimensions"][0] == {"id_point_dimension": 1, "balance": 131, "name": None}


async def test_points_balance_propagates_dimension_name(auth_client, monkeypatch):
    """LSG-Core 1.2.3 enriquece el saldo con el nombre: es la unica fuente de nombres."""
    async def mock_get_points_balance(player_id: int, token: str):
        return [
            {"id_players": 46, "id_point_dimension": 2, "balance": 114,
             "dimension_code": "FISICO_BASE", "dimension_name": "Fisico",
             "attribute_name": "Fisico"},
            # Sin dimension_name pero con attribute_name -> se usa el atributo
            {"id_players": 46, "id_point_dimension": 4, "balance": 20,
             "attribute_name": "Mental"},
        ]

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_balance", mock_get_points_balance
    )

    resp = await auth_client.get("/lsg-routes/activity/points-balance")

    assert resp.status_code == 200
    dimensions = resp.json()["dimensions"]
    assert dimensions[0]["name"] == "Fisico"
    assert dimensions[1]["name"] == "Mental"


async def test_points_balance_empty(auth_client, monkeypatch):
    """Sin dimensiones, el total es 0 y la lista vacia."""
    async def mock_get_points_balance(player_id: int, token: str):
        return []

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_balance", mock_get_points_balance
    )

    resp = await auth_client.get("/lsg-routes/activity/points-balance")

    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 0
    assert body["dimensions"] == []


async def test_points_balance_core_error_returns_502(auth_client, monkeypatch):
    """Si LSG-Core falla, el endpoint responde 502 (la app lo trata como opcional)."""
    async def mock_get_points_balance(player_id: int, token: str):
        raise httpx.RequestError("core down")

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_balance", mock_get_points_balance
    )

    resp = await auth_client.get("/lsg-routes/activity/points-balance")

    assert resp.status_code == 502
    assert resp.json()["detail"]["code"] == "CORE_SERVICE_ERROR"


async def test_transactions_maps_credit_entries(auth_client, monkeypatch):
    """GET /activity/transactions mapea creditos con saldo inicial/final y monto."""
    captured = {}

    async def mock_get_points_ledger(player_id: int, token: str, source_type=None, from_ts=None):
        captured["player_id"] = player_id
        captured["source_type"] = source_type
        captured["token"] = token
        return [
            {
                "point_dimension_id": 1,
                "direction": "CREDIT",
                "amount": 20,
                "current_balance": 111,
                "resulting_balance": 131,
                "source_type": "ADJUST",
                "reason": "lsg-routes session_uuid=abc",
                "created_at": "2026-06-09T10:00:00",
            },
            # Las redenciones (debitos) deben excluirse
            {
                "point_dimension_id": 2,
                "direction": "DEBIT",
                "amount": 17,
                "source_type": "REDEMPTION",
            },
        ]

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_ledger", mock_get_points_ledger
    )

    resp = await auth_client.get("/lsg-routes/activity/transactions")

    assert resp.status_code == 200
    body = resp.json()
    assert captured["player_id"] == 46
    assert captured["source_type"] is None  # se pide el ledger completo
    assert captured["token"] == USER_TOKEN  # con el token del propio participante
    assert len(body) == 1  # solo el credito
    txn = body[0]
    assert txn["point_dimension_id"] == 1
    assert txn["amount"] == 20
    assert txn["balance_before"] == 111
    assert txn["balance_after"] == 131
    assert txn["source_type"] == "ADJUST"


async def test_transactions_tolerates_missing_balance_fields(auth_client, monkeypatch):
    """Si el ledger no trae saldos, balance_before/after quedan en null sin romper."""
    async def mock_get_points_ledger(player_id: int, token: str, source_type=None, from_ts=None):
        return [{"id_point_dimension": 3, "amount": 5}]  # sin direction ni saldos

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_ledger", mock_get_points_ledger
    )

    resp = await auth_client.get("/lsg-routes/activity/transactions")

    assert resp.status_code == 200
    txn = resp.json()[0]
    assert txn["point_dimension_id"] == 3  # fallback id_point_dimension
    assert txn["amount"] == 5
    assert txn["balance_before"] is None
    assert txn["balance_after"] is None


async def test_transactions_core_error_returns_502(auth_client, monkeypatch):
    """Si LSG-Core falla, el endpoint responde 502."""
    async def mock_get_points_ledger(player_id: int, token: str, source_type=None, from_ts=None):
        raise httpx.HTTPStatusError("boom", request=None, response=httpx.Response(500))

    monkeypatch.setattr(
        activity_module.lsg_core_client, "get_points_ledger", mock_get_points_ledger
    )

    resp = await auth_client.get("/lsg-routes/activity/transactions")

    assert resp.status_code == 502
    assert resp.json()["detail"]["code"] == "CORE_SERVICE_ERROR"
