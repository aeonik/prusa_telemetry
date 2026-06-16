# Prusa Telemetry Dashboard

A real-time telemetry monitoring system for Prusa 3D printers. This application receives UDP telemetry packets from Prusa printers, processes them, and displays the data in a modern web dashboard with live updates via WebSocket.

## Purpose

This application provides:
- **Real-time monitoring** of Prusa printer telemetry data (temperatures, positions, status, etc.)
- **Web-based dashboard** with live updates
- **Multiple data views**: Latest values table or packet history
- **Structured data support**: Displays complex metrics like runtime stats, network info, and more

## Architecture

The system consists of three main components:

1. **Telemetry Server** (`src/aeonik/prusa_telemetry.clj`)
   - Listens for UDP packets on port 8514 (default)
   - Parses binary telemetry data
   - Processes data through transducer pipeline (sorting, formatting, time conversion)
   - Provides `fan-out` stream for multiple consumers (WebSocket clients, file saving, REPL taps)
   - Uses Manifold streams for asynchronous processing

2. **Web Server** (`src/aeonik/web_server.clj`)
   - HTTP server on port 8080 (default)
   - Serves static HTML/CSS/JavaScript
   - WebSocket endpoint (`/ws`) for real-time data streaming
   - Connects to telemetry server's `fan-out` stream
   - Sets up packet saving consumer (saves to EDN files, keyed by print_filename)
   - Converts telemetry packets to JSON for client consumption

3. **Web Dashboard** (`src-cljs/aeonik/app.cljs`)
   - ClojureScript frontend using Hiccup-style rendering
   - WebSocket client for receiving live updates
   - Two view modes:
     - **Latest Values**: Table showing current value for each metric
     - **Packets**: Historical view of recent telemetry packets
   - Controls: Pause, Clear, View Toggle

## Commands

### Toolchain with mise

