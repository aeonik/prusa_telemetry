# Printer Setup

The printer must send telemetry UDP packets to the machine running this app.

Default target:

```text
<dashboard-host-ip>:8514
```

Find the host IP on Linux:

```bash
ip route get 1.1.1.1
```

Open firewall access for UDP telemetry and HTTP dashboard traffic:

```text
UDP 8514
TCP 8080
```

For development through Shadow CLJS:

```text
TCP 9632
```

PrusaLink is optional but recommended. Configure it in
`config/prusa-telemetry.edn`:

```clojure
:prusalink
{:base-url "http://prusamk4.local"
 :username "maker"
 :password "secret"}
```

PrusaLink enables dashboard print progress, thumbnails, job IDs, and better
archive run naming.
