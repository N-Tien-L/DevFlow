---
name: docker-local
description: Operational guidance for managing, inspecting, and troubleshooting the DevFlow local Docker Compose environment (PostgreSQL 17, Backend, Frontend, Prometheus, Grafana).
---

# Docker Local Stack Skill — DevFlow

This skill provides instructions, commands, and troubleshooting techniques for managing the local containerized environment defined in `docker-compose.yml`.

---

## 1. Stack Overview & Port Mappings

| Service | Container Image / Build | Port Mapping | Healthcheck / Access |
|---|---|---|---|
| **`db`** | `postgres:17-alpine` | `5432:5432` | `pg_isready -U devflow` |
| **`backend`** | `./backend/Dockerfile` | `8080:8080` | `http://localhost:8080/actuator/health` |
| **`frontend`** | `./frontend/Dockerfile` | `3000:80` | `http://localhost:3000` |
| **`prometheus`** | `prom/prometheus:v2.54.1` | `9090:9090` | `http://localhost:9090` |
| **`grafana`** | `grafana/grafana:11.2.2` | `3001:3000` | `http://localhost:3001` (admin / admin) |

---

## 2. Essential Commands

All commands should be executed from the project root:

```bash
# 1. Start all containers in the background
docker compose up -d

# 2. Check real-time container status and health
docker compose ps

# 3. Stream logs from all services
docker compose logs -f

# 4. Stream logs from a specific service
docker compose logs -f backend
docker compose logs -f db

# 5. Stop all running containers (preserving database volume)
docker compose down

# 6. Stop all containers and delete volumes (complete database reset)
docker compose down -v
```

---

## 3. Rebuilding After Code Changes

When you make changes to backend or frontend code and want to test them inside Docker:

```bash
# Rebuild and restart only the backend container
docker compose build backend
docker compose up -d --no-deps backend

# Rebuild and restart only the frontend container
docker compose build frontend
docker compose up -d --no-deps frontend

# Force rebuild of entire stack without cache
docker compose build --no-cache
docker compose up -d
```

---

## 4. PostgreSQL Database Operations

### Connect to PostgreSQL Interactive Shell (`psql`)
```bash
docker compose exec db psql -U devflow -d devflow
```

### Useful `psql` Queries
```sql
-- List all tables in devflow schema
\dt

-- Describe a specific table
\d tasks

-- Inspect current records
SELECT * FROM boards LIMIT 10;
```

### Run SQL Command Directly
```bash
docker compose exec db psql -U devflow -d devflow -c "SELECT count(*) FROM tasks;"
```

### Backup and Restore Database
```bash
# Export dump to host
docker compose exec db pg_dump -U devflow devflow > backup.sql

# Restore dump
docker compose exec -T db psql -U devflow -d devflow < backup.sql
```

---

## 5. Metrics & Monitoring Access

- **Prometheus UI:** Navigate to [http://localhost:9090](http://localhost:9090). Check targets under `Status -> Targets` to ensure the Spring Boot actuator scrape target (`backend:8080/actuator/prometheus`) is `UP`.
- **Grafana Dashboards:** Navigate to [http://localhost:3001](http://localhost:3001) (Credentials: `admin` / `admin`). Provisioned datasource points directly to Prometheus.

---

## 6. Troubleshooting Common Issues

### Port Conflicts (e.g. `Bind for 0.0.0.0:5432 failed: port is already allocated`)
- Cause: A local PostgreSQL or another project service is already running on port 5432 or 8080.
- Fix: Stop local postgres service, or change host port in `docker-compose.yml` (e.g., `"5433:5432"`).

### Backend Fails Healthcheck or Exits
1. Inspect logs: `docker compose logs backend`
2. Check database connection: verify `db` container is healthy (`docker compose ps`). Backend will not start until `db` passes healthcheck.
3. Check memory/heap: Ensure Docker daemon has at least 4GB of RAM allocated.

### Reset Everything to Clean State
If you encounter persistent corrupted volume or state issues:
```bash
docker compose down -v --remove-orphans
docker compose up -d --build
```
