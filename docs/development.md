# Development

The preferred development flow is `bin/dev-service`. It starts:

- backend service
- backend nREPL
- Shadow CLJS watch
- Shadow nREPL
- optional FlowStorm debugger

```bash
bin/dev-service start
```

Open:

```text
http://localhost:9632
```

Useful commands:

```bash
bin/dev-service status
bin/dev-service repl
bin/dev-service cljs-repl
bin/dev-service logs
bin/dev-service restart
bin/dev-service stop
```

## Hot Reload

Backend code can usually be refreshed from the backend REPL:

```clojure
(user/reload!)
```

Restart streams only when the UDP socket, Manifold graph, or telemetry stage
topology changes:

```clojure
(user/reload! {:restart-streams? true})
```

The app build is watched by Shadow CLJS. The replay worker is a separate build;
compile it after editing `src-cljs/aeonik/replay/worker.cljs`:

```bash
clojure -M:shadow-cljs compile replay-worker
```

## Tests

```bash
clojure -M:test
```

## Profiling Replay

Use the checked-in profiling helpers:

```bash
GCODE_FILE='/path/to/file.gcode' tools/profile_replay_frontend.js
tools/profile_replay_backend.sh
```
