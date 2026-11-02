/**
 * Tests for stage 3.3d — `reference_grid` (the contact sheet).
 *
 * The contract these tests defend:
 *  * Selection is explicit (ids / views / role / set); `set` means the trusted
 *    folder an image was loaded from, and a crop inherits it through the
 *    recorded parent_ref — NEVER through a name match.
 *  * Every tile keeps its aspect ratio (contain-fit inside the cell); a tile
 *    that cannot decode is a PLACEHOLDER + a per-tile error, never a blank sheet.
 *  * Memory bounds are enforced BEFORE a canvas exists: cell ≤ 512, cols ≤ 6,
 *    tiles ≤ 48, sheet ≤ 4096×4096 (refused with the largest fitting cell
 *    named — never silently shrunk). The inline copy is downscaled to ≤ 2048.
 *  * Saving goes through the 3.3a guard only: trusted roots, and an existing
 *    file is NEVER replaced (O_EXCL — reported as saved_error, the sheet still
 *    returned). The response carries the sheet only — never a stored
 *    reference's internal data URL.
 *
 *   npm test
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN = path.join(here, "..", "plugin", "blockbench_mcp.js");
const source = fs.readFileSync(PLUGIN, "utf8");

const REGISTER = "Plugin.register(PLUGIN_ID, {";
assert.ok(source.includes(REGISTER), "plugin registration call moved — update this harness");
const EXPORTS =
  "globalThis.__MCP_GRID__ = { commands, G, MULTI_VIEWS, normaliseViewLabel, pickReference, " +
  "gridRoleOf, gridSetDirOf, GRID_LIMITS, refBatchLimit, guardPath, guardedWrite, addReference };\n";
const instrumented = source.replace(REGISTER, EXPORTS + REGISTER);

const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const plain = (v) => JSON.parse(JSON.stringify(v));

/** Descriptor bytes for an image that decodes to w x h (with a PNG signature). */
function imgBytes(label, w, h) {
  return Buffer.concat([Buffer.from(PNG_SIG), Buffer.from("IMG|" + label + "|" + w + "|" + h, "utf8")]);
}
function bytesToURL(buf) {
  return "data:image/png;base64," + buf.toString("base64");
}
function sizeFromText(text) {
  if (text.indexOf("CORRUPT") >= 0 && text.indexOf("IMG|") < 0) return null;
  const m = /IMG\|([^|]*)\|(\d+)\|(\d+)/.exec(text);
  if (!m) return null;
  return { w: Number(m[2]), h: Number(m[3]) };
}

// A path-only fs, shaped exactly like Blockbench's scoped wrapper: no fd API,
// writeFileSync honours {flag:'wx'} with a real EEXIST (the live-probed facts).
function makeFs() {
  let inoSeq = 900;
  let timeSeq = 100;
  const nodes = new Map();
  const norm = (p) => String(p).replace(/\\/g, "/").replace(/([^:])\/{2,}/g, "$1/").replace(/\/+$/, "") || "/";
  const parentOf = (p) => { const i = p.lastIndexOf("/"); return i <= 0 ? "/" : p.slice(0, i); };
  const baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);
  const api = {
    _nodes: nodes, norm,
    mkdir(p) { nodes.set(norm(p), { kind: "dir", ino: ++inoSeq, dev: 3, mtime: ++timeSeq }); return p; },
    put(p, buf) { nodes.set(norm(p), { kind: "file", buf: Buffer.isBuffer(buf) ? buf : Buffer.from(String(buf)), ino: ++inoSeq, dev: 3, mtime: ++timeSeq }); return norm(p); },
    statSync(p) {
      const n = nodes.get(norm(p));
      if (!n) throw Object.assign(new Error("ENOENT: " + p), { code: "ENOENT" });
      return { isFile: () => n.kind === "file", isDirectory: () => n.kind === "dir", size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev, mtime: n.mtime };
    },
    readFileSync(a) {
      if (typeof a === "number") throw new Error("openSync is not a function");
      const n = nodes.get(norm(a));
      if (!n) throw Object.assign(new Error("ENOENT: " + a), { code: "ENOENT" });
      return n.buf;
    },
    writeFileSync(a, data, options) {
      if (typeof a === "number") throw new Error("fd write reached without an fd API");
      const key = norm(a);
      if (options && options.flag === "wx" && nodes.has(key)) {
        throw Object.assign(new Error("EEXIST: file already exists, open '" + key + "'"), { code: "EEXIST" });
      }
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
      nodes.set(key, { kind: "file", buf, ino: ++inoSeq, dev: 3, mtime: ++timeSeq });
      return key;
    },
    readdirSync(p) {
      const dir = norm(p);
      const out = [];
      for (const key of nodes.keys()) if (parentOf(key) === dir && key !== dir) out.push(baseOf(key));
      return out.sort();
    },
    existsSync(p) { return nodes.has(norm(p)); },
    mkdirSync(p) { return api.mkdir(p); },
  };
  return api;
}

