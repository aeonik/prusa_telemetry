# Configuration

Copy `config/prusa-telemetry.edn.example` to `config/prusa-telemetry.edn`
for a local install, or set `PRUSA_CONFIG=/path/to/prusa-telemetry.edn`.

Runtime precedence is:

1. Built-in defaults
2. `PRUSA_CONFIG` or `config/prusa-telemetry.edn`
3. Environment overrides
4. Positional CLI ports, for backwards compatibility

Supported environment overrides:

- `TELEMETRY_PORT`
- `WEB_HOST`
- `WEB_PORT`
- `TELEMETRY_DATA_DIR`
- `PRINT_END_TIMEOUT_MS`
- `PRUSALINK_URL`
- `PRUSALINK_USERNAME`
- `PRUSALINK_PASSWORD`

The legacy `config/prusalink.edn` file is still supported. If
`PRUSALINK_AUTH_FILE` is set, it takes precedence for PrusaLink credentials.
