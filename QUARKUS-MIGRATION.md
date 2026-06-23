# Quarkus + GraalVM Native Migration Plan

Migrate the `server/` module from **Ktor + Netty** to **Quarkus (Kotlin) compiled to a GraalVM
native image**, and replace the hand-rolled OAuth authorization server ("the bicycle") by
delegating the OAuth *protocol* to a real, reusable authorization server while keeping the
plain-password UX driven by our own frontend.

## Goals (in priority order)
1. **Drop the OAuth bicycle.** Stop being our own OAuth Authorization Server. Reuse a 3rd-party
   AS for token issuance / DCR / discovery; we only validate tokens (resource server) and keep
   our password login screen.
2. **Cut RSS dramatically.** Target Syncthing-class footprint via native image (the Ktor spike
   already produced a 71 MB binary booting in ~1 ms; Quarkus should beat it — see below).
3. **No regressions.** Every current endpoint, MCP tool/resource, automation behavior, real-time
   sync, and the SPA must work identically.

## Decisions locked (from interview)
- **Language:** Kotlin on Quarkus (`quarkus-kotlin` + `quarkus-rest-kotlin-serialization`) — keeps
  reuse of the existing KMP `shared/` DTOs with zero duplication.
- **MCP:** `quarkus-mcp-server` extension (declarative `@Tool`/`@Resource`), not the embedded
  Kotlin SDK.
- **Auth:** Keep plain-password login asked by our frontend; reuse a 3rd-party OAuth server impl
  instead of writing the protocol ourselves.

## Auth architecture (the crux)

```
MCP client ──discovery/DCR/token──▶ Quarkus app (OIDC Proxy + Resource Server)
                                         │  proxies to backing AS, hides its URL behind /q/oidc
                                         ▼
                                    Ory Hydra  (OAuth2 AS: tokens, DCR, discovery)
                                         │ delegates login + consent
                                         ▼
                                    Our login/consent endpoints (the existing password screen)

SPA (composeApp) ── plain password ──▶ Quarkus app  (unchanged session/password auth)
Quarkus app validates Hydra-issued JWTs on /mcp via quarkus-oidc (resource server)
```

**Why Ory Hydra (not Keycloak):**
- Hydra **delegates login & consent to our app** → we keep "plain password asked by our frontend"
  exactly; no separate Keycloak login UI to theme.
- Tiny Go service (~tens of MB) vs Keycloak's 512 MB–1 GB + mandatory admin/login stack — consistent
  with the memory north-star that motivated this whole effort.
- Hydra owns the OAuth protocol (tokens, refresh, DCR, `.well-known` discovery) = the bicycle, gone.

**What Quarkus gives us for free (no hand-rolling):**
- `quarkus-oidc` resource-server: validates Hydra JWTs on `/mcp` from the bearer header.
- RFC 9728 `.well-known/oauth-protected-resource` metadata endpoint (Quarkus 3.25+).
- `quarkus-oidc-proxy`: fronts Hydra so the MCP client only ever sees our URL, and handles
  Dynamic Client Registration for MCP Inspector / connectors.

**What we still build (small, well-trodden):** the **login + consent provider** endpoints Hydra
redirects to — these reuse our existing password check. This is the only auth code we own.

## File-by-file mapping

| Current (Ktor) | Target (Quarkus) | Notes |
|---|---|---|
| `Application.kt` (embeddedServer, manual DI) | *(deleted)* | Quarkus boots itself; wiring via CDI beans. |
| `ApiRoutes.kt` | JAX-RS resource(s) (`quarkus-rest`) | `suspend` resource methods supported via `quarkus-kotlin`. kotlinx-serialization via `quarkus-rest-kotlin-serialization`. |
| `AuthRoutes.kt` | Auth resource + **Hydra login/consent provider** | Password check reused; now also backs Hydra's login/consent flow. |
| `McpRoutes.kt` + `mcp/McpHandler.kt` | `quarkus-mcp-server` `@Tool`/`@Resource` beans | SSE + OAuth handled by the extension + `quarkus-oidc`. |
| `WebRoutes.kt` (SPA serving) | Static resources / catch-all serving wasm dist | Copy `composeApp` wasm dist into `META-INF/resources` or serve from a configured dir. |
| `Logging.kt` + `logback.xml` | Quarkus logging (`application.properties`) | **Removes the native-image Logback headache** the Ktor spike hit (FileDescriptor in image heap). |
| `automation/AutomationManager.kt` | `@ApplicationScoped` bean | Mostly portable. 10-min heartbeat → keep coroutine, or `quarkus-scheduler` `@Scheduled`. |
| `automation/SunCalculator.kt` | Plain bean | Pure logic; verbatim. |
| `config/Config.kt` | `@ConfigMapping` + `application.properties`/env | Quarkus reads `.env` natively; **drop dotenv-kotlin**. |
| `hue/HueRemoteClient.kt` (Ktor client CIO) | Quarkus REST Client (`@RegisterRestClient`) | Cleaner native than embedding Ktor client. Rate limiters port as-is. |
| `hue/HueService.kt`, `LampStateCache.kt`, `RateLimiter.kt` | `@ApplicationScoped` beans | Portable; coroutines fine. 5s cache refresh → coroutine or `@Scheduled`. |
| `hue/HueModels.kt` | Unchanged DTOs | Serialization models. |
| `persistence/SettingsStore.kt` (raw sqlite-jdbc) | Keep raw store **or** `quarkus-jdbc-sqlite` + Agroal | Raw store native-compiles (spike proved it). Agroal is the idiomatic option. |
| `shared/` module | **Unchanged, reused** | KMP DTOs consumed directly by Kotlin Quarkus. |