function load({ roots = "", fsImpl = null } = {}) {
  const fakeFs = fsImpl || makeFs();
  class Image {
    constructor() { this.naturalWidth = 0; this.naturalHeight = 0; this.width = 0; this.height = 0; this.onload = null; this.onerror = null; this._src = ""; }
    get src() { return this._src; }
    set src(value) {
      this._src = value;
      setTimeout(() => {
        try {
          const b64 = String(value).replace(/^data:[^;]+;base64,/, "");
          const text = Buffer.from(b64, "base64").toString("utf8");
          const size = sizeFromText(text);
          if (!size) throw new Error("Could not decode image data.");
          this.naturalWidth = this.width = size.w;
          this.naturalHeight = this.height = size.h;
          if (this.onload) this.onload();
        } catch (e) { if (this.onerror) this.onerror(e); }
      }, 0);
    }
  }
  const drawn = []; // every fillText call, so label content can be asserted
  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    Buffer,
    isApp: true,
    require: (n) => (n === "fs" ? fakeFs : undefined),
    Plugin: { register() {} },
    Blockbench: { version: "5.2.2", showQuickMessage() {}, addCSS() {} },
    Project: null, Format: null, Formats: {}, Mode: { selected: { id: "edit" } },
    Texture: Object.assign([], { all: [], getDefault: () => null }),
    Animation: { all: [] },
    Undo: { initEdit() {}, finishEdit() {} },
    Canvas: { updateAll() {} },
    Outliner: { root: [], elements: [] },
    Group: Object.assign(class {}, { all: [] }),
    Cube: Object.assign(class {}, { all: [] }),
    Mesh: Object.assign(class {}, { all: [] }),
    Image,
    document: {
      createElement() {
        return {
          width: 64, height: 64,
          getContext() {
            return {
              fillStyle: "",
              fillRect() {},
              drawImage() {},
              fillText(t, x, y) { drawn.push({ t: String(t), x, y }); },
              set textBaseline(_v) {}, get textBaseline() { return "top"; },
            };
          },
          // The sheet PNG: signature + the canvas's own size encoded as an IMG
          // descriptor, so the inline-downscale re-decode lands on the right size.
          toDataURL() {
            return bytesToURL(imgBytes("sheet", this.width, this.height));
          },
        };
      },
    },
    settings: { blockbench_mcp_ref_roots: { get: () => roots } },
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });
  return { api: sandbox.__MCP_GRID__, fakeFs, drawn };
}

/** Seed a reference the same way the real loaders store them. */
function seed(pl, { name, view = null, extra = false, part = null, parent_ref = null, dir = null, w = 64, h = 64, broken = false }) {
  const entry = {
    id: "ref_" + (pl.api.G.references.length + 1),
    name,
    data_url: broken
      ? bytesToURL(Buffer.concat([Buffer.from(PNG_SIG), Buffer.from("CORRUPT|no-image")]))
      : bytesToURL(imgBytes(name, w, h)),
    width: w, height: h,
    view, extra, part, parent_ref, dir,
    quality: { value: broken ? null : "alpha" },
    source: "dir", added_at: Date.now(), ref_image: null, sha256: "x".repeat(64),
  };
  if (part) entry.origin = "crop";
  pl.api.G.references.push(entry);
  return entry;
}

