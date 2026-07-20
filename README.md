# Nine Men's Morris — Java Desktop & Spring Boot Backend

[![Java CI](https://github.com/hannnz1/nine-mens-morris-java/actions/workflows/ci.yml/badge.svg)](https://github.com/hannnz1/nine-mens-morris-java/actions/workflows/ci.yml)

A full implementation of **Nine Men's Morris**, originally developed by Group 43 for Monash University FIT3077 (Semester 1, 2023) and subsequently refactored into a backend-oriented Java project.

The repository now contains the original desktop game, a framework-independent rules engine, and a Spring Boot multiplayer API backed by PostgreSQL. The backend demonstrates transaction boundaries, optimistic concurrency control, idempotent writes, database migrations, token-based player authorization, real-time updates, automated tests, CI, and container deployment.

![Nine Men's Morris gameplay](<Screenshots/Hints prompting Black selection.png>)

## What It Implements

- Complete placement, movement, flying, mill, removal, and victory rules.
- Original local two-player Java desktop client with hints and tutorials.
- REST endpoints for creating, reading, and playing persistent games.
- One-time 256-bit player credentials; only SHA-256 token digests are stored.
- JPA `@Version` optimistic locking plus an explicit client version check.
- Per-game idempotency keys that make safe HTTP retries possible.
- Flyway-managed PostgreSQL schema and useful query indexes.
- STOMP/WebSocket game updates on `/topic/games/{gameId}`.
- Stable JSON validation and error responses without stack-trace leakage.
- JUnit rule tests and Spring Boot API integration tests.
- Multi-stage, non-root Docker image and Docker Compose environment.

## Architecture

```text
Desktop UI (legacy-desktop) ----\
                                >---- Pure Java rules (game-engine)
REST / WebSocket (backend) -----/               |
       |                                        |
       +-- service + transactions               |
       +-- JPA optimistic locking               |
       +-- Flyway ------------------------ PostgreSQL
```

The Maven modules have deliberately different responsibilities:

- `game-engine` — immutable game state and deterministic rules with no UI, Spring, or database dependency.
- `backend` — controllers, validation, authorization, transactions, persistence, error handling, and WebSocket delivery.
- `legacy-desktop` — builds the original submission from `Nine Man's Morris/src` without discarding its UI or academic history.

The backend stores each rules-engine snapshot as JSON. Relational columns retain the fields used for identity, status filtering, concurrency, authorization, and auditing. This keeps the domain engine independent while PostgreSQL still enforces primary keys, foreign keys, unique idempotency keys, and indexed access paths.

## Requirements

Choose either:

- Docker Desktop with Docker Compose; or
- JDK 17, Maven 3.9+, and PostgreSQL 16.

## Run the Backend with Docker

From the repository root in PowerShell:

```powershell
Copy-Item .env.example .env
# Edit .env and replace POSTGRES_PASSWORD before any shared deployment.
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

Flyway creates the database schema automatically. PostgreSQL data is retained in the `morris-postgres-data` named volume.

Stop the application without deleting its data:

```powershell
docker compose down
```

To deliberately delete the local database too, use `docker compose down -v`.

## Try the REST API

Create a game. The two raw credentials are returned only by this endpoint, so each token should be given only to its player:

```powershell
$created = Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/v1/games `
  -ContentType 'application/json' `
  -Body '{"whitePlayer":"Alice","blackPlayer":"Bob"}'

$gameId = $created.game.id
$whiteToken = $created.whiteCredential.token
$created
```

Place White's first piece. `expectedVersion` prevents a stale browser from overwriting a newer move; `Idempotency-Key` prevents a network retry from applying the move twice:

```powershell
$headers = @{
  'X-Player-Token' = $whiteToken
  'Idempotency-Key' = [guid]::NewGuid().ToString()
}

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/v1/games/$gameId/actions" `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body '{"type":"PLACE","from":null,"to":"A1","expectedVersion":0}'
```

Read public game state without a token:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/games/$gameId"
```

Actions use `PLACE`, `MOVE`, or `REMOVE`. Board positions use identifiers such as `A1`, `D1`, and `G7`. A STOMP client can connect at `/ws` and subscribe to `/topic/games/{gameId}`.

## Run Without Docker

Create a PostgreSQL database, then provide connection variables:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/morris'
$env:DB_USERNAME = 'morris'
$env:DB_PASSWORD = 'your-password'
mvn -pl backend -am spring-boot:run
```

The API listens on `http://localhost:8080` by default.

## Build and Test

Run all modules and all tests:

```powershell
mvn clean verify
```

Or test inside an isolated Maven container:

```powershell
docker run --rm `
  -v "${PWD}:/workspace" `
  -w /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn --batch-mode --no-transfer-progress clean verify
```

The suite covers the original desktop rules, all 24 positions and 32 graph edges, placement, movement, flying, mills, removal and victory, plus API creation, validation, authorization, optimistic version conflicts, rule errors, and idempotent retries. Every push and pull request to `main` runs the same Maven verification through GitHub Actions.

## Run the Original Desktop Game

Package and launch the executable desktop JAR:

```powershell
mvn -pl legacy-desktop -am package
java -jar .\legacy-desktop\target\legacy-desktop-1.0.0-SNAPSHOT.jar
```

Alternatively, open `Nine Man's Morris/src/Engine.java` in IntelliJ IDEA and run `Engine.main()`. Running from the original `src` directory ensures its relative tutorial-image paths resolve correctly.

## Persistence and Concurrency Design

- `game_sessions.version` is incremented by Hibernate on every successful state change.
- A client must submit the version it last read. A stale action receives HTTP `409 VERSION_CONFLICT`.
- The database version check also protects against two requests that pass application checks at nearly the same time.
- `(game_id, idempotency_key)` is unique. A retry with the same payload returns the saved response; reuse with a different payload receives HTTP 409.
- State changes and idempotency records share one database transaction.
- WebSocket notification is emitted only after the database transaction commits.

## Security Notes

- Player tokens are generated with `SecureRandom`, returned once, compared in constant time, and never stored in plaintext.
- Write endpoints authorize the token against the selected game and enforce the active player.
- Bean Validation constrains request data; game rules are independently checked by the domain engine.
- API errors use stable codes and do not expose internal exception messages or stack traces.
- Request-header size and credential length are bounded, and the container runs as an unprivileged user.
- Secrets are supplied through environment variables; `.env` is ignored by Git and `.env.example` contains no usable production secret.

For an internet-facing production deployment, add TLS at a reverse proxy, a managed secret store, rate limiting, token expiry/rotation, authenticated WebSocket subscriptions, centralized logs/metrics, backups, and multi-instance event delivery rather than the in-memory STOMP broker.

## Design Documentation

- [Domain model](Group43_Domain_Model.pdf)
- [Revised class diagram](<Revised Architecture/Group43_Revised_Class_Diagram.pdf>)
- [Sprint 4 design rationale](Design%20Rationale/Group_43_Sprint_4_Written_Work.pdf)
- [Sequence diagrams](<Sequence Diagrams>)
- [UI designs](<UI Design>)
- [Gameplay screenshots](Screenshots)

## Third-Party Component

Rendering and input handling use an adapted copy of Princeton University's `StdDraw`, authored by Robert Sedgewick and Kevin Wayne. See the [Princeton StdDraw documentation](https://introcs.cs.princeton.edu/java/stdlib/StdDraw.java.html) and the attribution in `Nine Man's Morris/src/View/StdDraw.java`.

## Academic Context

This repository is an archived and subsequently modernized copy of a collaborative university project. The GitHub history begins with the import and does not contain the original Monash GitLab commit history. The post-course backend refactor should be described separately from the original group work in applications and interviews.
