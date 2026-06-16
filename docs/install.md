# Install

Prusa Telemetry supports three install paths.

## Developer Install

Use this when you want REPL-driven development and hot reload.

```bash
git clone https://github.com/aeonik/prusa_telemetry.git
cd prusa_telemetry
mise trust
mise install clojure
npm ci
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
bin/dev-service stop
```

## Source Production Run

Use this when Clojure is installed on the target machine but you do not need a
managed dev REPL.

```bash
cp config/prusa-telemetry.edn.example config/prusa-telemetry.edn
npm ci
clojure -M:shadow-cljs compile app
clojure -M:shadow-cljs compile replay-worker
clojure -M:prod:run-web
```

Open:

```text
http://localhost:8080
```

## Jar Install

Use this for an operator install without Clojure source tooling at runtime.

```bash
npm ci
clojure -T:build release
cp target/prusa_telemetry-0.1.0-SNAPSHOT.jar /opt/prusa-telemetry/
cp -r bin install /opt/prusa-telemetry/
```

Run:

```bash
PRUSA_CONFIG=/etc/prusa-telemetry/prusa-telemetry.edn \
  PRUSA_TELEMETRY_JAR=/opt/prusa-telemetry/prusa_telemetry-0.1.0-SNAPSHOT.jar \
  /opt/prusa-telemetry/bin/prusa-telemetry
```

See [deploy-systemd.md](deploy-systemd.md) for always-on service setup.
