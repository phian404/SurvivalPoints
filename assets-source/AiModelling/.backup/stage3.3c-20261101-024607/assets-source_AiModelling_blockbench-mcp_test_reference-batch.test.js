/**
 * Tests for BATCH reference loading (stage 3.3b — `load_references_dir`).
 *
 * The rules these tests defend:
 *
 *  - View binding comes ONLY from a `sprefs/1` manifest. Nothing is inferred
 *    from a file name; with no manifest every image loads as `role:"extra"`.
 *  - An `extra` is reference material and can NEVER satisfy a view — not in
 *    compare_reference_multi, not as the implicit `pickReference()` target.
 *  - Paths go through the 3.3a layer only (guardPath/guardedRead/
 *    guardedListDir), so the folder must be inside a trusted root and a
 *    symlink inside it is skipped, not followed.
 *  - SHA-256 of the raw bytes is a reference's identity: identical content is
 *    reported as a duplicate instead of being stored twice, and `reload:true`
 *    replaces the existing entry in place.
 *  - Limits (8 MB/file, 4096 px/side, 64 MB total) are PER-FILE errors — one
 *    bad file never stops the batch.
 *  - Silhouette quality is ADVISORY: an unextractable silhouette is a warning,
 *    never a skipped file.
 *  - The response never carries image data.
 *
 *   npm test
 */
import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
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
  "globalThis.__MCP_BATCH__ = { commands, parseRefManifest, sha256Hex, refPlainName, refExtOf, refMimeOf, " +
  "refBatchLimit, refImageEntries, viewReference, pickReference, addReference, reserveRefBytes, checkRefBudget, " +
  "guardedRead, guardedListDir, guardPath, refRoots, G, REF_MANIFEST_VERSION, REF_BATCH_DEFAULTS, MULTI_VIEWS };\n";
const instrumented = source.replace(REGISTER, EXPORTS + REGISTER);

// ---------------------------------------------------------------------------
// A small in-memory filesystem with symlinks and inodes (same shape the 3.3a
// tests use, so the guard code runs unmodified against it).
// ---------------------------------------------------------------------------
function makeFs() {
  let inoSeq = 100;
  const nodes = new Map(); // real path -> { buf, ino, dev, kind }
  const links = new Map(); // path -> target
  const norm = (p) =>
    String(p).replace(/\\/g, "/").replace(/([^:])\/{2,}/g, "$1/").replace(/\/+$/, "") || "/";
  const parentOf = (p) => {
    const i = p.lastIndexOf("/");
    return i <= 0 ? "/" : p.slice(0, i);
  };
  const baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);

  const mkdir = (p) => {
    nodes.set(norm(p), { kind: "dir", ino: ++inoSeq, dev: 1 });
    return p;
  };
  const put = (p, content = "x") => {
    const n = norm(p);
    const buf = Buffer.isBuffer(content) ? content : Buffer.from(String(content));
    nodes.set(n, { kind: "file", buf, ino: ++inoSeq, dev: 1 });
    return n;
  };
  const symlink = (from, to) => {
    links.set(norm(from), to);
    return norm(from);
  };

  const realpathSync = (p) => {
    let cur = norm(p);
    const seen = new Set();
    for (let guard = 0; guard < 40; guard++) {
      if (links.has(cur)) {
        if (seen.has(cur)) throw new Error("ELOOP: " + p);
        seen.add(cur);
        const target = links.get(cur);
        cur = norm(target.startsWith("/") ? target : parentOf(cur) + "/" + target);
        continue;
      }
      const parts = cur.split("/").filter(Boolean);
      let acc = /^[A-Za-z]:$/.test(parts[0]) ? parts.shift() : "";
      let changed = false;
      for (const part of parts) {
        acc = acc + "/" + part;
        if (links.has(acc)) {
          const target = links.get(acc);
          acc = norm(target.startsWith("/") ? target : parentOf(acc) + "/" + target);
          changed = true;
        }
      }
      if (changed) {
        cur = acc;
        continue;
      }
      if (!nodes.has(cur)) throw Object.assign(new Error("ENOENT: " + p), { code: "ENOENT" });
      return cur;
    }
    throw new Error("ELOOP: " + p);
  };

  const fds = new Map();
  let fdSeq = 7;

  return {
    _nodes: nodes,
    _links: links,
    mkdir,
    put,
    symlink,
    norm,
    realpathSync,
    statSync(p) {
      const n = nodes.get(realpathSync(p));
      return {
        isFile: () => n.kind === "file",
        isDirectory: () => n.kind === "dir",
        size: n.buf ? n.buf.length : 0,
        ino: n.ino,
        dev: n.dev,
      };
    },
    lstatSync(p) {
      const key = norm(p);
      if (links.has(key)) {
        return { isFile: () => false, isDirectory: () => false, isSymbolicLink: () => true, ino: 0, dev: 1 };
      }
      const n = nodes.get(key);
      if (!n) throw Object.assign(new Error("ENOENT: " + p), { code: "ENOENT" });
      return {
        isFile: () => n.kind === "file",
        isDirectory: () => n.kind === "dir",
        isSymbolicLink: () => false,
        size: n.buf ? n.buf.length : 0,
        ino: n.ino,
        dev: n.dev,
      };
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
    fstatSync(fd) {
      const f = fds.get(fd);
      const n = nodes.get(f.real);
      if (!n) throw new Error("EBADF (gone)");
      return { isFile: () => n.kind === "file", size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev };
    },
    readFileSync(a, enc) {
      if (typeof a === "number") {
        const f = fds.get(a);
        if (!f.readable) throw Object.assign(new Error("EBADF: bad file descriptor, read"), { code: "EBADF" });
        const n = nodes.get(f.real);
        if (!n || n.kind !== "file") throw new Error("read: not a file");
        const out = n.buf.slice(f.pos || 0);
        f.pos = (f.pos || 0) + out.length;
        return out;
      }
      const n = nodes.get(realpathSync(a));
      if (enc === "base64") return Buffer.from(n.buf.toString("base64"), "base64");
      return n.buf;
    },
    readSync(fd, buffer, offset, length, position) {
      const f = fds.get(fd);
      if (!f || !f.readable) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
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
      if (!f.writable) throw Object.assign(new Error("EBADF"), { code: "EBADF" });
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
      nodes.set(f.real, { kind: "file", buf, ino: ++inoSeq, dev: 1 });
      f.pos = buf.length;
      return f.real;
    },
    closeSync(fd) {
      fds.delete(fd);
    },
    readdirSync(p) {
      const dir = realpathSync(p);
      const out = [];
      for (const key of new Set([...nodes.keys(), ...links.keys()])) {
        if (parentOf(key) === dir && key !== dir) out.push(baseOf(key));
      }
      return out.sort();
    },
  };
}