We pin the local toolchain through [`mise`](https://mise.jdx.dev/) so the Clojure CLI version stays consistent across machines.

1. Trust the repo config (one time):
   ```bash
   mise trust
   ```
2. Install the pinned CLI (see `.mise.toml` for the exact version):
   ```bash
   mise install clojure
   ```
3. Verify the CLI is available:
   ```bash
   mise exec -- clojure -Sdescribe
   ```

If an HTTP proxy blocks either `download.clojure.org` (Clojure installer) or `repo.clojars.org` (libraries), `mise install` will fail with a 403 response. Ensure both hosts are whitelisted and retry the install if you see that error.

### Build ClojureScript

Compile the ClojureScript frontend to JavaScript:

```bash
clojure -M:shadow-cljs compile app
```

This generates:
- `resources/app.js` - Main application loader
- `target/cljs-out/` - Compiled JavaScript modules

**Note**: You must rebuild ClojureScript after making changes to `src-cljs/` files.

### Run the Application

Start both the telemetry server and web server:

```bash
clj -M:run-web
```

Or with custom ports (telemetry port, web port):

```bash
clj -M:run-web 8514 8080
```

Default ports:
- **Telemetry UDP**: 8514
- **Web Server HTTP**: 8080

### Development with Shadow-cljs (REPL-driven)

For REPL-driven development with hot reloading - **it just works!**

1. **Jack in with Calva**:
   - In VS Code/Cursor: Use Calva's "Jack In" command
   - When prompted, select `:app` as the build to connect to
   - **Services auto-start** - the telemetry and web servers start automatically via `dev/user.clj`
   - The REPL session will be created (you may see "waiting for shadow-cljs runtimes")
   
2. **Open the app in your browser**:
   - Open `http://localhost:9632` in your browser
   - This completes the REPL connection - the "waiting" message will disappear
   - Shadow-cljs serves HTML/JS files from `resources/` and proxies all requests to the backend
   - WebSocket and API calls use relative URLs - shadow-cljs handles proxying automatically

**Service Management in REPL**:
```clojure
;; Check service status
(user/status)

;; Start all services (if not auto-started)
(user/start!)

;; Stop all services
(user/stop!)

;; Reload backend code and refresh the live web handler
(user/reload!)

;; Restart streams inside the current JVM only when the stream graph changed
(user/reload! {:restart-streams? true})

;; Start/stop individual services
(user/start-telemetry!)
(user/start-web!)
(user/stop-telemetry!)
(user/stop-web!)
```

**Note**: 
- Services auto-start when you jack in (configured in `dev/user.clj`)
- Always access the app via `http://localhost:9632` during development for REPL support
- The REPL will show "waiting for shadow-cljs runtimes" until you open the browser page
- Once the browser loads, the REPL connection completes and you can evaluate ClojureScript code
- Prefer `(user/reload!)` after backend edits. It reloads source and swaps the
  live web handler without closing the HTTP listener or restarting telemetry.
- Use `(user/reload! {:restart-streams? true})` only after changing UDP socket
  setup, Manifold stream topology, or the telemetry stage graph.

### FlowStorm Debugging

Start the end-to-end Clojure/ClojureScript debugger flow with:

```bash
bin/dev-service debug
```

This starts the backend service, backend nREPL, shadow watch, shadow nREPL, and
the FlowStorm GUI in the managed tmux session. Backend Clojure connects to the
remote debugger automatically. Frontend ClojureScript connects from the browser
through the debug preload, so refresh `http://localhost:9632` after starting
debug mode.

Browser tracing is intentionally narrow by default to avoid recording huge
render/replay values into the browser heap. The default traced CLJS namespaces
are `aeonik.prusalink,aeonik.ws`. Override with:

```bash
FLOW_STORM_CLJS_PREFIXES=aeonik.prusalink,aeonik.ws,aeonik.timeline bin/dev-service debug
```

The debug service uses `flow-storm-shim.preload` instead of FlowStorm's stock
preload because the current patched ClojureScript hook arity is newer than the
published `flow-storm-dbg` runtime hook arity.

**Stream Architecture**:
- Telemetry server creates a `fan-out` stream (main distribution point)
- Web server connects to `fan-out` stream for WebSocket clients and file saving
- Each consumer gets its own subscription via `s/connect`
- Use `(user/add-sink! :name callback)` in REPL to inspect packets
- See `ARCHITECTURE.org` for detailed stream topology documentation

### Run Telemetry Server Only

Run just the telemetry server (for debugging or console output):

```bash
clj -M:run-m
```

Or with custom port:

```bash
clj -M:run-m 8514
```

### Run Tests

```bash
clojure -M:test
```

### Build Uberjar

Create a standalone JAR file:

```bash
clojure -M:shadow-cljs compile app
clojure -T:build ci
```

This creates `target/prusa_telemetry-0.1.0-SNAPSHOT.jar`
with `aeonik.web-server` as the entrypoint, so it starts both the UDP
telemetry listener and HTTP dashboard/archive server.

Run the uberjar:

```bash
java -jar target/prusa_telemetry-0.1.0-SNAPSHOT.jar [telemetry-port] [web-port]
```

## Usage

### Quick Start

1. **Build the frontend**:
   ```bash
   clojure -M:shadow-cljs compile app
   ```

2. **Start the server**:
   ```bash
   clj -M:run-web
   ```

3. **Open your browser**:
   Navigate to `http://localhost:8080`

4. **Configure your Prusa printer** to send telemetry to your machine's IP on port 8514

### Dashboard Features

- **Connection Status**: Shows WebSocket connection state (Connected/Disconnected)
- **View Toggle**: Switch between "Latest Values" and "Packets" views
- **Pause**: Temporarily stop updating the display (data still received)
- **Clear**: Clear all displayed data

### Data Types

The dashboard handles three metric types:

1. **Numeric**: Simple numeric values (temperatures, positions, etc.)
2. **Structured**: Complex data with key-value pairs (runtime stats, network info, etc.)
3. **Error**: Error messages from the printer

### Data Persistence

Telemetry packets are automatically saved to disk:
- **Location**: `telemetry-data/prints/YYYY-MM-DD/<sanitized-filename>.edn`
- **Format**: Append-only EDN files (one packet per line)
- **Keying**: Packets are saved by `print_filename` metric (extracted from telemetry)
- **Tracking**: Active prints are tracked per sender with sticky behavior (10 minute timeout)
- **Loading**: Use the Timeline view to load and view saved print data

### PrusaLink API Auth

PrusaLink credentials are read from `config/prusalink.edn`, which is ignored by git.
Copy `config/prusalink.edn.example` and replace the placeholders:

```clojure
{:base-url "http://printer.local"
 :username "your-username"
 :password "your-password"}
```

You can override the location with `PRUSALINK_AUTH_FILE=/path/to/prusalink.edn`.
The service exposes `/api/prusalink/auth` to confirm whether the auth file is present and valid without returning the password.
It also proxies the printer API through backend Digest auth:

- `/api/prusalink/status` -> printer `/api/v1/status`
- `/api/prusalink/job` -> printer `/api/v1/job`
- `/api/prusalink/connection` -> printer `/api/connection`

### Latest Values View

Shows a table with:
- **Sender**: IP address and port of the printer
- **Metric**: Name of the metric
- **Value**: Current value (formatted appropriately)
- **Type**: Data type (numeric, structured, error)
- **Time**: Device timestamp (if available)

### Packets View

Shows recent telemetry packets with:
- Packet timestamp
- Sender information
- All metrics in that packet
- Individual metric timestamps

## Development

### Project Structure

```
prusa_telemetry/
├── src/                    # Clojure source
│   └── aeonik/
│       ├── prusa_telemetry.clj  # UDP server & data processing
│       └── web_server.clj       # HTTP/WebSocket server
├── src-cljs/               # ClojureScript source
│   └── aeonik/
│       └── app.cljs       # Frontend application
├── resources/              # Static assets
│   ├── index.html         # Main HTML page
│   └── app.js             # Generated ClojureScript loader
├── target/                 # Build artifacts
│   └── cljs-out/          # Compiled JavaScript
├── shadow-cljs.edn        # ClojureScript build/dev server config
└── deps.edn               # Dependencies
```

### Key Dependencies

- **aleph**: HTTP/WebSocket server
- **manifold**: Stream processing
- **clojure.data.json**: JSON serialization
- **clojurescript**: Frontend compilation

### Making Changes

1. **Backend changes** (`src/`): Just restart the server
2. **Frontend changes** (`src-cljs/`): Rebuild with `clojure -M:shadow-cljs compile app` then refresh browser
3. **HTML/CSS changes** (`resources/index.html`): Just refresh browser

### Debugging

- Check browser console (F12) for frontend errors
- Server logs show WebSocket connections and data flow
- Use `clj -M:run-m` to see raw telemetry data in console

## Configuration

### Ports

Default ports can be changed via command-line arguments:

```bash
# Custom telemetry and web ports
clj -M:run-web 9000 3000
```

### Prusa Printer Configuration

Configure your Prusa printer to send telemetry to:
- **Host**: Your machine's IP address
- **Port**: 8514 (or your custom port)

Refer to your Prusa printer's documentation for telemetry configuration.

## License

Copyright © 2025 Dave

Distributed under the Eclipse Public License version 1.0.
