"""Pruebas unitarias del cliente LSG-Core."""

from datetime import datetime, timezone

import pytest
import respx
from httpx import Response

from app.clients.lsg_core import LsgCoreClient

CORE_BASE = "https://lsg-core.test"
USER_TOKEN = "user_token"
SERVICE_TOKEN = "service_token"


@pytest.fixture
def core_client():
    return LsgCoreClient(CORE_BASE)


async def test_ingest_sensor_event_success(core_client):
    """ingest_sensor_event retorna id_sensor_ingest_event del webhook."""
    with respx.mock:
        respx.post(f"{CORE_BASE}/sensors/ingest/webhook").mock(
            return_value=Response(200, json={"status": "ok", "id_sensor_ingest_event": 78})
        )
        result = await core_client.ingest_sensor_event(
            player_id=46,
            sensor_endpoint_id=3,
            players_sensor_endpoint_id=12,
            raw_payload={"mode": "walking", "distance_km": 2.5, "adherence_ratio": 0.95},
            parsed_value=2.5,
            occurred_at=datetime.now(tz=timezone.utc),
            token=USER_TOKEN,
        )

    assert result["status"] == "ok"
    assert result["id_sensor_ingest_event"] == 78


async def test_ingest_sensor_event_sends_correct_payload(core_client):
    """ingest_sensor_event incluye todos los campos requeridos por LSG-Core."""
    captured_request = None

    def capture(request):
        nonlocal captured_request
        captured_request = request
        return Response(200, json={"status": "ok", "id_sensor_ingest_event": 1})

    with respx.mock:
        respx.post(f"{CORE_BASE}/sensors/ingest/webhook").mock(side_effect=capture)
        await core_client.ingest_sensor_event(
            player_id=46,
            sensor_endpoint_id=3,
            players_sensor_endpoint_id=12,
            raw_payload={"test": True},
            parsed_value=10.0,
            occurred_at=datetime(2026, 6, 1, 12, 0, 0, tzinfo=timezone.utc),
            token=USER_TOKEN,
        )

    import json
    body = json.loads(captured_request.content)
    assert body["player_id"] == 46
    assert body["sensor_endpoint_id"] == 3
    assert body["players_sensor_endpoint_id"] == 12
    assert body["status"] == "OK"
    # El ingest viaja a nombre del participante, no de la cuenta de servicio.
    assert captured_request.headers["Authorization"] == f"Bearer {USER_TOKEN}"


async def test_adjust_points_success(core_client):
    """adjust_points retorna source_ref del ledger."""
    with respx.mock:
        respx.post(f"{CORE_BASE}/players/46/points/adjust").mock(
            return_value=Response(200, json={"status": "ok", "source_ref": "ADJUST-abc123"})
        )
        result = await core_client.adjust_points(
            player_id=46,
            amount=20,
            reason="lsg-routes session_uuid=test-uuid sensor_event_id=78",
            token=SERVICE_TOKEN,
        )

    assert result["source_ref"] == "ADJUST-abc123"


async def test_adjust_points_sends_credit_direction(core_client):
    """adjust_points envia direction=CREDIT con el token de la cuenta de servicio."""
    captured_request = None

    def capture(request):
        nonlocal captured_request
        captured_request = request
        return Response(200, json={"status": "ok", "source_ref": "ADJUST-x"})

    with respx.mock:
        respx.post(f"{CORE_BASE}/players/46/points/adjust").mock(side_effect=capture)
        await core_client.adjust_points(
            player_id=46, amount=50, reason="test", token=SERVICE_TOKEN
        )

    import json
    body = json.loads(captured_request.content)
    assert body["direction"] == "CREDIT"
    assert body["amount"] == 50
    assert captured_request.headers["Authorization"] == f"Bearer {SERVICE_TOKEN}"


@pytest.mark.parametrize(
    "attribute_id,point_dimension_id,expected_key,expected_value,absent_key",
    [
        pytest.param(2, None, "attribute_id", 2, "point_dimension_id", id="uses_attribute_id"),
        pytest.param(0, 7, "point_dimension_id", 7, "attribute_id", id="falls_back_to_point_dimension_id"),
    ],
)
async def test_adjust_points_dimension_field(
    core_client, monkeypatch, attribute_id, point_dimension_id, expected_key, expected_value, absent_key
):
    """LSG-Core 1.2.3 deprecó point_dimension_id: se envia attribute_id, salvo que
    valga 0, caso en que se manda el campo deprecado como respaldo."""
    from app.core.config import settings

    monkeypatch.setattr(settings, "lsg_routes_attribute_id", attribute_id)
    if point_dimension_id is not None:
        monkeypatch.setattr(settings, "lsg_routes_point_dimension_id", point_dimension_id)
    captured_request = None

    def capture(request):
        nonlocal captured_request
        captured_request = request
        return Response(200, json={"status": "ok", "source_ref": "ADJUST-x"})

    with respx.mock:
        respx.post(f"{CORE_BASE}/players/46/points/adjust").mock(side_effect=capture)
        await core_client.adjust_points(
            player_id=46, amount=10, reason="test", token=SERVICE_TOKEN
        )

    import json
    body = json.loads(captured_request.content)
    assert body[expected_key] == expected_value
    assert absent_key not in body
