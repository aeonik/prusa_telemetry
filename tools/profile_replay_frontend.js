#!/usr/bin/env node

const fs = require("fs");
const http = require("http");
const net = require("net");
const path = require("path");
const { spawn } = require("child_process");
const WebSocket = require("ws");

const DEFAULT_ARCHIVE =
  "2026-06-15:Juniper_Nebulizer_v9_0.4n_0.15mm_PP_Prusa_MK4S__job-231_run-20260615-160941-316.edn";

function parseArgs(argv) {
  const args = {
    url: "http://localhost:9632/replay",
    archive: process.env.ARCHIVE || DEFAULT_ARCHIVE,
    gcode: process.env.GCODE_FILE || null,
    out: path.join("target", "profiles", `replay-frontend-${timestamp()}`),
    timeoutMs: 180000,
    chromium: process.env.CHROMIUM || "chromium",
    headless: true,
  };

  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = argv[i + 1];
    if (arg === "--url") {
      args.url = next;
      i += 1;
    } else if (arg === "--archive") {
      args.archive = next;
      i += 1;
    } else if (arg === "--gcode") {
      args.gcode = next;
      i += 1;
    } else if (arg === "--out") {
      args.out = next;
      i += 1;
    } else if (arg === "--timeout-ms") {
      args.timeoutMs = Number(next);
      i += 1;
    } else if (arg === "--chromium") {
      args.chromium = next;
      i += 1;
    } else if (arg === "--headed") {
      args.headless = false;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return args;
}

function splitArchive(archive) {
  const idx = archive.indexOf(":");
  if (idx < 0) {
    throw new Error(`Archive must be date:filename, got: ${archive}`);
  }
  return {
    date: archive.slice(0, idx),
    filename: archive.slice(idx + 1),
  };
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function mkdirp(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function writeJson(file, data) {
  fs.writeFileSync(file, JSON.stringify(data, null, 2));
}

function normalizeProfileUrl(url) {
  if (!url) {
    return "(runtime)";
  }
  const cljsRuntime = "/js/cljs-runtime/";
  const cljsIdx = url.indexOf(cljsRuntime);
  if (cljsIdx >= 0) {
    return url.slice(cljsIdx + cljsRuntime.length);
  }
  const jsIdx = url.indexOf("/js/");
  if (jsIdx >= 0) {
    return url.slice(jsIdx + 4);
  }
  return url;
}

function summarizeCpuProfile(profile, limit = 40) {
  const nodesById = new Map((profile.nodes || []).map((node) => [node.id, node]));
  const selfMsById = new Map();
  const samples = profile.samples || [];
  const timeDeltas = profile.timeDeltas || [];

  for (let i = 0; i < samples.length; i += 1) {
    const nodeId = samples[i];
    const ms = (timeDeltas[i] || 0) / 1000;
    selfMsById.set(nodeId, (selfMsById.get(nodeId) || 0) + ms);
  }

  const topSelf = Array.from(selfMsById.entries())
    .map(([nodeId, selfMs]) => {
      const node = nodesById.get(nodeId) || {};
      const callFrame = node.callFrame || {};
      return {
        selfMs,
        functionName: callFrame.functionName || "(anonymous)",
        url: normalizeProfileUrl(callFrame.url),
        line: Number.isFinite(callFrame.lineNumber) ? callFrame.lineNumber + 1 : null,
      };
    })
    .sort((a, b) => b.selfMs - a.selfMs)
    .slice(0, limit);

  const byUrl = new Map();
  for (const [nodeId, selfMs] of selfMsById.entries()) {
    const node = nodesById.get(nodeId) || {};
    const callFrame = node.callFrame || {};
    const url = normalizeProfileUrl(callFrame.url);
    byUrl.set(url, (byUrl.get(url) || 0) + selfMs);
  }

  return {
    totalProfileMs:
      Number.isFinite(profile.startTime) && Number.isFinite(profile.endTime)
        ? (profile.endTime - profile.startTime) / 1000
        : null,
    sampledSelfMs: Array.from(selfMsById.values()).reduce((sum, ms) => sum + ms, 0),
    sampleCount: samples.length,
    topSelf,
    byUrl: Array.from(byUrl.entries())
      .map(([url, selfMs]) => ({ url, selfMs }))
      .sort((a, b) => b.selfMs - a.selfMs)
      .slice(0, limit),
  };
}

function writeCpuSummaryText(file, summary) {
  const lines = [];
  lines.push(`totalProfileMs=${summary.totalProfileMs}`);
  lines.push(`sampledSelfMs=${summary.sampledSelfMs.toFixed(1)}`);
  lines.push(`sampleCount=${summary.sampleCount}`);
  lines.push("");
  lines.push("Top self time:");
  for (const row of summary.topSelf) {
    const location = row.line ? `${row.url}:${row.line}` : row.url;
    lines.push(`${row.selfMs.toFixed(1)}ms\t${row.functionName}\t${location}`);
  }
  lines.push("");
  lines.push("By URL:");
  for (const row of summary.byUrl) {
    lines.push(`${row.selfMs.toFixed(1)}ms\t${row.url}`);
  }
  fs.writeFileSync(file, `${lines.join("\n")}\n`);
}

function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
    server.on("error", reject);
  });
}