// ---------------------------------------------------------------------------
// Image / canvas stubs. File content encodes the decoded size:
//   "IMG|<label>|<w>|<h>"  -> decodes to w x h
//   "CORRUPT|<label>"      -> the decode fails (a truncated file)
// ---------------------------------------------------------------------------
function sizeFromContent(text) {
  if (text.startsWith("CORRUPT")) return null;
  const m = /^IMG\|([^|]*)\|(\d+)\|(\d+)/.exec(text);
  if (!m) return null;
  return { w: Number(m[2]), h: Number(m[3]) };
}

function makeImageClass() {
  return class Image {
    constructor() {
      this.naturalWidth = 0;
      this.naturalHeight = 0;
      this.width = 0;
      this.height = 0;
      this.onload = null;
      this.onerror = null;
      this._src = "";
    }
    get src() {
      return this._src;
    }
    set src(value) {
      this._src = value;
      setTimeout(() => {
        try {
          const b64 = String(value).replace(/^data:[^;]+;base64,/, "");
          const size = sizeFromContent(Buffer.from(b64, "base64").toString("utf8"));
          if (!size) throw new Error("decode failed");
          this.naturalWidth = size.w;
          this.width = size.w;
          this.naturalHeight = size.h;
          this.height = size.h;
          if (this.onload) this.onload();
        } catch (e) {
          if (this.onerror) this.onerror(e);
        }
      }, 0);
    }
  };
}

/** Default pixels: fully opaque, uniform — an unextractable silhouette. */
function uniformPixels(w, h) {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < data.length; i += 4) {
    data[i] = 200;
    data[i + 1] = 200;
    data[i + 2] = 200;
    data[i + 3] = 255;
  }
  return { data, width: w, height: h };
}

/** Pixels with a transparent border — a real alpha cutout. */
function alphaPixels(w, h) {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      const inside = x > 0 && y > 0 && x < w - 1 && y < h - 1;
      data[o] = 120;
      data[o + 1] = 90;
      data[o + 2] = 60;
      data[o + 3] = inside ? 255 : 0;
    }
  }
  return { data, width: w, height: h };
}

function load({ roots = "", fsImpl = null, pixels = uniformPixels } = {}) {
  const fakeFs = fsImpl || makeFs();
  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    Buffer,
    isApp: true,
    require: (name) => {
      if (name === "fs") return fakeFs;
      throw new Error("unexpected require('" + name + "')");
    },
    Plugin: { register() {} },
    Blockbench: { version: "5.2.2", showQuickMessage() {}, addCSS() {} },
    Project: null,
    Format: null,
    Formats: {},
    Mode: { selected: { id: "edit" } },
    Texture: Object.assign([], { all: [], getDefault: () => null }),
    Animation: { all: [] },
    Undo: { initEdit() {}, finishEdit() {} },
    Canvas: { updateAll() {} },
    Outliner: { root: [], elements: [] },
    Group: Object.assign(class {}, { all: [] }),
    Cube: Object.assign(class {}, { all: [] }),
    Mesh: Object.assign(class {}, { all: [] }),
    Image: makeImageClass(),
    document: {
      createElement() {
        return {
          width: 0,
          height: 0,
          getContext() {
            return {
              drawImage() {},
              getImageData(_x, _y, w, h) {
                return pixels(w, h);
              },
            };
          },
        };
      },
    },
    settings: {
      blockbench_mcp_ref_roots: { get: () => roots },
    },
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });
  return { api: sandbox.__MCP_BATCH__, sandbox, fs: fakeFs };
}

