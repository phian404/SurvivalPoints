/**
 * Tests for stage 3.3c — `crop_reference` + `manifestToPartsLayout`.
 *
 * Two products, one shared invariant: coordinates live in the ORIGINAL image's
 * NATURAL pixels, and a non-square image must never be mis-scaled.
 *
 *  - manifestToPartsLayout is a PURE converter (sparts/1 -> compare_parts
 *    layout). bbox_norm's x scales by width, y by height — SEPARATELY. bbox_px
 *    and bbox_norm together, zero/negative sizes and boxes leaving the image
 *    are REFUSED per entry (never clamped). An unknown group is refused by
 *    name; nothing is renamed or guessed. One bad entry never sinks the layout.
 *
 *  - crop_reference cuts regions out of a loaded reference and registers each
 *    as a role:'part' reference (view inherited, parent never mutated). Same
 *    bbox_px / bbox_norm rules. One failing crop never stops the others.
 *    Optional write_dir exports through the 3.3a guard (O_EXCL — an existing
 *    file is never replaced). No image data is returned.
 *
 *  - compare_parts with `parts_manifest` runs the WHOLE pipeline for real:
 *    manifest -> converter -> layout -> referencePartMask -> metrics.
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
  "globalThis.__MCP_CROP__ = { commands, G, MULTI_VIEWS, normaliseViewLabel, viewReference, " +
  "manifestToPartsLayout, PARTS_MANIFEST_VERSION, refBboxShape, refDirOf, refMimeOf, refBatchLimit, " +
  "REF_BATCH_DEFAULTS, guardedWrite, guardedRead, guardPath, addReference, sha256Hex, REF_IMAGE_MIME };\n";
const instrumented = source.replace(REGISTER, EXPORTS + REGISTER);

// ---------------------------------------------------------------------------
// Content encodes the decoded size:  "IMG|<label>|<w>|<h>"  (and a PNG
// signature prefix so an export through guardedWrite passes its check).
// ---------------------------------------------------------------------------
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
function img(label, w, h) {
  return "IMG|" + label + "|" + w + "|" + h;
}
function sizeFromContent(text) {
  if (text.startsWith("CORRUPT")) return null;
  const m = /IMG\|([^|]*)\|(\d+)\|(\d+)/.exec(text);
  if (!m) return null;
  return { w: Number(m[2]), h: Number(m[3]) };
}

/** Values built inside the vm have a different Array/Object prototype — compare as JSON. */
const plain = (v) => JSON.parse(JSON.stringify(v));
/** A data URL whose bytes are [PNG signature | ascii descriptor]. */
function pngDataURL(descriptor, label) {
  const bytes = Buffer.concat([Buffer.from(PNG_SIG), Buffer.from(descriptor, "utf8")]);
  return { mime: "image/png", b64: bytes.toString("base64"), bytes };
}
const dataURLof = (descriptor) => "data:image/png;base64," + pngDataURL(descriptor).b64;

