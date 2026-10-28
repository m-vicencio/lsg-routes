FROM python:3.12-slim

WORKDIR /app

# Instalar uv para gestion de dependencias
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

COPY pyproject.toml .python-version uv.lock ./

RUN uv sync --no-dev --frozen

COPY app/ app/
COPY alembic/ alembic/
COPY alembic.ini .

CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "14141"]