const ROOT = "/home/dev/refs";
const DIR = ROOT + "/hero";

/** A folder inside the trusted root, with the given files. */
function seed(f, files) {
  f.mkdir("/home");
  f.mkdir("/home/dev");
  f.mkdir(ROOT);
  f.mkdir(DIR);
  for (const [name, content] of Object.entries(files)) f.put(DIR + "/" + name, content);
  return f;
}

const manifest = (doc) => JSON.stringify(doc);
const img = (label, w = 512, h = 512) => `IMG|${label}|${w}|${h}`;

/**
 * Values built inside the vm have a different Array/Object prototype, so a
 * deepEqual against a host-side literal fails on identity rather than content.
 * Round-tripping through JSON puts both sides in the same realm.
 */
const plain = (v) => JSON.parse(JSON.stringify(v));

// ===========================================================================
// sha256Hex — the identity of a reference file
// ===========================================================================

test("sha256Hex matches Node's crypto for every message-length class", () => {
  const { api } = load();
  const cases = [
    Buffer.alloc(0),
    Buffer.from("a"),
    Buffer.from("abc"),
    Buffer.from("x".repeat(55)),
    Buffer.from("x".repeat(56)),
    Buffer.from("x".repeat(63)),
    Buffer.from("x".repeat(64)),
    Buffer.from("x".repeat(65)),
    Buffer.from("y".repeat(1000)),
    crypto.randomBytes(4096),
  ];
  for (const buf of cases) {
    const expected = crypto.createHash("sha256").update(buf).digest("hex");
    assert.equal(api.sha256Hex(buf), expected, `sha256 of ${buf.length} bytes`);
  }
});

test("sha256Hex accepts a plain Uint8Array as well as a Buffer", () => {
  const { api } = load();
  const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]);
  assert.equal(api.sha256Hex(bytes), crypto.createHash("sha256").update(Buffer.from(bytes)).digest("hex"));
});

// ===========================================================================
// manifest parsing — view binding is explicit, never guessed
// ===========================================================================

test("parseRefManifest binds views and marks extras", () => {
  const { api } = load();
  const out = api.parseRefManifest(
    manifest({
      version: "sprefs/1",
      references: [
        { file: "a.png", view: "front" },
        { file: "b.jpg", view: "left", name: "left side" },
        { file: "iso.png", role: "extra" },
        { file: "plain.png" },
      ],
    })
  );
  assert.equal(out.version, "sprefs/1");
  assert.equal(out.problems.length, 0);
  assert.deepEqual(plain(out.entries.map((e) => [e.file, e.view, e.role])), [
    ["a.png", "front", null],
    ["b.jpg", "left", null],
    ["iso.png", null, "extra"],
    ["plain.png", null, null],
  ]);
  assert.equal(out.entries[1].name, "left side");
  assert.equal(out.entries[0].name, "a.png", "the file name is the default label");
});

test("parseRefManifest REFUSES an extra that also names a view", () => {
  const { api } = load();
  const out = api.parseRefManifest(
    manifest({ version: "sprefs/1", references: [{ file: "iso.png", role: "extra", view: "top" }] })
  );
  assert.equal(out.entries.length, 0);
  assert.match(out.problems[0].error, /role "extra" but also names view "top"/);
});

test("parseRefManifest collects per-entry problems and keeps the valid entries", () => {
  const { api } = load();
  const out = api.parseRefManifest(
    manifest({
      version: "sprefs/1",
      references: [
        { file: "ok.png", view: "front" },
        { file: "bad.png", view: "frontish" },
        { file: "dup.png", view: "back" },
        { file: "dup.png", view: "top" },
        { file: "../escape.png", view: "left" },
        { file: "same.png", view: "left" },
        { file: "other.png", view: "left" },
        { file: "x.png", role: "primary" },
      ],
    })
  );
  assert.equal(out.entries.length, 3, "ok.png, dup.png(back) and same.png survive");
  assert.deepEqual(plain(out.entries.map((e) => e.file)), ["ok.png", "dup.png", "same.png"]);
  const text = out.problems.map((p) => p.entry + ": " + p.error).join("\n");
  assert.match(text, /references\[1\]: .*is not one of: front, back, left, right, top/);
  assert.match(text, /references\[3\]: .*repeats the file "dup\.png"/);
  assert.match(text, /references\[4\]: .*plain file name/);
  assert.match(text, /references\[6\]: .*names view "left", which references\[5\] already uses/);
  assert.match(text, /references\[7\]: .*\.role must be "extra"/);
});