// ---------------------------------------------------------------------------
// A real-ish in-memory fs (regular files + dirs + symlinks + inodes).
// ---------------------------------------------------------------------------
function makeFs() {
  let inoSeq = 100;
  const nodes = new Map();
  const links = new Map();
  const norm = (p) => String(p).replace(/\\/g, "/").replace(/([^:])\/{2,}/g, "$1/").replace(/\/+$/, "") || "/";
  const parentOf = (p) => { const i = p.lastIndexOf("/"); return i <= 0 ? "/" : p.slice(0, i); };
  const baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);
  const realpathSync = (p) => {
    let cur = norm(p);
    const seen = new Set();
    for (let g = 0; g < 40; g++) {
      if (links.has(cur)) {
        if (seen.has(cur)) throw new Error("ELOOP");
        seen.add(cur);
        const t = links.get(cur);
        cur = norm(t.startsWith("/") ? t : parentOf(cur) + "/" + t);
        continue;
      }
      const parts = cur.split("/").filter(Boolean);
      let acc = /^[A-Za-z]:$/.test(parts[0]) ? parts.shift() : "";
      let changed = false;
      for (const part of parts) {
        acc += "/" + part;
        if (links.has(acc)) { const t = links.get(acc); acc = norm(t.startsWith("/") ? t : parentOf(acc) + "/" + t); changed = true; }
      }
      if (changed) { cur = acc; continue; }
      if (!nodes.has(cur)) throw Object.assign(new Error("ENOENT: " + p), { code: "ENOENT" });
      return cur;
    }
    throw new Error("ELOOP");
  };
  const fds = new Map();
  let fdSeq = 7;
  const api = {
    _nodes: nodes, _links: links, norm,
    mkdir(p) { nodes.set(norm(p), { kind: "dir", ino: ++inoSeq, dev: 1 }); return p; },
    put(p, content) {
      const buf = Buffer.isBuffer(content) ? content : Buffer.from(String(content));
      nodes.set(norm(p), { kind: "file", buf, ino: ++inoSeq, dev: 1 });
      return norm(p);
    },
    symlink(from, to) { links.set(norm(from), to); return norm(from); },
    realpathSync,
    statSync(p) { const n = nodes.get(realpathSync(p)); return { isFile: () => n.kind === "file", isDirectory: () => n.kind === "dir", size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev }; },
    lstatSync(p) {
      const key = norm(p);
      if (links.has(key)) return { isFile: () => false, isDirectory: () => false, isSymbolicLink: () => true, ino: 0, dev: 1 };
      const n = nodes.get(key);
      if (!n) throw Object.assign(new Error("ENOENT"), { code: "ENOENT" });
      return { isFile: () => n.kind === "file", isDirectory: () => n.kind === "dir", isSymbolicLink: () => false, size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev };
    },
    openSync(p, mode) {
      const key = norm(p);
      if (mode === "wx" || mode === "wx+") {
        if (nodes.has(key) || links.has(key)) throw Object.assign(new Error("EEXIST"), { code: "EEXIST" });
        const real = realpathSync(parentOf(key)) + "/" + baseOf(key);
        const fd = ++fdSeq;
        fds.set(fd, { path: key, real, writable: true, readable: mode === "wx+", pos: 0 });
        return fd;
      }
      const real = realpathSync(p);
      const fd = ++fdSeq;
      fds.set(fd, { path: key, real, readable: true, writable: false, pos: 0 });
      return fd;
    },
    fstatSync(fd) { const n = nodes.get(fds.get(fd).real); if (!n) throw new Error("EBADF"); return { isFile: () => n.kind === "file", size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev }; },
    readFileSync(a, enc) {
      if (typeof a === "number") {
        const f = fds.get(a);
        if (!f.readable) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
        const n = nodes.get(f.real);
        const out = n.buf.slice(f.pos || 0);
        f.pos += out.length;
        return out;
      }
      const n = nodes.get(realpathSync(a));
      return enc === "base64" ? Buffer.from(n.buf.toString("base64"), "base64") : n.buf;
    },
    readSync(fd, buffer, offset, length, position) {
      const f = fds.get(fd);
      if (!f.readable) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
      const n = nodes.get(f.real);
      const start = position == null ? f.pos || 0 : position;
      const end = Math.min(n.buf.length, start + length);
      const copied = Math.max(0, end - start);
      n.buf.copy(buffer, offset, start, end);
      if (position == null) f.pos = end;
      return copied;
    },
    writeFileSync(a, data) {
      if (typeof a !== "number") throw new Error("guardedWrite must write through the fd");
      const f = fds.get(a);
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
      nodes.set(f.real, { kind: "file", buf, ino: ++inoSeq, dev: 1 });
      f.pos = buf.length;
      return f.real;
    },
    closeSync(fd) { fds.delete(fd); },
    readdirSync(p) {
      const dir = realpathSync(p);
      const out = [];
      for (const key of new Set([...nodes.keys(), ...links.keys()])) if (parentOf(key) === dir && key !== dir) out.push(baseOf(key));
      return out.sort();
    },
  };
  return api;
}