const ROOT = "/home/dev/refs";
const SET = ROOT + "/hero";

// ===========================================================================
// selection
// ===========================================================================

test("empty store: an honest zero, no canvas, no fake sheet", async () => {
  const pl = load();
  const res = await pl.api.commands.reference_grid({});
  assert.equal(res.count, 0);
  assert.equal(res.grid, null);
  assert.equal(res.data_url, undefined, "no image is produced from nothing");
  assert.match(res.note, /No references are loaded/);
});

test("filter that matches nothing: zero count, a note, still no throw", async () => {
  const pl = load();
  seed(pl, { name: "front.png", view: "front" });
  const res = await pl.api.commands.reference_grid({ views: ["top"] });
  assert.equal(res.count, 0);
  assert.equal(res.meta.matched, 0);
  assert.match(res.note, /No reference matches this selection/);
});

test("views filter keeps only whole-view references bound to those views", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  seed(pl, { name: "b", view: "back" });
  seed(pl, { name: "iso", extra: true });
  const res = await pl.api.commands.reference_grid({ views: ["front", "back"] });
  assert.equal(res.count, 2);
  assert.deepEqual(plain(res.tiles.map((t) => t.name)), ["f", "b"]);
});

test("unknown view names are selection errors, not silent filters", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  const res = await pl.api.commands.reference_grid({ views: ["frontish"] });
  assert.equal(res.count, 0);
  assert.equal(res.meta.selection_errors.length, 1);
  assert.match(res.meta.selection_errors[0].error, /not one of front, back, left, right, top/);
});

test("role filter: view / extra / part, and an unknown role throws", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  seed(pl, { name: "iso", extra: true });
  seed(pl, { name: "head", part: "head", parent_ref: "ref_1", extra: true });
  assert.deepEqual(plain((await pl.api.commands.reference_grid({ role: "part" })).tiles.map((t) => t.name)), ["head"]);
  assert.deepEqual(plain((await pl.api.commands.reference_grid({ role: "extra" })).tiles.map((t) => t.name)), ["iso"], "a part is part, not extra");
  assert.deepEqual(plain((await pl.api.commands.reference_grid({ role: "view" })).tiles.map((t) => t.name)), ["f"]);
  await assert.rejects(() => pl.api.commands.reference_grid({ role: "blob" }), /Unknown role "blob"/);
});

test("set filter uses the recorded dir; a crop inherits its parent's set via parent_ref only", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front", dir: SET });
  seed(pl, { name: "other", view: "back", dir: ROOT + "/villain" });
  seed(pl, { name: "f / head", part: "head", parent_ref: "ref_1", extra: true }); // no own dir
  const res = await pl.api.commands.reference_grid({ set: SET });
  assert.deepEqual(plain(res.tiles.map((t) => t.name)), ["f", "f / head"], "the crop followed its parent, not its name");
  const byId = new Map(pl.api.G.references.map((r) => [r.id, r]));
  assert.equal(pl.api.gridSetDirOf(byId.get("ref_2"), byId, 0), ROOT + "/villain");
});

test("ids: explicit order is honoured, unknown selectors are per-id errors, others still draw", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  seed(pl, { name: "b", view: "back" });
  const res = await pl.api.commands.reference_grid({ ids: ["ref_2", "ghost", "f"] });
  assert.deepEqual(plain(res.tiles.map((t) => t.name)), ["b", "f"], "given order, not store order");
  assert.equal(res.meta.selection_errors.length, 1);
  assert.match(res.meta.selection_errors[0].error, /no reference matches/);
});

