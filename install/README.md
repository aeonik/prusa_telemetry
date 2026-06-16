# Install Assets

- `systemd/prusa-telemetry.service` is a template service unit for a jar-based install.
- Install the application under `/opt/prusa-telemetry`.
- Install local config under `/etc/prusa-telemetry/prusa-telemetry.edn`.
- Store archives under `/var/lib/prusa-telemetry/prints` for system installs.

Adjust `User`, `Group`, paths, and `JAVA_OPTS` to match the target machine.