// ---------------------------------------------------------------------------
// The sandbox: enough Blockbench to run crop_reference AND compare_parts, with
// a canvas that actually produces a PNG data URL sized to the crop, and a
// Preview whose camera direction maps to a view (so compare_parts renders).
// ---------------------------------------------------------------------------
function loadPlugin({ roots = "", fsImpl = null, S = 64, refFill = 40, fill = 40 } = {}) {
  const fakeFs = fsImpl || makeFs();
  const calls = { setProjectionMode: [], renders: 0 };

  function squareMask(n, off) {
    const m = new Uint8Array(S * S);
    const o = off != null ? off : Math.max(0, Math.floor((S - n) / 2));
    for (let y = o; y < o + n && y < S; y++) for (let x = o; x < o + n && x < S; x++) m[y * S + x] = 1;
    return m;
  }
  function imageDataFrom(mask) {
    const d = new Uint8ClampedArray(S * S * 4);
    for (let i = 0; i < S * S; i++) d[i * 4 + 3] = mask[i] ? 255 : 0;
    return { data: d, width: S, height: S };
  }
  let pending = "model";

  class Group {
    static all = [];
    constructor(d = {}) { this.name = d.name || "g"; this.origin = [0, 0, 0]; this.rotation = [0, 0, 0]; this.children = []; this.parent = "root"; this.mesh = { visible: true, isMesh: true }; }
    init() { Group.all.push(this); return this; }
    addTo(p) { this.parent = p || "root"; if (p && typeof p === "object") p.children.push(this); return this; }
  }
  class Cube {
    static all = [];
    constructor(d = {}) { this.name = d.name || "c"; this.from = d.from || [0, 0, 0]; this.to = d.to || [1, 1, 1]; this.origin = this.from.slice(); this.rotation = [0, 0, 0]; this.faces = {}; this.parent = "root"; this.mesh = { visible: true, isMesh: true }; }
    init() { Cube.all.push(this); return this; }
    addTo(p) { this.parent = p || "root"; if (p && typeof p === "object") p.children.push(this); return this; }
    applyTexture() {}
  }
  class Mesh {
    static all = [];
    constructor(d = {}) { this.name = d.name || "m"; this.origin = [0, 0, 0]; this.children = []; this.parent = "root"; this.mesh = { visible: true, isMesh: true }; }
    init() { Mesh.all.push(this); return this; }
    addTo(p) { this.parent = p || "root"; if (p && typeof p === "object") p.children.push(this); return this; }
  }

  const camera = {
    position: {
      v: [10, 10, 10],
      toArray() { return this.v.slice(); },
      set(x, y, z) {
        this.v = [x, y, z];
        const len = Math.hypot(x, y, z) || 1;
        const d = [x / len, y / len, z / len];
        const table = { front: [0, 0, -1], back: [0, 0, 1], left: [-1, 0, 0], right: [1, 0, 0], top: [0, 1, 0] };
        let best = null, bestDot = -Infinity;
        for (const [name, ref] of Object.entries(table)) {
          const dd = d[0] * ref[0] + d[1] * ref[1] + d[2] * ref[2];
          if (dd > bestDot) { bestDot = dd; best = name; }
        }
        view.__v = bestDot > 0.9 ? best : "isometric";
      },
      get x() { return this.v[0]; }, get y() { return this.v[1]; }, get z() { return this.v[2]; },
    },
  };
  const view = { __v: "front" };
  const controls = { target: { v: [0, 0, 0], toArray() { return this.v.slice(); }, set(x, y, z) { this.v = [x, y, z]; } }, updateSceneScale() {} };
  const preview = {
    camera, controls, isOrtho: false,
    setProjectionMode(v) { calls.setProjectionMode.push(!!v); this.isOrtho = !!v; },
    render() { calls.renders++; },
  };
  preview.canvas = { width: S, height: S };
  Object.defineProperty(preview, "renderer", {
    value: { domElement: { width: S, height: S }, getClearColor() { return null; }, getClearAlpha() { return 1; }, setClearColor() {} },
  });

  class Image {
    constructor() { this.naturalWidth = 0; this.naturalHeight = 0; this.width = 0; this.height = 0; this.onload = null; this.onerror = null; this._src = ""; }
    get src() { return this._src; }
    set src(value) {
      this._src = value;
      // The next canvas read is the REFERENCE decode (same handoff as
      // parts.test.js): getImageData returns the reference mask once, then the
      // renderer's own captures answer as "model".
      pending = "reference";
      setTimeout(() => {
        try {
          const b64 = String(value).replace(/^data:[^;]+;base64,/, "");
          const text = Buffer.from(b64, "base64").toString("utf8"); // includes PNG sig — sizeFromContent greps past it
          // An encoded IMG|<label>|<w>|<h> descriptor decodes to that exact size;
          // anything else (like the opaque "hero" stub URLs compare_parts tests use)
          // falls back to the stub canvas size, mirroring the parts harness.
          const size = sizeFromContent(text) || { w: S, h: S };
          if (!sizeFromContent(text) && /^CORRUPT/.test(text)) throw new Error("decode failed");
          this.naturalWidth = this.width = size.w;
          this.naturalHeight = this.height = size.h;
          if (this.onload) this.onload();
        } catch (e) { if (this.onerror) this.onerror(e); }
      }, 0);
    }
  }

  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    Buffer,
    isApp: true,
    require: (n) => (n === "fs" ? fakeFs : undefined),
    Plugin: { register() {} },
    Blockbench: { version: "5.2.2", showQuickMessage() {}, addCSS() {} },
    Project: { name: "crop_test", geometry_name: "crop_test", texture_width: 64, texture_height: 64 },
    Format: { id: "free", box_uv: false, animation_mode: false, forward_direction: "-z" },
    Formats: {},
    Mode: { selected: { id: "edit" } },
    Texture: Object.assign([], { all: [], getDefault: () => null }),
    Animation: { all: [] },
    Undo: { initEdit() {}, finishEdit() {} },
    Canvas: { updateAll() {} },
    Outliner: { root: [], elements: [] },
    Preview: { selected: preview },
    ReferenceImage: class { constructor() {} addAsReference() {} add() {} select() {} update() {} delete() {} },
    Group, Cube, Mesh, Image,
    scene: { traverse() {}, background: null },
    THREE: { Color: function () {} },
    document: {
      createElement() {
        return {
          width: S, height: S,
          getContext() {
            return {
              drawImage() {},
              getImageData() {
                if (pending === "reference") { pending = "model"; return imageDataFrom(squareMask(refFill)); }
                return imageDataFrom(squareMask(fill));
              },
              putImageData() {}, fillRect() {}, fillText() {},
              createImageData(w, h) { return { data: new Uint8ClampedArray(w * h * 4), width: w, height: h }; },
            };
          },
          // A crop canvas carries its own width/height; toDataURL encodes them so
          // a later decode (or the PNG signature check in guardedWrite) is honest.
          toDataURL() {
            return dataURLof(img("crop", this.width, this.height));
          },
        };
      },
    },
    settings: { blockbench_mcp_ref_roots: { get: () => roots } },
  };
  // Mark which getImageData is expected: reference decode goes through Image;
  // model captures go through the renderer. Simplest faithful model: whenever a
  // fresh Image loads we flip pending to "reference" for its one canvas read.
  sandbox.__setPending = (v) => { pending = v; };

  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });
  return { api: sandbox.__MCP_CROP__, sandbox, fakeFs, calls, preview, Group, Cube, Mesh, view, dataURLof };
}