test("parseRefManifest never infers a view from a file name", () => {
  const { api } = load();
  const out = api.parseRefManifest(
    manifest({ version: "sprefs/1", references: [{ file: "front.png" }, { file: "left_side.jpg" }] })
  );
  assert.deepEqual(plain(out.entries.map((e) => e.view)), [null, null]);
});

test("parseRefManifest rejects a wrong version, bad JSON and a non-object", () => {
  const { api } = load();
  assert.throws(() => api.parseRefManifest(manifest({ version: "sprefs/2", references: [] })), /unsupported manifest version "sprefs\/2"/);
  assert.throws(() => api.parseRefManifest(manifest({ references: [] })), /unsupported manifest version/);
  assert.throws(() => api.parseRefManifest("{oops"), /not valid JSON/);
  assert.throws(() => api.parseRefManifest("[]"), /must be a JSON object/);
  assert.throws(() => api.parseRefManifest(manifest({ version: "sprefs/1" })), /needs a "references" array/);
  // A non-object ENTRY is a per-entry problem (the rest still load), not a
  // structural failure of the whole file.
  const bad = api.parseRefManifest(manifest({ version: "sprefs/1", references: ["a.png"] }));
  assert.equal(bad.entries.length, 0);
  assert.match(bad.problems[0].error, /references\[0\] must be an object/);
});

test("refPlainName refuses anything that is a path, a traversal or a NUL byte", () => {
  const { api } = load();
  assert.equal(api.refPlainName("front.png"), "front.png");
  assert.throws(() => api.refPlainName(""), /is empty/);
  assert.throws(() => api.refPlainName("a/b.png"), /plain file name/);
  assert.throws(() => api.refPlainName("a\\b.png"), /plain file name/);
  assert.throws(() => api.refPlainName(".."), /is not a file name/);
  assert.throws(() => api.refPlainName("a\0b.png"), /NUL byte/);
});

test("refMimeOf accepts the four image types and nothing else", () => {
  const { api } = load();
  assert.equal(api.refMimeOf("a.PNG"), "image/png");
  assert.equal(api.refMimeOf("a.jpg"), "image/jpeg");
  assert.equal(api.refMimeOf("a.jpeg"), "image/jpeg");
  assert.equal(api.refMimeOf("a.webp"), "image/webp");
  assert.equal(api.refMimeOf("sprefs.json"), null);
  assert.equal(api.refMimeOf("noext"), null);
  assert.equal(api.refExtOf("A.PnG"), ".png");
});

test("refBatchLimit rejects a nonsense limit instead of silently defaulting", () => {
  const { api } = load();
  assert.equal(api.refBatchLimit(null, 5, "x"), 5);
  assert.equal(api.refBatchLimit("12", 5, "x"), 12);
  assert.throws(() => api.refBatchLimit(0, 5, "max_file_bytes"), /max_file_bytes must be a positive number/);
  assert.throws(() => api.refBatchLimit(-1, 5, "max_file_bytes"), /must be a positive number/);
  assert.throws(() => api.refBatchLimit("lots", 5, "max_file_bytes"), /must be a positive number/);
});

// ===========================================================================
// path safety — the 3.3a layer is the only way in
// ===========================================================================

test("load_references_dir FAILS CLOSED when no trusted roots are configured", async () => {
  const { api, fs: f } = load({ roots: "" });
  seed(f, { "front.png": img("front") });
  await assert.rejects(
    api.commands.load_references_dir({ dir: DIR }),
    /no trusted reference roots are configured/
  );
});

test("load_references_dir refuses a folder outside every trusted root", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  f.mkdir("/etc"); f.put("/etc/front.png", img("front"));
  await assert.rejects(
    api.commands.load_references_dir({ dir: "/etc" }),
    /outside every trusted reference root/
  );
});

test("load_references_dir refuses a relative dir (no guessing where it points)", async () => {
  const { api } = load({ roots: ROOT });
  await assert.rejects(api.commands.load_references_dir({ dir: "refs/hero" }), /must be absolute/);
  await assert.rejects(api.commands.load_references_dir({}), /`dir` is required/);
});

test("a symlink inside the folder is SKIPPED with a reason, never followed", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "front.png": img("front") });
  f.mkdir("/secret"); f.put("/secret/id_rsa", img("secret"));
  f.symlink(DIR + "/leak.png", "/secret/id_rsa");
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.loaded.length, 1);
  const skip = res.skipped.find((s) => s.file === "leak.png");
  assert.ok(skip, "the symlink must be reported");
  assert.equal(skip.reason, "symlink");
  assert.equal(res.loaded[0].file, "front.png");
});

// ===========================================================================
// the happy path: manifest -> views, coverage, extras
// ===========================================================================

