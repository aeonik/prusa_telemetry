# systemd Deploy

This is the intended always-on Linux install path.

## Build

```bash
npm ci
clojure -T:build release
```

## Install Files

```bash
sudo useradd --system --home /var/lib/prusa-telemetry --shell /usr/bin/nologin prusa-telemetry
sudo mkdir -p /opt/prusa-telemetry /etc/prusa-telemetry /var/lib/prusa-telemetry/prints
sudo cp target/prusa_telemetry-0.1.0-SNAPSHOT.jar /opt/prusa-telemetry/
sudo cp -r bin /opt/prusa-telemetry/
sudo cp config/prusa-telemetry.edn.example /etc/prusa-telemetry/prusa-telemetry.edn
sudo cp install/systemd/prusa-telemetry.service /etc/systemd/system/
sudo chown -R prusa-telemetry:prusa-telemetry /var/lib/prusa-telemetry
sudo chown -R root:root /opt/prusa-telemetry /etc/prusa-telemetry
```

Edit:

```bash
sudoedit /etc/prusa-telemetry/prusa-telemetry.edn
```

Set:

```clojure
:archive {:prints-dir "/var/lib/prusa-telemetry/prints"}
```

## Start

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now prusa-telemetry
sudo systemctl status prusa-telemetry
```

Logs:

```bash
journalctl -u prusa-telemetry -f
```

## Upgrade

```bash
sudo systemctl stop prusa-telemetry
sudo cp target/prusa_telemetry-NEW.jar /opt/prusa-telemetry/
sudo systemctl start prusa-telemetry
```

If you pin `PRUSA_TELEMETRY_JAR` in the service, update that path during
upgrades. Otherwise `bin/prusa-telemetry` picks the newest matching jar in the
install directory.