/** A reference the parent crop_reference can decode: natural WxH. */
function seedRef(pl, { id, name, view = null, w = 64, h = 64, label = name }) {
  const entry = {
    id: id || "ref_" + (pl.api.G.references.length + 1),
    name,
    data_url: pl.dataURLof(img(label, w, h)),
    width: w, height: h,
    view,
    source: "test", added_at: Date.now(), ref_image: null, extra: !view,
  };
  pl.api.G.references.push(entry);
  return entry;
}

/** For compare_parts: a view-labelled ref that yields a clean silhouette. */
function seedWholeRef(pl, { name, view, w = 64, h = 64 }) {
  pl.api.G.references.push({
    id: "ref_" + (pl.api.G.references.length + 1),
    name, view,
    data_url: "data:image/png;base64," + name,
    width: w, height: h,
    source: "test", added_at: Date.now(), ref_image: null,
  });
}

function seedModel(pl, names) {
  names.forEach((n) => {
    const g = new pl.Group({ name: n }).init();
    g.addTo("root");
    const c = new pl.Cube({ name: n + "_cube" }).init();
    c.addTo(g);
  });
  return names;
}

// ===========================================================================
// 1. manifestToPartsLayout — the PURE converter
// ===========================================================================

const KNOWN = ["head_group", "body_group", "left_arm_group"];
const SZ = (w, h) => ({ front: { width: w, height: h } });

test("bbox_px is passed through as natural pixels, unchanged", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_px: [8, 8, 48, 48] }] } },
    SZ(64, 64),
    { knownGroups: KNOWN }
  );
  assert.deepEqual([...out.problems], []);
  assert.deepEqual(plain(out.views.front[0]), { group: "head_group", bbox: [8, 8, 48, 48] });
});

test("bbox_norm converts x by WIDTH and y by HEIGHT separately (1600x900)", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_norm: [0.1, 0.2, 0.25, 0.5] }] } },
    SZ(1600, 900),
    { knownGroups: KNOWN }
  );
  assert.deepEqual([...out.problems], []);
  // 0.1*1600=160, 0.2*900=180, 0.25*1600=400, 0.5*900=450.
  assert.deepEqual(plain(out.views.front[0]), { group: "head_group", bbox: [160, 180, 400, 450] });
});

test("bbox_norm full 0..1 yields the FULL image box, not a square one", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "body_group", bbox_norm: [0, 0, 1, 1] }] } },
    SZ(1600, 900),
    { knownGroups: KNOWN }
  );
  // One shared factor (min side = 900) would give [0,0,900,900]; the correct
  // per-axis scale gives the true 1600x900. This is the exact bug the branch
  // exists to prevent.
  assert.deepEqual(plain(out.views.front[0].bbox), [0, 0, 1600, 900]);
});

test("bbox_px and bbox_norm agree on a non-square image within rounding", () => {
  const { api } = loadPlugin();
  const norm = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_norm: [0.25, 0.5, 0.5, 0.25] }] } },
    SZ(1600, 900), { knownGroups: KNOWN }
  ).views.front[0].bbox;
  const px = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_px: [400, 450, 800, 225] }] } },
    SZ(1600, 900), { knownGroups: KNOWN }
  ).views.front[0].bbox;
  assert.deepEqual(plain(norm), plain(px));
});

test("bbox_norm and bbox_px differ on a non-square image (the trap is real)", () => {
  const { api } = loadPlugin();
  // [0,0,0.5,0.5] on 1600x900 -> 800x450, whereas [0,0,0.5,0.5] treated as px
  // (or scaled by one factor) would give 0.5*900=450 square. Different boxes.
  const norm = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_norm: [0, 0, 0.5, 0.5] }] } },
    SZ(1600, 900), { knownGroups: KNOWN }
  ).views.front[0].bbox;
  assert.deepEqual(plain(norm), [0, 0, 800, 450], "width scaled by 1600, height by 900");
});

test("bbox_px and bbox_norm TOGETHER is refused (a contradiction, not a preference)", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_px: [1, 1, 2, 2], bbox_norm: [0, 0, 1, 1] }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /exactly ONE region.*contradiction/);
});

test("zero and negative sizes are refused, never clamped", () => {
  const { api } = loadPlugin();
  const cases = [
    { bbox_px: [4, 4, 0, 10] },
    { bbox_px: [4, 4, 10, 0] },
    { bbox_px: [4, 4, -5, 10] },
    { bbox_norm: [0, 0, 0, 0.5] },
    { bbox_norm: [0, 0, 0.5, -0.1] },
  ];
  cases.forEach((entry) => {
    const out = api.manifestToPartsLayout({ version: "sparts/1", views: { front: [Object.assign({ group: "head_group" }, entry)] } }, SZ(64, 64), { knownGroups: KNOWN });
    assert.equal(out.views.front.length, 0, "refused: " + JSON.stringify(entry));
    // A negative SIZE component is caught by the size rule; a negative OFFSET
    // (bbox_norm) is caught by the 0..1 range rule first. Both refuse, nothing
    // is clamped either way.
    assert.match(out.problems[0].error, /zero or negative size|not silently clamp|within 0\.\.1/i);
  });
});