function fullManifest() {
  return manifest({
    version: "sprefs/1",
    references: [
      { file: "f.png", view: "front" },
      { file: "b.png", view: "back" },
      { file: "l.png", view: "left" },
      { file: "r.png", view: "right" },
      { file: "t.png", view: "top" },
      { file: "iso.png", role: "extra" },
    ],
  });
}

function fullFiles() {
  return {
    "sprefs.json": fullManifest(),
    "f.png": img("f"),
    "b.png": img("b"),
    "l.png": img("l"),
    "r.png": img("r"),
    "t.png": img("t"),
    "iso.png": img("iso", 640, 480),
  };
}

test("a sprefs/1 manifest binds all five views and keeps the extra out of them", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  const res = await api.commands.load_references_dir({ dir: DIR });

  assert.equal(res.manifest.used, true);
  assert.equal(res.manifest.version, "sprefs/1");
  assert.equal(res.manifest.entry_count, 6);
  assert.equal(res.errors.length, 0);
  assert.equal(res.loaded.length, 6);
  assert.deepEqual([...res.coverage.covered], ["front", "back", "left", "right", "top"]);
  assert.deepEqual([...res.coverage.missing], []);
  assert.equal(res.coverage.complete, true);
  assert.deepEqual([...res.extras], ["iso.png"]);

  const iso = res.loaded.find((r) => r.file === "iso.png");
  assert.equal(iso.role, "extra");
  assert.equal(iso.view, null, "an extra never carries a view");
  assert.equal(iso.width, 640);
  assert.equal(iso.height, 480);
  // The extra is stored and visible, just not usable as a view target.
  assert.equal(api.G.references.length, 6);
  assert.ok(api.G.references.every((r) => typeof r.sha256 === "string" && r.sha256.length === 64));
});

test("the response carries NO image data", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  const res = await api.commands.load_references_dir({ dir: DIR });
  const json = JSON.stringify(res);
  assert.ok(!json.includes("base64"), "no base64 payload may leak into the result");
  assert.ok(!json.includes("data:image"), "no data URL may leak into the result");
});

test("an extra can never satisfy a view — not for compare, not as the default pick", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    "sprefs.json": manifest({ version: "sprefs/1", references: [{ file: "iso.png", role: "extra" }] }),
    "iso.png": img("iso"),
  });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.loaded.length, 1);
  assert.equal(api.viewReference("front"), null, "no view is covered by an extra");
  // The implicit "most recent reference" must skip the extra too.
  assert.equal(api.pickReference(), null);
  // Naming it explicitly still works — it IS loaded, just not a view target.
  assert.ok(api.pickReference(res.loaded[0].id));
});

test("a labelled reference still wins over an extra for its view", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  await api.commands.load_references_dir({ dir: DIR });
  const front = api.viewReference("front");
  assert.ok(front);
  assert.equal(front.file, "f.png");
  assert.equal(front.view, "front");
  assert.equal(front.extra, false);
});

test("with NO manifest every image loads as an extra and the reason is stated", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "front.png": img("front"), "side.png": img("side") });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.manifest.used, false);
  assert.equal(res.loaded.length, 2);
  assert.deepEqual([...res.loaded.map((r) => r.role)], ["extra", "extra"]);
  assert.deepEqual([...res.coverage.covered], []);
  assert.deepEqual([...res.coverage.missing], ["front", "back", "left", "right", "top"]);
  assert.equal(res.coverage.complete, false);
  assert.ok(
    res.notes.some((n) => /no manifest "sprefs\.json"/.test(n)),
    "the caller must be told why nothing is bound to a view"
  );
  assert.ok(res.notes.some((n) => /No view was inferred from any file name/.test(n)));
});

test("include_unlisted:false loads ONLY what the manifest names", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  const res = await api.commands.load_references_dir({ dir: DIR, include_unlisted: false });
  assert.equal(res.loaded.length, 6, "all six are listed in the manifest");
  assert.deepEqual([...res.unlisted], []);
});

test("an unlisted image in the folder is loaded as an extra, not dropped", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    "sprefs.json": manifest({ version: "sprefs/1", references: [{ file: "f.png", view: "front" }] }),
    "f.png": img("f"),
    "surprise.png": img("surprise"),
  });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.deepEqual([...res.unlisted], ["surprise.png"]);
  const s = res.loaded.find((r) => r.file === "surprise.png");
  assert.equal(s.role, "extra");
  assert.equal(res.loaded.length, 2);
});

test("a BROKEN manifest is reported and the images still load as extras", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "sprefs.json": "{not json", "f.png": img("f") });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.manifest.used, false);
  assert.match(res.manifest.refused, /not valid JSON/);
  assert.equal(res.loaded.length, 1);
  assert.equal(res.loaded[0].role, "extra");
  assert.ok(res.notes.some((n) => /was refused/.test(n)));
});

