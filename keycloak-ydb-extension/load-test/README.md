# Load Testing

Load tests for Keycloak + YDB using [keycloak-benchmark](https://github.com/keycloak/keycloak-benchmark) (Gatling).

## Prerequisites

- Java 21+
- Python 3
- Docker + Docker Compose

## Quick Start

### 1. Download keycloak-benchmark

```bash
./prepare.sh
```

Downloads the Gatling benchmark JARs from GitHub releases into `lib/`.
To use a specific version:

```bash
./prepare.sh 26.4.0-SNAPSHOT
```

### 2. Build and start infrastructure

From the `keycloak-ydb-extension/` root:

```bash
./run-keycloak-with-ydb.sh
```

This builds core + retry-proxy, copies the JAR, and starts Docker Compose (YDB + Keycloak + retry-proxy).

Wait for Keycloak to start (~30-60s). Check logs:

```bash
docker compose -f docker/docker-compose.yml logs -f ydb-keycloak
```

Services:
- Keycloak (via retry-proxy): http://localhost:9090
- YDB Monitoring: http://localhost:8765
- Admin credentials: `admin` / `admin`

### 3. Setup test realm

```bash
python3 setup-test-realm.py
```

Creates `test-realm` with clients (`gatling`, `client-0`, `test-client`), roles, groups, and test users.

By default connects to `http://localhost:9090`. To use a different URL:

```bash
python3 setup-test-realm.py http://localhost:8080
```

### 4. Run load test

```bash
./run.sh <scenario> <users-per-sec> [measurement-sec] [server-url]
```

Examples:

```bash
./run.sh CreateUsers 30              # 30 rps, 60s measurement
./run.sh CreateUsers 30 120          # 30 rps, 120s measurement
./run.sh CreateDeleteUsers 10 60     # 10 rps, 60s
```

Results are saved to `results/` with Gatling HTML reports.

### 5. Cleanup between runs

Delete all users from test-realm:

```bash
python3 delete-all-users.py
```

## Alternative Infrastructure

### Keycloak + PostgreSQL

For comparison testing against PostgreSQL:

```bash
docker compose -f docker/docker-compose-pg.yml up -d
```

Services:
- Keycloak: http://localhost:9091
- Admin credentials: `admin` / `admin`

### Keycloak + Remote YDB

For connecting to an external YDB instance (not in Docker Compose):

```bash
# Start YDB separately, e.g.:
docker run -d --rm --name ydb-local -h localhost \
  --platform linux/amd64 \
  -p 2135:2135 -p 2136:2136 -p 8765:8765 \
  -v $(pwd)/ydb_certs:/ydb_certs -v $(pwd)/ydb_data:/ydb_data \
  -e GRPC_TLS_PORT=2135 -e GRPC_PORT=2136 -e MON_PORT=8765 \
  ydbplatform/local-ydb:latest

# Then start Keycloak + retry-proxy:
YDB_JDBC_URL="jdbc:ydb:grpc://host.docker.internal:2136/local" \
  docker compose -f docker/docker-compose-remote-ydb.yml up -d --build
```

For a cloud YDB instance:

```bash
YDB_JDBC_URL="jdbc:ydb:grpcs://ydb.serverless.yandexcloud.net:2135/ru-central1/..." \
  docker compose -f docker/docker-compose-remote-ydb.yml up -d --build
```

Services:
- Keycloak (via retry-proxy): http://localhost:9090
- Admin credentials: `admin` / `admin`

## Available Scenarios

| Scenario | Description |
|----------|-------------|
| `CreateUsers` | Create user + List users |
| `CreateDeleteUsers` | Create user + List users + Delete user |
| `CreateClients` | Create client |
| `CreateDeleteClients` | Create client + Delete client |
| `ClientSecret` | Client credentials grant (authentication) |
| `AuthorizationCode` | Authorization code flow (authentication) |

Full list of scenarios: [keycloak-benchmark/scenario](https://github.com/keycloak/keycloak-benchmark/tree/main/benchmark/src/main/scala/keycloak/scenario)

## Directory Structure

```
load-test/
  prepare.sh            # Downloads keycloak-benchmark from GitHub
  run.sh                # Runs Gatling scenario
  setup-test-realm.py   # Creates test realm, clients, users
  delete-all-users.py   # Deletes all users from realm
  lib/                  # Benchmark JARs (gitignored)
  results/              # Gatling reports (gitignored)
```