test("a box reaching outside the image is refused, not truncated", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_px: [50, 50, 30, 30] }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /past the image \(64x64\).*not truncated/i);
});

test("bbox_norm beyond 1 and negative values are refused", () => {
  const { api } = loadPlugin();
  for (const b of [[0, 0, 1.2, 0.5], [-0.1, 0, 0.5, 0.5]]) {
    const out = api.manifestToPartsLayout({ version: "sparts/1", views: { front: [{ group: "head_group", bbox_norm: b }] } }, SZ(64, 64), { knownGroups: KNOWN });
    assert.equal(out.views.front.length, 0, "refused: " + b.join(","));
    assert.match(out.problems[0].error, /within 0\.\.1/);
  }
});

test("polygon is passed through in natural pixels (>=3 points)", () => {
  const { api } = loadPlugin();
  const poly = [[10, 10], [40, 10], [40, 40], [10, 40]];
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", polygon: poly }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.deepEqual(plain(out.views.front[0]), { group: "head_group", polygon: poly });
});

test("a polygon bleeding off-image is a WARNING, still emitted", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", polygon: [[10, 10], [70, 10], [40, 40]] }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 1, "not refused");
  assert.equal(out.warnings.length, 1);
  assert.match(out.warnings[0].warning, /outside the 64x64 image.*clipped/i);
});

test("polygon with fewer than 3 points is refused", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", polygon: [[1, 1], [2, 2]] }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /at least 3/);
});

test("mask_png data URL is passed straight through", () => {
  const { api } = loadPlugin();
  const url = "data:image/png;base64,iVBORw0KGgoAAA";
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", mask_png: url }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.deepEqual(plain(out.views.front[0]), { group: "head_group", mask_png: url });
});

test("a non-PNG/JPEG mask_png data URL is refused", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", mask_png: "data:image/gif;base64,AA" }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /base64 PNG or JPEG/);
});

test("mask_png FILE name runs through the injected resolver", () => {
  const { api } = loadPlugin();
  const seen = [];
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", mask_png: "head_mask.png" }] } },
    SZ(64, 64),
    { knownGroups: KNOWN, resolveMask: (n) => { seen.push(n); return "data:image/png;base64,RESOLVED"; } }
  );
  assert.deepEqual(seen, ["head_mask.png"], "the resolver gets the PLAIN file name");
  assert.deepEqual(plain(out.views.front[0]), { group: "head_group", mask_png: "data:image/png;base64,RESOLVED" });
});

test("mask_png file name with NO resolver is refused (no silent drop)", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", mask_png: "head_mask.png" }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /no mask resolver.*compare_parts/);
});

test("mask_png path traversal in the file name is refused by refPlainName", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", mask_png: "../evil.png" }] } },
    SZ(64, 64), { knownGroups: KNOWN, resolveMask: () => "data:image/png;base64,x" }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /plain file name/);
});

test("an unknown group is refused BY NAME — never renamed or guessed", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "ghost_group", bbox_px: [1, 1, 2, 2] }] } },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /no model group named "ghost_group".*does not rename/);
});

test("with no knownGroups list, the group is passed through (validated later by compare_parts)", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "anything", bbox_px: [1, 1, 2, 2] }] } },
    SZ(64, 64), {}
  );
  assert.equal(out.views.front.length, 1);
});

test("a view with no known natural size is refused (bbox needs the image to validate)", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { front: [{ group: "head_group", bbox_norm: [0, 0, 0.5, 0.5] }] } },
    {}, // no sizes
    { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 0);
  assert.match(out.problems[0].error, /no natural image size known for view "front"/);
});

test("an unknown view key is refused by name, others survive", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    { version: "sparts/1", views: { frontish: [{ group: "head_group", bbox_px: [1, 1, 2, 2] }], back: [{ group: "body_group", bbox_px: [1, 1, 2, 2] }] } },
    { frontish: { width: 64, height: 64 }, back: { width: 64, height: 64 } },
    { knownGroups: KNOWN }
  );
  assert.match(out.problems[0].error, /view "frontish" is not one of/);
  assert.equal(out.views.back.length, 1, "the valid view still produced a spec");
});

test("one bad entry never sinks the view — the good entries convert", () => {
  const { api } = loadPlugin();
  const out = api.manifestToPartsLayout(
    {
      version: "sparts/1",
      views: {
        front: [
          { group: "head_group", bbox_px: [1, 1, 10, 10] },
          { group: "ghost", bbox_px: [1, 1, 2, 2] },
          { group: "body_group", bbox_px: [0, 0, 5, 5] },
        ],
      },
    },
    SZ(64, 64), { knownGroups: KNOWN }
  );
  assert.equal(out.views.front.length, 2, "two valid parts survived");
  assert.equal(out.problems.length, 1);
});