test("manifest:false skips the manifest entirely", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  const res = await api.commands.load_references_dir({ dir: DIR, manifest: false });
  assert.equal(res.manifest.used, false);
  assert.equal(res.manifest.name, null);
  assert.equal(res.loaded.length, 6);
  assert.deepEqual([...res.coverage.covered], []);
});

// ===========================================================================
// SHA-256 de-duplication and reload
// ===========================================================================

test("identical bytes are reported as a duplicate and NOT stored twice", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    "sprefs.json": manifest({ version: "sprefs/1", references: [{ file: "a.png", view: "front" }] }),
    "a.png": img("same"),
    "copy.png": img("same"), // identical content under another name
  });
  const first = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(first.loaded.length, 1, "only ONE copy is stored");
  assert.equal(first.duplicates.length, 1, "the second file is recognised as the same bytes");
  assert.equal(first.duplicates[0].file, "copy.png");
  assert.match(first.duplicates[0].note, /identical bytes already loaded/);
  assert.equal(api.G.references.length, 1);
  const bytesAfterFirst = api.G.refBytes;

  const second = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(second.loaded.length, 0, "nothing new is stored");
  assert.equal(second.duplicates.length, 2, "both files match the one stored copy");
  assert.equal(api.G.references.length, 1, "still one reference, never four");
  assert.equal(api.G.refBytes, bytesAfterFirst, "the memory budget did not grow");
  for (const d of second.duplicates) assert.equal(d.sha256.length, 64);
});

test("re-loading the SAME folder is idempotent", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  await api.commands.load_references_dir({ dir: DIR });
  const n = api.G.references.length;
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.loaded.length, 0);
  assert.equal(res.duplicates.length, 6);
  assert.equal(res.errors.length, 0);
  assert.equal(api.G.references.length, n);
  assert.deepEqual([...res.coverage.covered], ["front", "back", "left", "right", "top"]);
});

test("a duplicate whose bytes are already loaded as an extra gets the view BOUND", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "iso.png": img("hero") });
  const first = await api.commands.load_references_dir({ dir: DIR });
  const id = first.loaded[0].id;
  assert.equal(first.loaded[0].role, "extra");

  // Now a manifest arrives that declares those same bytes as the front view.
  f.put(DIR + "/sprefs.json", manifest({ version: "sprefs/1", references: [{ file: "iso.png", view: "front" }] }));
  const second = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(second.loaded.length, 0);
  assert.equal(second.duplicates.length, 1);
  assert.equal(second.duplicates[0].bound_view, "front");
  assert.equal(api.G.references.length, 1, "no second copy was created");
  const entry = api.G.references.find((r) => r.id === id);
  assert.equal(entry.view, "front");
  assert.equal(entry.extra, false, "binding a view makes it a comparison target, not material");
  assert.deepEqual([...second.coverage.covered], ["front"]);
});

test("a duplicate that would CONTRADICT an existing view is an error, not a silent rebind", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    "sprefs.json": manifest({ version: "sprefs/1", references: [{ file: "a.png", view: "front" }] }),
    "a.png": img("hero"),
  });
  await api.commands.load_references_dir({ dir: DIR });
  f.put(DIR + "/sprefs.json", manifest({ version: "sprefs/1", references: [{ file: "a.png", view: "back" }] }));
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.errors.length, 1);
  assert.match(res.errors[0].error, /already loaded as view "front".*wants view "back"/);
  assert.equal(api.viewReference("front").view, "front", "the old binding was not overwritten");
});

test("reload:true replaces the entry in place and keeps the budget honest", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "a.png": img("v1") });
  const first = await api.commands.load_references_dir({ dir: DIR });
  const id1 = first.loaded[0].id;
  const bytes1 = api.G.refBytes;

  const res = await api.commands.load_references_dir({ dir: DIR, reload: true });
  assert.equal(res.loaded.length, 1);
  assert.equal(res.loaded[0].reloaded, true);
  assert.notEqual(res.loaded[0].id, id1, "a fresh entry replaces the old one");
  assert.equal(api.G.references.length, 1);
  assert.equal(api.G.refBytes, bytes1, "the old bytes were released before the new ones were reserved");
});

test("clear_references releases the memory budget it was holding", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  await api.commands.load_references_dir({ dir: DIR });
  assert.ok(api.G.refBytes > 0);
  const cleared = api.commands.clear_references();
  assert.equal(cleared.cleared, 6);
  assert.equal(api.G.refBytes, 0, "an empty store must not look full");
  // And loading works again afterwards.
  const again = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(again.loaded.length, 6);
});

// ===========================================================================
// limits — every failure is PER FILE
// ===========================================================================

test("a corrupt file is a per-file error and the rest still load", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    "sprefs.json": manifest({
      version: "sprefs/1",
      references: [
        { file: "f.png", view: "front" },
        { file: "broken.png", view: "left" },
        { file: "b.png", view: "back" },
      ],
    }),
    "f.png": img("f"),
    "broken.png": "CORRUPT|broken",
    "b.png": img("b"),
  });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.loaded.length, 2);
  assert.equal(res.errors.length, 1);
  assert.equal(res.errors[0].file, "broken.png");
  assert.match(res.errors[0].error, /not a decodable image/);
  // The two good views still bound.
  assert.deepEqual([...res.coverage.covered], ["front", "back"]);
  assert.deepEqual([...res.coverage.missing], ["left", "right", "top"]);
});