function httpJson(url) {
  return new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        let data = "";
        res.on("data", (chunk) => {
          data += chunk;
        });
        res.on("end", () => {
          try {
            resolve(JSON.parse(data));
          } catch (err) {
            reject(err);
          }
        });
      })
      .on("error", reject);
  });
}

async function waitFor(fn, timeoutMs, label) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const value = await fn();
    if (value) {
      return value;
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

class Cdp {
  constructor(wsUrl) {
    this.nextId = 1;
    this.pending = new Map();
    this.ws = new WebSocket(wsUrl);
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws.once("open", resolve);
      this.ws.once("error", reject);
      this.ws.on("message", (data) => {
        const msg = JSON.parse(data.toString());
        if (msg.id && this.pending.has(msg.id)) {
          const { resolve, reject } = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          if (msg.error) {
            reject(new Error(`${msg.error.message}: ${msg.error.data || ""}`));
          } else {
            resolve(msg.result || {});
          }
        }
      });
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
  }

  close() {
    this.ws.close();
  }
}

async function runtimeValue(cdp, expression, awaitPromise = false) {
  const result = await cdp.send("Runtime.evaluate", {
    expression,
    awaitPromise,
    returnByValue: true,
  });
  if (result.exceptionDetails) {
    throw new Error(JSON.stringify(result.exceptionDetails));
  }
  return result.result.value;
}

async function run() {
  const args = parseArgs(process.argv);
  mkdirp(args.out);

  if (!args.gcode) {
    throw new Error("G-code path is required. Pass --gcode or set GCODE_FILE.");
  }
  if (!fs.existsSync(args.gcode)) {
    throw new Error(`G-code file does not exist: ${args.gcode}`);
  }

  const port = await getFreePort();
  const userDataDir = path.join(args.out, "chrome-user-data");
  mkdirp(userDataDir);

  const chromeArgs = [
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    "--no-first-run",
    "--disable-default-apps",
    "--disable-gpu",
    "--no-sandbox",
  ];
  if (args.headless) {
    chromeArgs.push("--headless=new");
  }
  chromeArgs.push("about:blank");

  const chrome = spawn(args.chromium, chromeArgs, {
    stdio: ["ignore", "ignore", "pipe"],
  });
  const chromeErrors = [];
  chrome.stderr.on("data", (chunk) => {
    chromeErrors.push(chunk.toString());
  });

  try {
    await waitFor(
      () =>
        httpJson(`http://127.0.0.1:${port}/json/version`).catch(() => null),
      15000,
      "Chrome DevTools endpoint"
    );

    const pages = await httpJson(`http://127.0.0.1:${port}/json/list`);
    const page = pages.find((p) => p.type === "page");
    if (!page) {
      throw new Error("No Chrome page target found");
    }

    const cdp = new Cdp(page.webSocketDebuggerUrl);
    await cdp.open();
    await cdp.send("Page.enable");
    await cdp.send("DOM.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("Performance.enable");
    await cdp.send("Profiler.enable");
    await cdp.send("Profiler.start");

    const marks = { startedAt: new Date().toISOString() };
    const loadStart = Date.now();
    await cdp.send("Page.navigate", { url: args.url });
    await waitFor(
      () =>
        runtimeValue(cdp, "document.readyState === 'complete'").catch(
          () => false
        ),
      30000,
      "page load"
    );
    marks.pageLoadMs = Date.now() - loadStart;

    const archiveParts = splitArchive(args.archive);
    const selectStart = Date.now();
    const loaderResult = await runtimeValue(
      cdp,
      `(() => {
        if (!globalThis.aeonik || !aeonik.files || !aeonik.files.load_telemetry_file_replace) {
          return {loaded: false, reason: "aeonik.files.load_telemetry_file_replace is not available"};
        }
        aeonik.files.load_telemetry_file_replace(
          ${JSON.stringify(archiveParts.date)},
          ${JSON.stringify(archiveParts.filename)}
        );
        return {loaded: true};
      })()`
    );
    if (!loaderResult.loaded) {
      throw new Error(`Replay loader was not started: ${JSON.stringify(loaderResult)}`);
    }

    const doc = await cdp.send("DOM.getDocument", {});
    const input = await cdp.send("DOM.querySelector", {
      nodeId: doc.root.nodeId,
      selector: "#replay-gcode-file",
    });
    if (!input.nodeId) {
      throw new Error("Could not find #replay-gcode-file");
    }
    await cdp.send("DOM.setFileInputFiles", {
      nodeId: input.nodeId,
      files: [args.gcode],
    });

    await waitFor(
      () =>
        runtimeValue(
          cdp,
          `(() => {
            const body = document.body.innerText;
            const cards = document.querySelectorAll('.replay-metric-card').length;
            const loading = body.includes('Loading replay data');
            const gcodeLoaded = !body.includes('No G-code loaded') && !body.includes('Parsing G-code');
            return !loading && gcodeLoaded && cards > 0 ? {cards, text: body.slice(0, 2000)} : null;
          })()`
        ).catch(() => null),
      args.timeoutMs,
      "replay data and gcode parse"
    );
    marks.replayReadyMs = Date.now() - selectStart;

    await sleep(1000);
    const profile = await cdp.send("Profiler.stop");
    const cpuSummary = summarizeCpuProfile(profile.profile);
    const metrics = await cdp.send("Performance.getMetrics");
    const heap = await cdp.send("Runtime.getHeapUsage").catch((err) => ({
      error: err.message,
    }));
    const pageSummary = await runtimeValue(
      cdp,
      `(() => ({
        title: document.title,
        metricCards: document.querySelectorAll('.replay-metric-card').length,
        bodyTextPrefix: document.body.innerText.slice(0, 2000),
        performanceMemory: performance.memory ? {
          usedJSHeapSize: performance.memory.usedJSHeapSize,
          totalJSHeapSize: performance.memory.totalJSHeapSize,
          jsHeapSizeLimit: performance.memory.jsHeapSizeLimit
        } : null
      }))()`
    );
    const screenshot = await cdp.send("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: false,
    });

    fs.writeFileSync(
      path.join(args.out, "frontend.cpuprofile"),
      JSON.stringify(profile.profile)
    );
    writeJson(path.join(args.out, "frontend-cpu-summary.json"), cpuSummary);
    writeCpuSummaryText(path.join(args.out, "frontend-cpu-summary.txt"), cpuSummary);
    writeJson(path.join(args.out, "frontend-metrics.json"), metrics);
    writeJson(path.join(args.out, "frontend-heap.json"), heap);
    writeJson(path.join(args.out, "frontend-summary.json"), {
      args,
      marks,
      pageSummary,
    });
    fs.writeFileSync(
      path.join(args.out, "frontend-screenshot.png"),
      Buffer.from(screenshot.data, "base64")
    );

    cdp.close();
    chrome.kill("SIGTERM");
    console.log(`Frontend replay profile written to ${args.out}`);
    console.log(
      JSON.stringify(
        {
          pageLoadMs: marks.pageLoadMs,
          replayReadyMs: marks.replayReadyMs,
          metricCards: pageSummary.metricCards,
          heap,
        },
        null,
        2
      )
    );
  } catch (err) {
    chrome.kill("SIGTERM");
    fs.writeFileSync(
      path.join(args.out, "chrome-stderr.log"),
      chromeErrors.join("")
    );
    throw err;
  }
}

run().catch((err) => {
  console.error(err.stack || err.message || err);
  process.exit(1);
});