test("the converter rejects a wrong version and a non-object structural shape", () => {
  const { api } = loadPlugin();
  assert.throws(() => api.manifestToPartsLayout({ version: "sparts/2", views: {} }, SZ(64, 64)), /unsupported parts manifest version/);
  assert.throws(() => api.manifestToPartsLayout({ views: {} }, SZ(64, 64)), /unsupported parts manifest version/);
  assert.throws(() => api.manifestToPartsLayout({ version: "sparts/1" }, SZ(64, 64)), /needs a "views" object/);
  assert.throws(() => api.manifestToPartsLayout("nope", SZ(64, 64)), /must be a JSON object/);
});

// ===========================================================================
// 2. crop_reference — cutting regions into role:'part' references
// ===========================================================================

test("crop_reference: bbox_px creates a part with the exact natural box and inherited view", async () => {
  const pl = loadPlugin();
  const parent = seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 1600, h: 900 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p",
    crops: [{ part: "head", bbox_px: [100, 200, 400, 300] }],
  });
  assert.equal(res.crops.length, 1);
  const c = res.crops[0];
  assert.equal(c.part, "head");
  assert.equal(c.role, "part");
  assert.equal(c.origin, "crop");
  assert.equal(c.parent_ref, "ref_p");
  assert.equal(c.view, "front", "the crop inherits the parent's view");
  assert.deepEqual(plain(c.bbox_natural), [100, 200, 400, 300]);
  assert.equal(c.width, 400);
  assert.equal(c.height, 300);
  // Registered as a real reference the store can see.
  const stored = pl.api.G.references.find((r) => r.id === c.id);
  assert.ok(stored);
  assert.equal(stored.part, "head");
  assert.equal(stored.parent_ref, "ref_p");
  assert.equal(stored.origin, "crop");
  assert.equal(stored.extra, true, "a part is never a view target");
});

test("crop_reference: bbox_norm on 1600x900 equals the equivalent bbox_px within rounding", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 1600, h: 900 });
  const norm = await pl.api.commands.crop_reference({
    reference: "ref_p", crops: [{ part: "a", bbox_norm: [0.25, 0.5, 0.5, 0.25] }],
  });
  const px = await pl.api.commands.crop_reference({
    reference: "ref_p", crops: [{ part: "b", bbox_px: [400, 450, 800, 225] }],
  });
  assert.deepEqual(norm.crops[0].bbox_natural, px.crops[0].bbox_natural);
});

test("crop_reference: bbox_norm scales x by width and y by height SEPARATELY", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 1600, h: 900 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p", crops: [{ part: "half", bbox_norm: [0, 0, 0.5, 0.5] }],
  });
  // 0.5 of width = 800, 0.5 of height = 450 — a shared 900 factor would square it.
  assert.deepEqual(plain(res.crops[0].bbox_natural), [0, 0, 800, 450]);
});

test("crop_reference: bbox_px AND bbox_norm together is a per-crop error", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 100, h: 100 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p", crops: [{ part: "x", bbox_px: [0, 0, 10, 10], bbox_norm: [0, 0, 0.1, 0.1] }],
  });
  assert.equal(res.crops.length, 0);
  assert.equal(res.errors.length, 1);
  assert.match(res.errors[0].error, /both bbox_px and bbox_norm.*contradiction/);
});

test("crop_reference: zero / negative size is refused, not clamped", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 100, h: 100 });
  for (const bb of [[0, 0, 0, 50], [0, 0, 50, -5]]) {
    const res = await pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "p", bbox_px: bb }] });
    assert.equal(res.crops.length, 0, "refused: " + bb.join(","));
    assert.match(res.errors[0].error, /zero or negative size.*does not clamp/i);
  }
});

test("crop_reference: a box past the image edge is refused, not truncated", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 100, h: 100 });
  const res = await pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "p", bbox_px: [80, 80, 50, 50] }] });
  assert.equal(res.crops.length, 0);
  assert.match(res.errors[0].error, /past the image \(100x100\).*nothing is truncated/i);
});

test("crop_reference: a crop starting at a negative coordinate is refused", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 100, h: 100 });
  const res = await pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "p", bbox_px: [-5, 10, 20, 20] }] });
  assert.equal(res.crops.length, 0);
  assert.match(res.errors[0].error, /starts outside the image/);
});

test("crop_reference: the parent reference is NEVER mutated", async () => {
  const pl = loadPlugin();
  const parent = seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 1600, h: 900 });
  const before = JSON.stringify({ view: parent.view, width: parent.width, height: parent.height, url: parent.data_url, part: parent.part });
  await pl.api.commands.crop_reference({
    reference: "ref_p",
    crops: [{ part: "head", bbox_px: [10, 10, 20, 20] }, { part: "bad", bbox_px: [5000, 5000, 20, 20] }],
  });
  const after = JSON.stringify({ view: parent.view, width: parent.width, height: parent.height, url: parent.data_url, part: parent.part });
  assert.equal(after, before, "one success and one failure — the parent is byte-for-byte unchanged");
});

test("crop_reference: one failing crop never stops the others", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero_front", view: "front", w: 1000, h: 1000 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p",
    crops: [
      { part: "ok1", bbox_px: [0, 0, 100, 100] },
      { part: "out", bbox_px: [950, 950, 100, 100] }, // past the 1000x1000 edge
      { part: "ok2", bbox_norm: [0.1, 0.1, 0.2, 0.2] },
    ],
  });
  assert.deepEqual(plain(res.crops.map((c) => c.part)), ["ok1", "ok2"], "both good crops created, in order");
  assert.equal(res.errors.length, 1);
  assert.equal(res.errors[0].part, "out");
  assert.equal(res.totals.created, 2);
});