test("a file over max_file_bytes is skipped per file, not fatally", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    // 13 bytes vs 68 bytes: a 40-byte cap admits exactly one of them.
    "small.png": "IMG|s|512|512",
    "big.png": "IMG|" + "x".repeat(60) + "|512|512",
  });
  const res = await api.commands.load_references_dir({ dir: DIR, max_file_bytes: 40 });
  assert.equal(res.errors.length, 1);
  assert.equal(res.errors[0].file, "big.png");
  assert.match(res.errors[0].error, /over the 40 byte limit/);
  assert.equal(res.loaded.length, 1, "the small file still loaded");
  assert.equal(res.loaded[0].file, "small.png");
});

test("an image over max_side_px is skipped per file", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "huge.png": img("huge", 9000, 100), "ok.png": img("ok", 300, 200) });
  const res = await api.commands.load_references_dir({ dir: DIR, max_side_px: 4096 });
  assert.equal(res.errors.length, 1);
  assert.equal(res.errors[0].file, "huge.png");
  assert.match(res.errors[0].error, /9000x100, over the 4096 px side limit/);
  assert.equal(res.loaded.length, 1);
});

test("the total budget refuses only the file that would cross it", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, { "a.png": img("a"), "b.png": img("b"), "c.png": img("c") });
  // Each stored data URL is 22 + 20 = 42 bytes, so a 50-byte budget admits
  // exactly one and the other two are refused — not a fatal call error.
  const res = await api.commands.load_references_dir({ dir: DIR, max_total_bytes: 50 });
  assert.equal(res.loaded.length, 1);
  assert.equal(res.errors.length, 2);
  for (const e of res.errors) assert.match(e.error, /reference memory limit reached/);
  assert.equal(api.G.references.length, 1);
  assert.ok(api.G.refBytes <= 50);
  assert.equal(res.totals.ref_bytes_limit, 50);
});

test("one failing file does not abort the files after it", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, {
    "sprefs.json": manifest({
      version: "sprefs/1",
      references: [
        { file: "a_broken.png", view: "front" },
        { file: "b_big.png", view: "back" },
        { file: "c_ok.png", view: "left" },
        { file: "d_ok.png", view: "right" },
      ],
    }),
    "a_broken.png": "CORRUPT|a",
    "b_big.png": "IMG|b|9000|9000",
    "c_ok.png": img("c"),
    "d_ok.png": img("d"),
  });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.deepEqual([...res.loaded.map((r) => r.file)], ["c_ok.png", "d_ok.png"]);
  assert.deepEqual([...res.errors.map((e) => e.file)], ["a_broken.png", "b_big.png"]);
  assert.deepEqual([...res.coverage.covered], ["left", "right"]);
});

// ===========================================================================
// silhouette quality is ADVISORY
// ===========================================================================

test("an unextractable silhouette is a WARNING — the image still loads", async () => {
  const { api, fs: f } = load({ roots: ROOT, pixels: uniformPixels });
  seed(f, { "flat.png": img("flat") });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.loaded.length, 1, "quality never gates a load");
  assert.equal(res.loaded[0].quality, "unreliable");
  assert.equal(res.warnings.length, 1);
  assert.match(res.warnings[0].warning, /no separable silhouette/);
  assert.equal(api.G.references.length, 1);
  assert.equal(api.G.references[0].quality.value, "unreliable");
});

test("a real alpha cutout reports quality 'alpha' with no warning", async () => {
  const { api, fs: f } = load({ roots: ROOT, pixels: alphaPixels });
  seed(f, { "cut.png": img("cut", 64, 64) });
  const res = await api.commands.load_references_dir({ dir: DIR });
  assert.equal(res.loaded.length, 1);
  assert.equal(res.loaded[0].quality, "alpha");
  assert.equal(res.warnings.length, 0);
});

// ===========================================================================
// inventory
// ===========================================================================

test("list_references exposes the role, the file identity and the coverage", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  await api.commands.load_references_dir({ dir: DIR });
  const list = api.commands.list_references();
  assert.equal(list.count, 6);
  assert.deepEqual([...Object.keys(list.coverage)], ["front", "back", "left", "right", "top"]);
  assert.equal(list.coverage.front, true);
  const iso = list.references.find((r) => r.file === "iso.png");
  assert.equal(iso.role, "extra");
  assert.equal(iso.view, null);
  assert.equal(iso.sha256.length, 64);
  assert.equal(iso.source, "dir");
});

