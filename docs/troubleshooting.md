# Troubleshooting

## Dashboard Loads But No Live Data

- Confirm the printer sends UDP telemetry to the dashboard host IP.
- Confirm the port matches `:telemetry/:port`, default `8514`.
- Check firewall rules for UDP `8514`.
- Watch logs with `bin/dev-service logs` or `journalctl -u prusa-telemetry -f`.

## Archive Files Are Not Saved

- Confirm `:archive/:prints-dir` exists or can be created.
- Confirm the service user can write there.
- Confirm telemetry includes `print_filename`.
- PrusaLink helps identify repeated runs of the same file.

## PrusaLink Fails

- Open `/api/prusalink/auth` in the dashboard host.
- Confirm `:prusalink/:base-url`, username, and password.
- Confirm the printer is reachable from the dashboard host.

## Replay Loads Slowly

Large multi-day prints can be large. Replay currently keeps the full archive
index in a browser worker and a bounded preview index on the main thread. Exact
packet details arrive asynchronously while KPI cards scrub immediately from the
preview.

For very large prints, prefer the latest browser release and avoid leaving
multiple replay tabs open.

## Browser Memory Is High

Replay and G-code visualization are intentionally data-heavy. Close old replay
tabs after analysis. Future camera streams should be stored and replayed as
separate media chunks, not embedded into EDN telemetry files.

## Port Already In Use

Override ports:

```bash
WEB_PORT=18080 TELEMETRY_PORT=18514 clojure -M:prod:run-web
```

or in config:

```clojure
{:telemetry {:port 18514}
 :http {:port 18080}}
```