test("crop_reference: a non-square image crops correctly at an off-centre box", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 1600, h: 900 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p", crops: [{ part: "p", bbox_px: [1200, 600, 400, 300] }],
  });
  // 1200+400=1600 (== width, allowed), 600+300=900 (== height, allowed).
  assert.equal(res.crops.length, 1);
  assert.deepEqual(plain(res.crops[0].bbox_natural), [1200, 600, 400, 300]);
});

test("crop_reference: the response carries NO image data", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 200, h: 200 });
  const res = await pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "p", bbox_px: [0, 0, 50, 50] }] });
  assert.ok(!JSON.stringify(res).includes("base64"), "no base64 leaks to the caller");
});

test("crop_reference: needs a loaded reference and a non-empty crops array", async () => {
  const pl = loadPlugin();
  await assert.rejects(() => pl.api.commands.crop_reference({ crops: [{ part: "p", bbox_px: [0, 0, 1, 1] }] }), /needs a loaded reference/);
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 50, h: 50 });
  await assert.rejects(() => pl.api.commands.crop_reference({ reference: "ref_p", crops: [] }), /non-empty `crops` array/);
  await assert.rejects(() => pl.api.commands.crop_reference({ reference: "ref_p" }), /non-empty `crops` array/);
});

test("crop_reference: an unknown view override is refused, not dropped", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 50, h: 50 });
  await assert.rejects(
    () => pl.api.commands.crop_reference({ reference: "ref_p", view: "sideways", crops: [{ part: "p", bbox_px: [0, 0, 10, 10] }] }),
    /Unknown view "sideways"/
  );
});

// --- write_dir: the 3.3a guard, no overwrite --------------------------------
const ROOT = "/home/dev/refs";
const WDIR = ROOT + "/crops";

test("crop_reference write_dir exports each crop as a verified PNG", async () => {
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT); f.mkdir(WDIR);
  const pl = loadPlugin({ roots: ROOT, fsImpl: f });
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 200, h: 100 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p",
    crops: [{ part: "head", bbox_px: [0, 0, 50, 40] }],
    write_dir: WDIR,
  });
  const file = path.posix.join(WDIR, "head.png").replace(/\\/g, "/");
  assert.equal(res.crops[0].written.path, file);
  const node = f._nodes.get(f.norm ? f.norm(file) : file);
  assert.ok(node, "the PNG landed on disk");
  assert.equal(node.buf[0], 0x89);
  assert.equal(node.buf[1], 0x50); // PNG signature — guardedWrite verified it
});

test("crop_reference write_dir refuses to overwrite an existing file (O_EXCL)", async () => {
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT); f.mkdir(WDIR);
  f.put(WDIR + "/head.png", "somebody else's bytes");
  const pl = loadPlugin({ roots: ROOT, fsImpl: f });
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 200, h: 100 });
  const res = await pl.api.commands.crop_reference({
    reference: "ref_p",
    crops: [{ part: "head", bbox_px: [0, 0, 50, 40] }],
    write_dir: WDIR,
  });
  // The crop itself succeeded — only its EXPORT failed. A lost reference would
  // be a worse outcome than a reportable disk miss.
  assert.equal(res.crops.length, 1);
  assert.equal(res.crops[0].written, undefined, "nothing was written");
  assert.match(res.crops[0].written_error, /cannot create.*exists or is a planted link/);
  assert.equal(f._nodes.get(f.norm(WDIR + "/head.png")).buf.toString(), "somebody else's bytes", "the existing file survived");
});

test("crop_reference write_dir outside a trusted root is refused", async () => {
  const f = makeFs();
  f.mkdir("/tmp");
  const pl = loadPlugin({ roots: ROOT, fsImpl: f });
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 200, h: 100 });
  await assert.rejects(
    () => pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "p", bbox_px: [0, 0, 5, 5] }], write_dir: "/tmp" }),
    /outside every trusted reference root|no trusted reference roots/
  );
});

// ===========================================================================
// 3. compare_parts with parts_manifest — the real pipeline
// ===========================================================================

test("compare_parts {parts_manifest} converts bbox_norm and runs the parts metrics", async () => {
  const pl = loadPlugin();
  seedModel(pl, ["head_group", "body_group"]);
  seedWholeRef(pl, { name: "hero", view: "front" });
  const res = await pl.api.commands.compare_parts({
    parts_manifest: {
      version: "sparts/1",
      views: { front: [
        { group: "head_group", bbox_norm: [0.1, 0.1, 0.5, 0.5] },
        { group: "body_group", bbox_px: [8, 8, 48, 48] },
      ] },
    },
  });
  assert.equal(res.layout_source, "parts_manifest");
  assert.equal(res.views.length, 1);
  assert.equal(res.views[0].ok, true, "the view compared");
  assert.equal(res.views[0].parts.length, 2);
  for (const pt of res.views[0].parts) {
    assert.equal(pt.ok, true, pt.error);
    assert.ok(pt.shape && "match_percent" in pt.shape, "shape block present");
    assert.ok(pt.size && "size_ratio" in pt.size, "size block present");
    assert.ok(pt.placement && "delta" in pt.placement, "placement block present");
    assert.ok(pt.occlusion && "occluded_pct" in pt.occlusion, "occlusion block present");
  }
  assert.deepEqual([...res.manifest_problems], []);
});

