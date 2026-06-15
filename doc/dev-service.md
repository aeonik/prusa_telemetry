# Development Service

This project runs best in development as live REPL-owned processes, not as an
uberjar. The `bin/dev-service` script keeps the backend and shadow-cljs watcher
inside one tmux session so they are easy to start, stop, inspect, and attach to.

## Commands

```bash
bin/dev-service start
bin/dev-service status
bin/dev-service attach
bin/dev-service logs
bin/dev-service stop
bin/dev-service restart
```

The service starts two tmux windows:

- `backend`: telemetry UDP receiver, HTTP/WebSocket backend, and backend nREPL.
- `shadow`: shadow-cljs `watch app`, dev HTTP server, and shadow nREPL.

## Endpoints

- App: `http://localhost:9632`
- Backend HTTP/WebSocket: `http://localhost:8080`
- Telemetry UDP: `8514`
- Backend Clojure nREPL: `127.0.0.1:7888`
- shadow-cljs nREPL: `127.0.0.1:9631`

The backend nREPL port is also written to `.nrepl-port` for editor attach
workflows.

## REPL Access

```bash
bin/dev-service repl
bin/dev-service cljs-repl
```

From the backend REPL, use the existing `user` namespace lifecycle helpers:

```clojure
(user/status)
(user/restart!)
(user/stop!)
(user/start!)
```

For the shadow nREPL, open `http://localhost:9632` in a browser after starting
the service so the browser runtime connects.

## Port Overrides

Set environment variables before `start`:

```bash
NREPL_PORT=7889 WEB_PORT=8081 TELEMETRY_PORT=8515 bin/dev-service start
```

Supported overrides are `NREPL_BIND`, `NREPL_PORT`, `SHADOW_NREPL_PORT`,
`TELEMETRY_PORT`, `WEB_PORT`, `SHADOW_HTTP_PORT`, and
`PRUSA_TELEMETRY_TMUX_SESSION`.
