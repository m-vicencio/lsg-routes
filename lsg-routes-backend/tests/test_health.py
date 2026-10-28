"""Smoke tests: endpoints que solo confirman que el servicio responde."""


async def test_health(client):
    """Verifica que /lsg-routes/health responde con status ok."""
    response = await client.get("/lsg-routes/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_app_version_endpoint(client):
    """GET /lsg-routes/app/version retorna informacion de version."""
    response = await client.get("/lsg-routes/app/version")
    assert response.status_code == 200
    data = response.json()
    assert "latest" in data
    assert "url" in data
    assert "sha256" in data
    assert "minimum_required" in data