test("compare_parts manifest: an unknown group is a manifest problem, and the known part still compares", async () => {
  const pl = loadPlugin();
  seedModel(pl, ["head_group", "body_group"]);
  seedWholeRef(pl, { name: "hero", view: "front" });
  const res = await pl.api.commands.compare_parts({
    parts_manifest: {
      version: "sparts/1",
      views: { front: [
        { group: "ghost_group", bbox_px: [1, 1, 10, 10] }, // refused at conversion
        { group: "head_group", bbox_px: [8, 8, 48, 48] },   // survives
      ] },
    },
  });
  assert.equal(res.views[0].ok, true);
  assert.equal(res.manifest_problems.length, 1);
  assert.match(res.manifest_problems[0].error, /no model group named "ghost_group"/);
  // Only the one valid spec made it into the layout.
  assert.equal(res.views[0].parts.length, 1);
  assert.equal(res.views[0].parts[0].part, "head_group");
});

test("compare_parts manifest: bbox_norm needs a loaded view reference for the natural size", async () => {
  const pl = loadPlugin();
  seedModel(pl, ["head_group"]);
  // NO view-labelled reference: sizes.front cannot be resolved.
  const res = await pl.api.commands.compare_parts({
    parts_manifest: { version: "sparts/1", views: { front: [{ group: "head_group", bbox_norm: [0, 0, 0.5, 0.5] }] } },
  }).catch((e) => e);
  // The converter refuses every entry (no size), compare_parts then throws for
  // the empty view; either path must name the cause, never a fake number.
  const msg = res && res.message ? res.message : JSON.stringify(res);
  assert.match(msg, /no natural image size|no usable entries|No reference for view/i);
});

test("compare_parts manifest: a manifest where EVERY entry is refused throws a named cause", async () => {
  const pl = loadPlugin();
  seedModel(pl, ["head_group"]);
  seedWholeRef(pl, { name: "hero", view: "front" });
  await assert.rejects(
    () => pl.api.commands.compare_parts({
      parts_manifest: { version: "sparts/1", views: { front: [{ group: "ghost", bbox_px: [0, 0, 5, 5] }] } },
    }),
    /produced no usable regions.*no model group named "ghost"/
  );
});

// ===========================================================================
// 4. wiring + no-regression guards
// ===========================================================================

test("crop_reference is a HEAVY action on both sides and registered as a tool", () => {
  const pluginHeavy = source.match(/const HEAVY_ACTIONS = \{[\s\S]*?\n\};/);
  assert.match(pluginHeavy[0], /crop_reference:\s*true/);
  const clientSrc = fs.readFileSync(path.join(here, "..", "src", "client.ts"), "utf8");
  assert.match(clientSrc, /"crop_reference"/);
  const toolsSrc = fs.readFileSync(path.join(here, "..", "src", "tools.ts"), "utf8");
  assert.match(toolsSrc, /forward\(\s*\n?\s*"crop_reference"/, "the MCP tool is registered");
  assert.match(toolsSrc, /parts_manifest/);
});

test("list_references reports part crops with their role and parent", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 200, h: 200 });
  const res = await pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "head", bbox_px: [0, 0, 50, 50] }] });
  const list = pl.api.commands.list_references();
  const crop = list.references.find((r) => r.id === res.crops[0].id);
  assert.equal(crop.role, "part");
  assert.equal(crop.part, "head");
  assert.equal(crop.parent_ref, "ref_p");
  assert.equal(crop.view, "front");
});

test("a part crop never satisfies a view even though it carries the view label", async () => {
  const pl = loadPlugin();
  seedRef(pl, { id: "ref_p", name: "hero", view: "front", w: 200, h: 200 });
  const res = await pl.api.commands.crop_reference({ reference: "ref_p", crops: [{ part: "head", bbox_px: [0, 0, 50, 50] }] });
  // The parent already covered "front"; make it the ONLY front reference by
  // removing it, so we can see the crop is NOT picked up as a whole-view target.
  pl.api.G.references = pl.api.G.references.filter((r) => r.id === res.crops[0].id);
  const picked = pl.api.viewReference("front");
  assert.equal(picked, null, "extra/part references are skipped by viewReference");
});

test("refBboxShape rejects a short/non-numeric array", () => {
  const { api } = loadPlugin();
  assert.throws(() => api.refBboxShape([1, 2, 3], "b"), /must be \[x, y, w, h\]/);
  assert.throws(() => api.refBboxShape([1, 2, 3, NaN], "b"), /four finite numbers/);
});

test("refDirOf returns the parent folder of a real path", () => {
  const { api } = loadPlugin();
  assert.equal(api.refDirOf("/a/b/c.json"), "/a/b");
  assert.equal(api.refDirOf("C:/refs/front.png"), "C:/refs");
});
