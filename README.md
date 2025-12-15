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
   - Provides processed stream for consumption

2. **Web Server** (`src/aeonik/web_server.clj`)
   - HTTP server on port 8080 (default)
   - Serves static HTML/CSS/JavaScript
   - WebSocket endpoint (`/ws`) for real-time data streaming
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
clj -M:cljs build-cljs.clj
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
   - Open `http://localhost:9630` (or 9631) in your browser
   - This completes the REPL connection - the "waiting" message will disappear
   - Shadow-cljs serves HTML/JS files from `resources/`
   - The WebSocket automatically connects to the backend on port 8080

**Service Management in REPL**:
```clojure
;; Check service status
(user/status)

;; Start all services (if not auto-started)
(user/start!)

;; Stop all services
(user/stop!)

;; Restart all services
(user/restart!)

;; Start/stop individual services
(user/start-telemetry!)
(user/start-web!)
(user/stop-telemetry!)
(user/stop-web!)
```

**Note**: 
- Services auto-start when you jack in (configured in `dev/user.clj`)
- Always access the app via `http://localhost:9630` during development for REPL support
- The REPL will show "waiting for shadow-cljs runtimes" until you open the browser page
- Once the browser loads, the REPL connection completes and you can evaluate ClojureScript code

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
clojure -T:build test
```

### Build Uberjar

Create a standalone JAR file:

```bash
clojure -T:build ci
```

This creates `target/prusa_telemetry-0.1.0-SNAPSHOT.jar`

Run the uberjar:

```bash
java -jar target/prusa_telemetry-0.1.0-SNAPSHOT.jar [telemetry-port] [web-port]
```

## Usage

### Quick Start

1. **Build the frontend**:
   ```bash
   clj -M:cljs build-cljs.clj
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
├── build-cljs.clj         # ClojureScript build script
└── deps.edn               # Dependencies
```

### Key Dependencies

- **aleph**: HTTP/WebSocket server
- **manifold**: Stream processing
- **clojure.data.json**: JSON serialization
- **clojurescript**: Frontend compilation

### Making Changes

1. **Backend changes** (`src/`): Just restart the server
2. **Frontend changes** (`src-cljs/`): Rebuild with `clj -M:cljs build-cljs.clj` then refresh browser
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