// ===========================================================================
// layout bounds — enforced BEFORE a canvas exists
// ===========================================================================

test("cell is capped at 512 and cols at 6; nonsense values throw", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  await assert.rejects(() => pl.api.commands.reference_grid({ cell: 513 }), /cell 513 is outside the allowed 24\.\.512/);
  await assert.rejects(() => pl.api.commands.reference_grid({ cols: 7 }), /cols 7 is outside the allowed 1\.\.6/);
  await assert.rejects(() => pl.api.commands.reference_grid({ cell: "banana" }), /cell must be a positive number/);
  const ok = await pl.api.commands.reference_grid({ cell: 512, cols: 6 });
  assert.equal(ok.grid.cell, 512);
});

test("max_cells is capped at 48; extra matches are reported as omitted, not drawn", async () => {
  const pl = load();
  for (let i = 0; i < 4; i++) seed(pl, { name: "r" + i, view: "front" });
  const res = await pl.api.commands.reference_grid({ max_cells: 2 });
  assert.equal(res.count, 2);
  assert.equal(res.meta.shown, 2);
  assert.equal(res.meta.omitted.length, 2);
  await assert.rejects(() => pl.api.commands.reference_grid({ max_cells: 49 }), /exceeds the hard cap 48/);
});

test("a sheet over 4096 on either side is REFUSED with the largest fitting cell named", async () => {
  const pl = load();
  for (let i = 0; i < 48; i++) seed(pl, { name: "r" + i, view: "front" });
  // 48 tiles @ cell=512, cols=6 -> 8 rows: H = 26+10+8*522 = 4212 > 4096
  await assert.rejects(
    () => pl.api.commands.reference_grid({ cell: 512, cols: 6, max_cells: 48 }),
    (e) => /over the 4096 px side limit \(48 tiles @ cell=512, cols=6\)/.test(e.message) && /Use cell <= \d+/.test(e.message),
  );
  // width overflow: 1 tile can't do it; 2x cell 2000 would break the cell rule first.
  // So drive the W side via cols=6 cell=512 with few rows (W = 10+6*522 = 3142 — fits).
  // Height is the only reachable overflow at these caps; that is by design.
  const fit = await pl.api.commands.reference_grid({ cell: 497, cols: 6, max_cells: 48 });
  assert.ok(fit.grid.height <= 4096 && fit.grid.width <= 4096, "the named cell does fit");
});

test("the sheet is a rectangle: W/H equal cols/rows computed from the tile count", async () => {
  const pl = load();
  for (let i = 0; i < 7; i++) seed(pl, { name: "r" + i, view: "front" });
  const res = await pl.api.commands.reference_grid({ cell: 100 });
  assert.equal(res.grid.cols, 5);            // default max 5, sized to count
  assert.equal(res.grid.rows, 2);
  assert.equal(res.grid.width, 10 + 5 * 110);
  assert.equal(res.grid.height, 26 + 10 + 2 * 110);
});

// ===========================================================================
// tiles: aspect, labels, decode failures
// ===========================================================================

test("every tile keeps its aspect ratio inside the cell (non-square 300x100)", async () => {
  const pl = load();
  seed(pl, { name: "wide", view: "front", w: 300, h: 100 });
  const res = await pl.api.commands.reference_grid({ cell: 100 });
  const t = res.tiles[0];
  assert.equal(t.drawn, true);
  const boxW = 100 - 8, boxH = 100 - 8 - 36; // label row
  assert.ok(t.fit.w <= boxW && t.fit.h <= boxH, "fit inside the box");
  assert.ok(Math.abs(t.fit.w / t.fit.h - 3) < 0.2, `aspect ~3:1, got ${t.fit.w / t.fit.h}`);
});

