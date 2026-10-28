# Guia de Contribucion - lsg-routes-backend

## Convenciones de codigo

- **Comentarios y docstrings:** en castellano, estilo Google.
- **Identificadores (variables, funciones, clases):** en ingles.

## Mensajes de commit

Formato Conventional Commits:

```
feat: agrega endpoint GET /routes/{id}
fix: corrige calculo de adherencia para rutas muy cortas
chore: actualiza version de SQLAlchemy a 2.0.37
docs: agrega seccion de variables de entorno en README
test: agrega pruebas unitarias para el validador de velocidad
```

Tipos validos: `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `perf`.

## Estructura de ramas

- `main`: rama de produccion, requiere PR + revision
- `dev`: rama de integracion
- `feat/<nombre>`: nuevas funcionalidades
- `fix/<nombre>`: correcciones

## Tests obligatorios

Toda nueva funcionalidad debe incluir:
1. **Prueba unitaria** para validadores, servicios de calculo y clientes externos.
2. **Prueba de integracion** para endpoints (con respx mockeando LSG-Auth y LSG-Core).

Ejecutar antes de abrir PR:
```bash
uv run pytest
```

## Ejecucion local

```bash
uv sync --dev --native-tls   # primera vez (o al cambiar dependencias)
cp .env.example .env          # configurar variables
uv run pytest                 # tests
uv run uvicorn app.main:app --reload --port 14141  # servidor
```

## Variables de entorno sensibles

Nunca commitear `.env` ni credenciales. Ver `.env.example` para la lista completa.

## Modelo de seguridad

El modelo de amenaza y los limites del sistema anti-trampa se describen en `docs/DOCUMENTACION-LSG-ROUTES.md` (secciones 4.7 y 11).
