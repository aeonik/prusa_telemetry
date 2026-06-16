# Configuration

The main runtime config file is EDN.

```bash
cp config/prusa-telemetry.edn.example config/prusa-telemetry.edn
```

Or use an external path:

```bash
PRUSA_CONFIG=/etc/prusa-telemetry/prusa-telemetry.edn
```

## Example

```clojure
{:telemetry
 {:port 8514}

 :http
 {:host "0.0.0.0"
  :port 8080}

 :archive
 {:prints-dir "/var/lib/prusa-telemetry/prints"
  :print-end-timeout-ms 600000}

 :prusalink
 {:base-url "http://prusamk4.local"
  :username "maker"
  :password "secret"}}
```

## Precedence

1. Built-in defaults
2. `PRUSA_CONFIG` or `config/prusa-telemetry.edn`
3. Environment overrides
4. Positional CLI ports, for compatibility

## Environment Overrides

- `TELEMETRY_PORT`
- `WEB_HOST`
- `WEB_PORT`
- `TELEMETRY_DATA_DIR`
- `PRINT_END_TIMEOUT_MS`
- `PRUSALINK_URL`
- `PRUSALINK_USERNAME`
- `PRUSALINK_PASSWORD`

## PrusaLink Auth

Preferred: put PrusaLink credentials under `:prusalink` in the main config.

Still supported:

```bash
PRUSALINK_AUTH_FILE=/path/to/prusalink.edn
```

and legacy:

```text
config/prusalink.edn
```

`PRUSALINK_AUTH_FILE` takes precedence when set.