test("a broken image is a placeholder + per-tile error; the sheet still renders", async () => {
  const pl = load();
  seed(pl, { name: "good", view: "front" });
  seed(pl, { name: "bad", view: "back", broken: true });
  const res = await pl.api.commands.reference_grid({});
  assert.equal(res.count, 2, "both tiles exist");
  const bad = res.tiles.find((t) => t.name === "bad");
  assert.equal(bad.drawn, false);
  assert.match(bad.error, /Could not decode|decode/i);
  assert.equal(res.errors.length, 1);
  assert.ok(res.data_url.startsWith("data:image/png"), "the sheet was produced anyway");
});

test("annotate defaults ON and labels carry role/view, size, parent and quality", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front", w: 320, h: 180 });
  seed(pl, { name: "f / head", part: "head", parent_ref: "ref_1", extra: true });
  await pl.api.commands.reference_grid({});
  const texts = pl.drawn.map((d) => d.t).join("\n");
  assert.match(texts, /f/, "name label");
  assert.match(texts, /320x180/, "real pixel size");
  assert.match(texts, /part.*of f\b|of f/, "the parent is named");
  assert.match(texts, /view front|front/, "the view is shown");
  const pl2 = load();
  seed(pl2, { name: "f", view: "front" });
  const res2 = await pl2.api.commands.reference_grid({ annotate: false });
  assert.equal(res2.grid.annotate, false);
});

test("role counts in meta describe the selection, not the store", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  seed(pl, { name: "iso", extra: true });
  const res = await pl.api.commands.reference_grid({ role: "extra" });
  assert.deepEqual(plain(res.role_counts), { view: 0, extra: 1, part: 0 });
});

// ===========================================================================
// output: inline cap, no source data URLs, save through the guard
// ===========================================================================

test("the response NEVER carries a stored reference's internal data URL", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  seed(pl, { name: "iso", extra: true });
  const res = await pl.api.commands.reference_grid({});
  const tilesJson = JSON.stringify(res.tiles) + JSON.stringify(res.meta);
  assert.ok(!tilesJson.includes("base64"), "tiles/meta carry no image payloads");
  const b64s = JSON.stringify(res).match(/base64/g) || [];
  assert.equal(b64s.length, 1, "exactly one base64 in the whole response: the sheet itself");
});

test("the inline copy is downscaled to 2048 px; the full sheet geometry is reported", async () => {
  const pl = load();
  for (let i = 0; i < 5; i++) seed(pl, { name: "r" + i, view: "front" });
  // cell 512, cols 5 -> W = 10 + 5*522 = 2620 (> 2048)
  const res = await pl.api.commands.reference_grid({ cell: 512 });
  assert.equal(res.grid.width, 2620);
  assert.ok(res.inline_size.downscaled_from, "downscale happened");
  assert.equal(res.inline_size.downscaled_from.width, 2620);
  assert.ok(Math.max(res.inline_size.width, res.inline_size.height) <= 2048, `inline ${res.inline_size.width}x${res.inline_size.height}`);
});

test("inline:false skips the image payload entirely (metadata still there)", async () => {
  const pl = load();
  seed(pl, { name: "f", view: "front" });
  const res = await pl.api.commands.reference_grid({ inline: false });
  assert.equal(res.data_url, undefined);
  assert.equal(res.count, 1);
  assert.ok(res.grid);
});

const OUT = SET + "/sheets";

function seedFs() {
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT); f.mkdir(SET); f.mkdir(OUT);
  return f;
}

test("out_dir saves through the guard, inside a trusted root; degraded mode is honest", async () => {
  const f = seedFs();
  const pl = load({ roots: ROOT, fsImpl: f });
  seed(pl, { name: "f", view: "front" });
  const res = await pl.api.commands.reference_grid({ out_dir: OUT, name: "set_review" });
  assert.ok(res.saved, JSON.stringify(res.saved_error));
  assert.equal(res.saved.path, OUT + "/set_review.png");
  assert.equal(res.saved.hardened, false, "this fs has no fd API — reported, not pretended");
  const file = f._nodes.get(f.norm(OUT + "/set_review.png"));
  assert.ok(file && file.buf[0] === 0x89 && file.buf[1] === 0x50, "a real PNG landed on the fake disk");
  assert.equal(res.saved.bytes, file.buf.length);
});

