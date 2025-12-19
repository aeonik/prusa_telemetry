# XTDB Integration Guide

This guide explains how to set up and use XTDB for storing telemetry metrics.

## Overview

XTDB integration allows you to persist telemetry metrics in a time-series database. The system automatically stores all processed telemetry packets as they arrive.

## Quick Start

### In-Memory XTDB (Recommended for Development)

1. **Dependencies are already configured** in `deps.edn`:
   - `com.xtdb/xtdb-api` - Public API
   - `com.xtdb/xtdb-core` - In-process nodes

2. **Start XTDB in REPL**:
```clojure
(user/start-xtdb! {:in-memory? true})
```

3. **Start services** (web server will automatically use XTDB):
```clojure
(user/start!)
```

That's it! Metrics will now be stored automatically.

## Usage

### Starting XTDB

```clojure
;; In-memory (works on Java 25)
(user/start-xtdb! {:in-memory? true})

;; Check status
(user/status)
;; => {:telemetry :running, :web :running, :xtdb :running}
```

### Querying Metrics

```clojure
(require '[aeonik.xtdb :as xtdb])

;; Get all metrics
(xtdb/query-metrics (:node @user/xtdb-node))

;; Query specific metric name
(xtdb/query-metrics (:node @user/xtdb-node) {:name "temp"})

;; Query recent metrics (last hour)
(let [one-hour-ago (- (System/currentTimeMillis) (* 60 60 1000))]
  (xtdb/query-metrics (:node @user/xtdb-node) {:since-ms one-hour-ago}))

;; Get all unique metric names
(xtdb/query-metric-names (:node @user/xtdb-node))

;; Get total count
(xtdb/query-metric-count (:node @user/xtdb-node))
```

### Custom Queries

You can also use XTDB's query API directly:

```clojure
(require '[xtdb.api :as xt])

(xt/q (:node @user/xtdb-node)
      '{:find [name value device-time-us]
        :where [[e :telemetry/name name]
                [e :telemetry/value value]
                [e :telemetry/device-time-us device-time-us]
                [(= name "temp")]]
        :limit 10})
```

## Data Schema

Metrics are stored with the following schema:

- `:xt/id` - Unique document ID (composed of msg:tm:tick:name:sender)
- `:telemetry/name` - Metric name (e.g., "temp", "pos_x")
- `:telemetry/value` - Metric value (number or string)
- `:telemetry/type` - Type (:numeric, :structured, :error)
- `:telemetry/tick` - Device tick counter
- `:telemetry/device-time-us` - Device timestamp in microseconds
- `:telemetry/device-time-str` - Formatted device time string
- `:telemetry/sender` - Sender address
- `:telemetry/message` - Message ID from prelude
- `:telemetry/message-tm` - Message timestamp from prelude
- `:telemetry/ingested-at-ms` - Wall-clock time when ingested
- `:telemetry/table` - Table name (default: :telemetry/metrics)
- `:telemetry/fields` - Structured fields (if type is :structured)
- `:telemetry/error` - Error message (if type is :error)

## Service Management

```clojure
;; Check status
(user/status)

;; Start XTDB
(user/start-xtdb! {:in-memory? true})

;; Stop XTDB
(user/stop-xtdb!)

;; Restart everything
(user/restart!)
```

## Notes

- **In-memory storage**: Uses `xtdb.api/client` with JDBC URL, which works on Java 25
- **Automatic integration**: When XTDB is running, the web server automatically connects the telemetry stream
- **Asynchronous storage**: Metrics are submitted asynchronously and may not be immediately queryable
- **No persistence**: In-memory storage is lost on restart (use external XTDB server for persistence)