## Build / native image
- Server module swaps `kotlinJvm` + `io.ktor.plugin` + `application` → **`io.quarkus` plugin**
  (+ `kotlin("plugin.allopen")` for CDI). `shared` stays Kotlin Multiplatform.
- Native build: `./gradlew :server:build -Dquarkus.native.enabled=true
  -Dquarkus.native.container-build=true` (or in a GraalVM 25 CI image).
- **Quarkus generates reachability metadata at build time → no tracing-agent step** (the fragile
  part of the Ktor spike disappears). kotlinx-serialization registered by the Kotlin REST extension;
  sqlite native handled by `quarkus-jdbc-sqlite` / the `SqliteJdbcFeature`.
- Expected footprint: ≤ the Ktor spike's 71 MB, with lower RSS (build-time init, no Netty arenas).

## Dependencies (server)
Add: `quarkus-kotlin`, `quarkus-rest`, `quarkus-rest-kotlin-serialization`, `quarkus-rest-client`
(+ kotlinx-serialization variant), `quarkus-mcp-server`, `quarkus-oidc`, `quarkus-oidc-proxy`,
`quarkus-scheduler`, `quarkus-jdbc-sqlite`.
Remove: Ktor server (core/netty/CN/auth/sessions/cors/sse/serialization), Ktor client (core/cio/CN),
`mcp-sdk`/`mcp-sdk-server`, `dotenv-kotlin`, `logback-classic`.

## Deployment
- `docker-compose.yml`: services become `hue-manager` (native binary, `ubi9-micro`/distroless image)
  + `hydra` (Ory Hydra, SQLite or Postgres) + existing optional `caddy`.
- Hydra config: register the MCP client (or rely on DCR via OIDC Proxy); point its login/consent
  URLs at our app.
- CI (`docker-publish.yml`): build native image in a GraalVM 25 container, package into micro base.
- `release-desktop.yml` and the `composeApp`/`androidApp` modules are **unaffected**.

## Phased execution (each phase independently verifiable)
- **Phase 0 — De-risk (do first):** throwaway spike of `quarkus-mcp-server` (SSE + a trivial tool +
  `quarkus-oidc` resource-server + native compile). MCP is the highest-value, newest-extension piece;
  prove it before committing. Also stand up Hydra locally with a stub login/consent.
- **Phase 1 — Scaffold + portable core:** new Quarkus build for `:server`, CDI wiring, port the
  framework-agnostic beans (config, SunCalculator, RateLimiter, SettingsStore, LampStateCache,
  AutomationManager, HueModels). No HTTP yet; unit-test the automation logic.
- **Phase 2 — REST + Hue client + SPA + SPA password auth:** port `ApiRoutes` to JAX-RS, Hue client
  to REST Client, serve the wasm SPA, plain-password/session auth for the SPA.
- **Phase 3 — MCP:** port tools/resources to `quarkus-mcp-server`.
- **Phase 4 — OAuth:** Hydra + login/consent provider + resource-server config + OIDC proxy/DCR;
  delete all hand-rolled OAuth endpoints.
- **Phase 5 — Native + Docker + CI:** native build, compose (app + Hydra + Caddy), pipeline.
- **Phase 6 — Cutover:** delete Ktor module, update `CLAUDE.md`/`TASK.md`/`README`, measure RSS vs 215 MB.

## Top risks
1. **`quarkus-mcp-server` maturity** (SSE + OAuth) — newest, highest-value piece → **Phase 0 spike gates the whole migration.**
2. **Login/consent provider against Hydra** ("OAuth the hard way" on the login side) — bounded, well-documented, far less than a full AS.
3. **`suspend` in JAX-RS resources** — supported, but verify against current `quarkus-kotlin`.
4. **Adding a second container (Hydra)** — acceptable (already docker-compose + Caddy), and tiny vs Keycloak.
5. **Hue REST Client parity** — rate limiting + optimistic cache patching must behave exactly as today.

## Go/No-go
Phase 0 is the gate. If `quarkus-mcp-server` can't do SSE + OAuth cleanly in native, we fall back to
the proven path: **native-image the existing Ktor server** (already works) + refactor only the Kotlin
MCP auth to resource-server mode against Hydra — same auth win, no framework rewrite.