test("an existing sheet is NEVER replaced: O_EXCL error is reported, the sheet still returns", async () => {
  const f = seedFs();
  f.put(OUT + "/set_review.png", "earlier sheet bytes");
  const pl = load({ roots: ROOT, fsImpl: f });
  seed(pl, { name: "f", view: "front" });
  const res = await pl.api.commands.reference_grid({ out_dir: OUT, name: "set_review" });
  assert.equal(res.saved, null);
  assert.match(res.saved_error, /cannot create .*exists or is a planted link/);
  assert.equal(f._nodes.get(f.norm(OUT + "/set_review.png")).buf.toString(), "earlier sheet bytes", "the old file survived");
  assert.ok(res.data_url.startsWith("data:image/png"), "the fresh sheet is still returned inline");
  assert.equal(res.count, 1);
});

test("out_dir outside every trusted root is refused as a save error, never silently skipped", async () => {
  const f = seedFs();
  f.mkdir("/elsewhere");
  const pl = load({ roots: ROOT, fsImpl: f });
  seed(pl, { name: "f", view: "front" });
  const res = await pl.api.commands.reference_grid({ out_dir: "/elsewhere" });
  assert.equal(res.saved, null);
  assert.match(res.saved_error, /outside every trusted reference root/);
  assert.ok(res.data_url, "the sheet itself is still delivered");
});

test("out_dir pointing at a file (not a directory) is a named save error", async () => {
  const f = seedFs();
  f.put(SET + "/notadir.png", imgBytes("x", 10, 10));
  const pl = load({ roots: ROOT, fsImpl: f });
  seed(pl, { name: "f", view: "front" });
  const res = await pl.api.commands.reference_grid({ out_dir: SET + "/notadir.png" });
  assert.match(res.saved_error, /is not a directory/);
});

// ===========================================================================
// wiring
// ===========================================================================

test("reference_grid is HEAVY on both sides and registered as a tool", async () => {
  const heavy = source.match(/const HEAVY_ACTIONS = \{[\s\S]*?\n\};/);
  assert.match(heavy[0], /reference_grid:\s*true/);
  const clientSrc = fs.readFileSync(path.join(here, "..", "src", "client.ts"), "utf8");
  assert.match(clientSrc, /"reference_grid"/);
  const { tools } = await import("../dist/tools.js");
  const tool = tools.find((t) => t.name === "reference_grid");
  assert.ok(tool, "catalogue entry");
  assert.deepEqual(tool.inputSchema.required ?? [], []);
  const props = tool.inputSchema.properties;
  for (const k of ["ids", "views", "role", "set", "cell", "cols", "max_cells", "annotate", "out_dir", "inline"]) {
    assert.ok(props[k], "exposes " + k);
  }
  assert.ok(!props.roots, "no roots parameter, ever");
  assert.match(tool.description, /never returns the stored references' internal data URLs/i);
});

test("gridRoleOf/gridSetDirOf stay pure: no dir + no parent = null set, plain = view", () => {
  const pl = load();
  assert.equal(pl.api.gridRoleOf({}), "view");
  assert.equal(pl.api.gridRoleOf({ extra: true }), "extra");
  assert.equal(pl.api.gridRoleOf({ extra: true, part: "head" }), "part", "part wins over extra");
  const byId = new Map();
  assert.equal(pl.api.gridSetDirOf({ parent_ref: "missing" }, byId, 0), null);
  // depth guard: a cyclic parent chain must terminate
  const a = { id: "a", parent_ref: "b" };
  const b = { id: "b", parent_ref: "a" };
  byId.set("a", a); byId.set("b", b);
  assert.equal(pl.api.gridSetDirOf(a, byId, 0), null, "the walk stops; no infinite loop");
});
