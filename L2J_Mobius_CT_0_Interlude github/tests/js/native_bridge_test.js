/*
 * Regression harness for tools/l2admin/native-bridge.js - the desktop-app file
 * bridge that replaces the browser File System Access API when l2admin runs in
 * the WebView2 host (L2Admin-App.ps1).
 *
 * It installs the bridge into a FAKE window whose chrome.webview is wired to an
 * in-memory file system, then exercises the same handle calls index.html makes:
 * showDirectoryPicker, values(), getFileHandle/getDirectoryHandle, getFile, and
 * createWritable round trips. This protects the contract between the page and
 * the PowerShell host without needing Windows or a real browser.
 *
 * Usage: node tests/js/native_bridge_test.js
 */
"use strict";
const path = require("path");
const bridge = require(path.resolve(__dirname, "..", "..", "tools", "l2admin", "native-bridge.js"));

let pass = 0, fail = 0;
function ok(cond, msg) { if (cond) pass++; else { fail++; console.log("FAIL:", msg); } }
function eqBytes(a, e, msg) { ok(JSON.stringify(Array.from(a)) === JSON.stringify(Array.from(e)), msg); }

// ---- in-memory host --------------------------------------------------------
// Files keyed by absolute path using "/" separators (the bridge joins with "/").
function makeHost(seed) {
  const files = new Map(Object.entries(seed || {}));        // path -> Uint8Array
  const dirs = new Set();
  for (const p of files.keys()) {                            // register ancestor dirs
    let d = p.replace(/\/[^/]*$/, "");
    while (d && !dirs.has(d)) { dirs.add(d); d = d.replace(/\/[^/]*$/, ""); }
  }
  function isDir(p) { return dirs.has(p); }
  function childrenOf(p) {
    const out = new Map();
    const prefix = p.replace(/\/+$/, "") + "/";
    for (const f of files.keys()) if (f.startsWith(prefix) && f.slice(prefix.length).indexOf("/") < 0) out.set(f.slice(prefix.length), "file");
    for (const d of dirs) if (d.startsWith(prefix) && d.slice(prefix.length).length && d.slice(prefix.length).indexOf("/") < 0) out.set(d.slice(prefix.length), "directory");
    return out;
  }
  // A minimal window with a chrome.webview that answers ops synchronously.
  const listeners = [];
  const b64 = {
    enc: (bytes) => Buffer.from(bytes).toString("base64"),
    dec: (s) => new Uint8Array(Buffer.from(s || "", "base64"))
  };
  function handle(msg) {
    const reply = (ok, result, error, name) => {
      const payload = ok ? { rid: msg.rid, ok: true, result } : { rid: msg.rid, ok: false, error, name };
      // Deliver asynchronously like the real bridge (PostWebMessageAsJson).
      setTimeout(() => listeners.forEach(fn => fn({ data: payload })), 0);
    };
    switch (msg.op) {
      case "pickDir": return reply(true, { path: "C:/game", name: "game" });
      case "pickFile": return reply(true, { path: "C:/game/config/NPC.ini", name: "NPC.ini" });
      case "list": {
        const entries = [];
        for (const [name, kind] of childrenOf(msg.path)) entries.push({ name, kind });
        return reply(true, { entries });
      }
      case "stat": {
        if (files.has(msg.path)) return reply(true, { exists: true, kind: "file" });
        if (isDir(msg.path)) return reply(true, { exists: true, kind: "directory" });
        return reply(true, { exists: false });
      }
      case "readBytes": {
        if (!files.has(msg.path)) return reply(false, null, "not found", "NotFoundError");
        return reply(true, { base64: b64.enc(files.get(msg.path)) });
      }
      case "writeBytes": {
        files.set(msg.path, b64.dec(msg.base64));
        let d = msg.path.replace(/\/[^/]*$/, "");
        while (d && !dirs.has(d)) { dirs.add(d); d = d.replace(/\/[^/]*$/, ""); }
        return reply(true, { ok: true });
      }
      case "mkdir": { dirs.add(msg.path.replace(/\/+$/, "")); return reply(true, { ok: true }); }
      default: return reply(false, null, "unknown op", "NotSupportedError");
    }
  }
  const win = {
    File: class FakeFile {                                   // just enough of File for getFile().text()
      constructor(parts, name) { this._bytes = parts[0]; this.name = name; }
      async text() { return Buffer.from(this._bytes).toString("utf8"); }
      async arrayBuffer() { return this._bytes.buffer.slice(this._bytes.byteOffset, this._bytes.byteOffset + this._bytes.byteLength); }
    },
    chrome: { webview: {
      postMessage: (m) => handle(m),
      addEventListener: (_evt, fn) => listeners.push(fn)
    } }
  };
  return { win, files };
}

// ---- tests -----------------------------------------------------------------
(async () => {
  const seed = {
    "C:/game/config/Rates.ini": new TextEncoder().encode("RateXp=1\r\n"),
    "C:/game/config/NPC.ini": new TextEncoder().encode("; npc\r\n"),
    "C:/game/data/PhantomPlaystyles.xml": new TextEncoder().encode("<list/>\n")
  };
  const { win, files } = makeHost(seed);
  bridge.installInto(win);

  ok(typeof win.showDirectoryPicker === "function", "showDirectoryPicker installed");
  ok(win.__l2native && typeof win.__l2native.openGameDir === "function", "__l2native handoff installed");

  const root = await win.showDirectoryPicker();
  ok(root.kind === "directory" && root.name === "game", "picker returns game dir handle");
  ok((await root.queryPermission()) === "granted", "queryPermission is always granted");

  // Enumerate the game folder: should see config/ and data/ directories.
  const top = [];
  for await (const e of root.values()) top.push(e.kind + ":" + e.name);
  ok(top.includes("directory:config") && top.includes("directory:data"), "values() lists subdirs, got " + top.join(","));

  // Walk into config and detect .ini files (the exact thing that broke in-browser).
  const config = await root.getDirectoryHandle("config");
  const inis = [];
  for await (const e of config.values()) if (e.kind === "file" && e.name.endsWith(".ini")) inis.push(e.name);
  ok(inis.sort().join(",") === "NPC.ini,Rates.ini", "ini detection under config, got " + inis.join(","));

  // Read a file through a handle.
  const fh = await config.getFileHandle("Rates.ini");
  const text = await (await fh.getFile()).text();
  ok(text === "RateXp=1\r\n", "getFile round trip");

  // getFileHandle on a missing file must reject (used as an existence probe).
  let threw = false;
  try { await config.getFileHandle("Missing.ini"); } catch (_) { threw = true; }
  ok(threw, "getFileHandle rejects a missing file");

  // Write a file in place and confirm the bytes reached the host.
  const w = await fh.createWritable();
  await w.write("RateXp=5\r\n");
  await w.close();
  eqBytes(files.get("C:/game/config/Rates.ini"), new TextEncoder().encode("RateXp=5\r\n"), "createWritable persisted new bytes");

  // Create a new directory + file (the crest-writing path).
  const crests = await (await root.getDirectoryHandle("data")).getDirectoryHandle("crests", { create: true });
  const cf = await crests.getFileHandle("pledge.dds", { create: true });
  const cw = await cf.createWritable();
  await cw.write(new Uint8Array([1, 2, 3, 4]));
  await cw.close();
  eqBytes(files.get("C:/game/data/crests/pledge.dds"), new Uint8Array([1, 2, 3, 4]), "create dir + binary write");

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch(err => { console.error("harness error:", err); process.exit(1); });