test("every reference loaded from a folder carries its sha256, file and dir", async () => {
  const { api, fs: f } = load({ roots: ROOT });
  seed(f, fullFiles());
  await api.commands.load_references_dir({ dir: DIR });
  for (const r of api.G.references) {
    assert.equal(typeof r.sha256, "string");
    assert.equal(r.sha256.length, 64);
    assert.ok(r.file, "the source file name is kept");
    assert.equal(r.dir, DIR);
    assert.equal(r.source, "dir");
  }
});

// ===========================================================================
// small helpers
// ===========================================================================

test("checkRefBudget refuses WITHOUT spending the budget", () => {
  const { api } = load();
  api.G.refBytes = 900;
  assert.equal(api.checkRefBudget(50, 1000), 950, "a fitting reservation is reported");
  assert.equal(api.G.refBytes, 900, "…and nothing was reserved yet");
  assert.throws(() => api.checkRefBudget(200, 1000), /reference memory limit reached/);
  assert.equal(api.G.refBytes, 900, "a refused check changed nothing");
});

test("refImageEntries keeps only supported image types", () => {
  const { api } = load();
  const out = api.refImageEntries({
    entries: [
      { name: "a.png" },
      { name: "b.JPG" },
      { name: "sprefs.json" },
      { name: "notes.txt" },
      { name: "noext" },
    ],
  });
  assert.deepEqual(plain(out.map((e) => e.name)), ["a.png", "b.JPG"]);
  assert.deepEqual(plain(api.refImageEntries(null)), []);
});

test("viewReference ignores extras and returns null when only extras exist", () => {
  const { api } = load();
  api.G.references = [
    { id: "ref_1", view: null, extra: true },
    { id: "ref_2", view: "front", extra: false },
    { id: "ref_3", view: null, extra: true },
  ];
  assert.equal(api.viewReference("front").id, "ref_2");
  assert.equal(api.viewReference("top"), null);
  api.G.references = [{ id: "ref_9", view: "front", extra: true }];
  assert.equal(api.viewReference("front"), null, "an extra can never satisfy a view");
});

test("pickReference still honours an explicit id/name even for an extra", () => {
  const { api } = load();
  api.G.references = [{ id: "ref_1", name: "iso", view: null, extra: true }];
  assert.equal(api.pickReference(), null, "no implicit target when only extras are loaded");
  assert.equal(api.pickReference("ref_1").id, "ref_1");
  assert.equal(api.pickReference("iso").id, "ref_1");
});

// ===========================================================================
// wiring: the tool, the timeout class, and the no-roots-parameter rule
// ===========================================================================

test("load_references_dir is a HEAVY action on BOTH sides of the bridge", () => {
  const pluginHeavy = source.match(/const HEAVY_ACTIONS = \{[\s\S]*?\n\};/);
  assert.ok(pluginHeavy, "plugin HEAVY_ACTIONS table found");
  assert.match(pluginHeavy[0], /load_references_dir:\s*true/);
  const clientSrc = fs.readFileSync(path.join(here, "..", "src", "client.ts"), "utf8");
  const clientHeavy = clientSrc.match(/const HEAVY_ACTIONS = new Set\(\[[\s\S]*?\]\);/);
  assert.ok(clientHeavy, "client HEAVY_ACTIONS set found");
  assert.match(clientHeavy[0], /"load_references_dir"/);
});

test("load_references_dir takes NO roots parameter and documents the Setting", () => {
  const block = source.slice(source.indexOf("async load_references_dir(p)"), source.indexOf("list_references()"));
  assert.ok(block.length > 500, "the command body was found");
  assert.doesNotMatch(block, /p\.roots|params\.roots/, "the caller must never supply roots");
  assert.match(block, /refRoots\(\)/, "roots come from the Setting, via refRoots()");
  assert.match(block, /guardedListDir\(/, "listing goes through the 3.3a guard");
  assert.match(block, /guardedRead\(/, "reading goes through the 3.3a guard");
  assert.doesNotMatch(block, /fs\.readFileSync\(/, "no unguarded read path may exist");
  assert.doesNotMatch(block, /readdirSync\(/, "no unguarded listing path may exist");
});

test("the MCP tool catalogue exposes load_references_dir with a required dir", async () => {
  const { tools } = await import("../dist/tools.js");
  const tool = tools.find((t) => t.name === "load_references_dir");
  assert.ok(tool, "load_references_dir must be in the catalogue");
  assert.deepEqual(tool.inputSchema.required, ["dir"]);
  const props = tool.inputSchema.properties;
  for (const key of ["manifest", "reload", "include_unlisted", "max_file_bytes", "max_total_bytes", "max_side_px"]) {
    assert.ok(props[key], `the tool should expose ${key}`);
  }
  assert.ok(!props.roots, "the tool must NOT accept roots");
  assert.match(tool.description, /sprefs\/1/);
  assert.match(tool.description, /NEVER pass its own roots/);
  assert.match(tool.description, /Nothing is ever inferred from a file name/);
});
