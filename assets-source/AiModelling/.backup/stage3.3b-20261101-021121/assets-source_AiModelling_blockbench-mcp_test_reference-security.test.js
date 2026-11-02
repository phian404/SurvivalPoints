/**
 * Tests for the reference SECURITY layer (stage 3.3a).
 *
 * These guard the rules that make batch reference loading (3.3b-d) safe:
 *
 *  - Trusted roots come ONLY from the plugin Setting. There is no code path
 *    by which an MCP call supplies its own roots, and with no roots
 *    configured everything fails closed.
 *  - guardPath resolves with realpath BEFORE the prefix test, so a symlink
 *    cannot smuggle a path out of (or into) a trusted directory.
 *  - guardedRead is immune to a path swap after the check: it opens first,
 *    fstats the DESCRIPTOR, reads through it, then re-lstats the PATH and
 *    compares dev+ino. The fake fs below can plant each attack and the test
 *    asserts the read is refused.
 *  - guardedWrite uses 'wx' (O_CREAT|O_EXCL) so it never follows or replaces
 *    an existing entry, and verifies the PNG signature on read-back.
 *  - reserveRefBytes enforces the memory budget BEFORE anything is stored.
 *
 * The REAL shipping functions are exercised: the plugin source is loaded into
 * a vm with stubbed Blockbench globals and a hand-built fake fs (same pattern
 * as metric.test.js / parts.test.js).
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
const instrumented = source.replace(
  REGISTER,
  "globalThis.__MCP_SEC__ = { parseRoots, refRoots, guardPath, guardedRead, guardedWrite, guardedListDir, reserveRefBytes, isAbsolutePath, G, PLUGIN_ID };\n" +
    REGISTER
);

/**
 * A tiny in-memory filesystem with symlinks, inodes and a hook to swap a path
 * out from under an open descriptor (the TOCTOU attack guardPath must survive).
 */
function makeFs({ roots = [] } = {}) {
  let inoSeq = 100;
  const nodes = new Map(); // real path -> { buf, ino, dev, kind:'file'|'dir' }
  const links = new Map(); // path -> target (absolute or relative to its dir)
  const norm = (p) => String(p).replace(/\\/g, "/").replace(/([^:])\/{2,}/g, "$1/").replace(/\/+$/, "") || "/";
  const parentOf = (p) => { const i = p.lastIndexOf("/"); return i <= 0 ? "/" : p.slice(0, i); };
  const baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);

  const mkdir = (p) => { nodes.set(norm(p), { kind: "dir", ino: ++inoSeq, dev: 1 }); return p; };
  const put = (p, content = "x") => {
    const n = norm(p);
    nodes.set(n, { kind: "file", buf: Buffer.from(content), ino: ++inoSeq, dev: 1 });
    return n;
  };
  const symlink = (from, to) => { links.set(norm(from), to); return norm(from); };

  // Resolve every symlink component; throw ENOENT when something is missing.
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
      // A directory component may itself be a symlink: rebuild from the root.
      const parts = cur.split("/").filter(Boolean);
      // A Windows drive prefix ("E:") is the root of its own path — keep it
      // instead of turning the path into "/E:/..." (which would never match).
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
      if (changed) { cur = acc; continue; }
      if (!nodes.has(cur)) throw Object.assign(new Error("ENOENT: " + p), { code: "ENOENT" });
      return cur;
    }
    throw new Error("ELOOP: " + p);
  };

  const fds = new Map(); // fd -> { path, real }
  let fdSeq = 7;
  const hooks = { afterRead: null };

  const api = {
    // --- test setup helpers (not part of the plugin's surface) ---
    _nodes: nodes, _links: links, mkdir, put, symlink, norm,
    _hooks: hooks,

    realpathSync,
    statSync(p) {
      const n = nodes.get(realpathSync(p));
      return { isFile: () => n.kind === "file", isDirectory: () => n.kind === "dir", size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev };
    },
    lstatSync(p) {
      const key = norm(p);
      if (links.has(key)) return { isFile: () => false, isDirectory: () => false, isSymbolicLink: () => true, ino: 0, dev: 1 };
      const n = nodes.get(key);
      if (!n) throw Object.assign(new Error("ENOENT: " + p), { code: "ENOENT" });
      return { isFile: () => n.kind === "file", isDirectory: () => n.kind === "dir", isSymbolicLink: () => false, size: n.buf ? n.buf.length : 0, ino: n.ino, dev: n.dev };
    },
    openSync(p, mode) {
      const key = norm(p);
      // O_EXCL create modes: 'wx' is write-ONLY, 'wx+' is read-write. The
      // plugin must use 'wx+' — the fake enforces the same descriptor
      // permissions a real OS gives, so a regression to 'wx' fails here.
      if (mode === "wx" || mode === "wx+") {
        if (nodes.has(key) || links.has(key)) throw Object.assign(new Error("EEXIST: " + p), { code: "EEXIST" });
        const real = realpathSync(parentOf(key)); // parent must exist and be resolved
        const fd = ++fdSeq;
        fds.set(fd, { path: key, real: real + "/" + baseOf(key), fresh: true, writable: true, readable: mode === "wx+", pos: 0 });
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
        // Model the descriptor POSITION: a read starts where the last write
        // left it (the real-world trap the plugin must avoid with readSync).
        const out = n.buf.slice(f.pos || 0);
        f.pos = (f.pos || 0) + out.length;
        // The attack window: the path is swapped AFTER the descriptor was
        // measured and read, but BEFORE the caller re-checks the name.
        if (typeof hooks.afterRead === "function") hooks.afterRead(f.path);
        return out;
      }
      const n = nodes.get(realpathSync(a));
      if (enc === "base64") return Buffer.from(n.buf.toString("base64"), "base64");
      return n.buf;
    },
    // pread: read `length` bytes into `buffer` at an explicit position.
    readSync(fd, buffer, offset, length, position) {
      const f = fds.get(fd);
      if (!f || !f.readable) throw Object.assign(new Error("EBADF: bad file descriptor, read"), { code: "EBADF" });
      const n = nodes.get(f.real);
      if (!n || n.kind !== "file") throw new Error("readSync: not a file");
      const start = position == null ? (f.pos || 0) : position;
      const end = Math.min(n.buf.length, start + length);
      const copied = Math.max(0, end - start);
      n.buf.copy(buffer, offset, start, end);
      if (position == null) f.pos = end;
      return copied;
    },
    writeFileSync(a, data) {
      if (typeof a !== "number") throw new Error("guardedWrite must write through the fd");
      const f = fds.get(a);
      if (!f.writable) throw Object.assign(new Error("EBADF: bad file descriptor, write"), { code: "EBADF" });
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data));
      nodes.set(f.real, { kind: "file", buf, ino: ++inoSeq, dev: 1 });
      f.pos = buf.length; // the write moved the descriptor to the end of file
      return f.real;
    },
    closeSync(fd) { fds.delete(fd); },
    readdirSync(p) {
      const dir = realpathSync(p);
      const out = [];
      for (const key of new Set([...nodes.keys(), ...links.keys()])) {
        if (parentOf(key) === dir && key !== dir) out.push(baseOf(key));
      }
      return out.sort();
    },
  };
  return api;
}

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);
const PNG_B64 = PNG.toString("base64");

/** Load the plugin with `settings` pre-seeded and a fake fs available. */
function load({ roots = "", extraSandbox = {} } = {}) {
  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    Buffer,
    isApp: true,
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
    settings: {
      blockbench_mcp_ref_roots: { get: () => roots },
    },
    ...extraSandbox,
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });
  return { sec: sandbox.__MCP_SEC__, sandbox };
}

const ROOT = "/home/dev/refs";

// ===========================================================================
// parseRoots / refRoots — the Setting is the only source of trust
// ===========================================================================

test("parseRoots splits commas and semicolons, trims and drops trailing slashes", () => {
  const { sec } = load();
  // spread: arrays built inside the vm have a different Array.prototype
  assert.deepEqual([...sec.parseRoots("/a/b, /c/d/ ; /e/f")], ["/a/b", "/c/d", "/e/f"]);
  assert.deepEqual([...sec.parseRoots("")], []);
  assert.deepEqual([...sec.parseRoots(null)], []);
  assert.deepEqual([...sec.parseRoots("E:\\refs;E:\\more")], ["E:\\refs", "E:\\more"]);
});

test("refRoots reads the plugin Setting and drops non-absolute entries", () => {
  const { sec } = load({ roots: "/a/b,relative,/c/d" });
  assert.deepEqual([...sec.refRoots()], ["/a/b", "/c/d"]);
});

test("refRoots FAILS CLOSED: empty or garbage setting means no roots at all", () => {
  assert.equal(load({ roots: "" }).sec.refRoots(), null);
  assert.equal(load({ roots: "   " }).sec.refRoots(), null);
  assert.equal(load({ roots: "relative/only" }).sec.refRoots(), null);
});

test("there is NO parameter path to the roots — guardPath takes them from the Setting only", () => {
  const { sec } = load({ roots: "/trusted" });
  // The only ways in are the `roots` OPTION (internal callers) or the Setting.
  // A command cannot reach either: assert the guard functions never read params.
  assert.match(source, /function refRoots\(\)[\s\S]{0,400}?_ref_roots/);
  assert.equal(sec.refRoots.length, 0, "refRoots takes no arguments — nothing to pass");
  assert.equal(sec.guardPath.length, 2, "guardPath(fs, p, opts) — opts.roots is internal only");
});

// ===========================================================================
// guardPath — containment, symlinks, fail-closed
// ===========================================================================

test("guardPath accepts a file inside the trusted root and reports the root", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT); f.put(ROOT + "/front.png", "data");
  const g = sec.guardPath(f, ROOT + "/front.png");
  assert.equal(g.real, ROOT + "/front.png");
  assert.equal(g.root, ROOT);
});

test("guardPath refuses a path outside every root", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/etc"); f.put("/etc/passwd", "pw");
  assert.throws(() => sec.guardPath(f, "/etc/passwd"), /outside every trusted reference root/);
});

test("guardPath refuses a relative path outright", () => {
  const { sec } = load({ roots: ROOT });
  assert.throws(() => sec.guardPath(makeFs(), "front.png"), /must be absolute/);
});

test("guardPath refuses when NO roots are configured (fail closed)", () => {
  const { sec } = load({ roots: "" });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT); f.put(ROOT + "/a.png", "x");
  assert.throws(() => sec.guardPath(f, ROOT + "/a.png"), /no trusted reference roots are configured/);
});

test("guardPath cannot be escaped by a symlink INSIDE the root pointing out", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.mkdir("/secret"); f.put("/secret/id_rsa", "key");
  f.symlink(ROOT + "/innocent.png", "/secret/id_rsa");
  assert.throws(() => sec.guardPath(f, ROOT + "/innocent.png"), /outside every trusted reference root/);
});

test("guardPath cannot be escaped by a symlinked PARENT directory", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.mkdir("/other"); f.put("/other/real.png", "x");
  f.symlink(ROOT + "/sub", "/other");
  assert.throws(() => sec.guardPath(f, ROOT + "/sub/real.png"), /outside every trusted reference root/);
});

test("guardPath resolves a symlinked ROOT itself, so the check uses real paths", () => {
  const { sec } = load({ roots: "/config/refs" });
  const f = makeFs();
  // The symlink target must EXIST — a link to a missing directory is broken
  // and realpath refuses it (as it would on a real filesystem).
  f.mkdir("/config"); f.mkdir("/real"); f.mkdir("/real/refs");
  f.symlink("/config/refs", "/real/refs");
  f.put("/real/refs/front.png", "x");
  const g = sec.guardPath(f, "/config/refs/front.png");
  assert.equal(g.real, "/real/refs/front.png", "compared on the resolved path");
  assert.equal(g.root, "/real/refs");
});

test("guardPath is separator-dialect agnostic (Windows backslashes still match)", () => {
  const { sec } = load({ roots: "E:\\refs" });
  const f = makeFs();
  f.mkdir("E:/refs"); f.put("E:/refs/front.png", "x");
  const g = sec.guardPath(f, "E:\\refs\\front.png");
  assert.equal(g.real, "E:/refs/front.png");
});

test("guardPath rejects a prefix LOOKALIKE (/refs vs /refs_evil)", () => {
  const { sec } = load({ roots: "/refs" });
  const f = makeFs();
  f.mkdir("/refs"); f.mkdir("/refs_evil"); f.put("/refs_evil/a.png", "x");
  assert.throws(() => sec.guardPath(f, "/refs_evil/a.png"), /outside every trusted reference root/);
});

test("guardPath(forWrite) allows a NEW file in a trusted dir and rejects a new file outside", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  const g = sec.guardPath(f, ROOT + "/out/part.png", { forWrite: true });
  assert.equal(g.real, ROOT + "/out/part.png");
  assert.throws(
    () => sec.guardPath(f, "/tmp/evil.png", { forWrite: true }),
    /outside every trusted reference root|cannot resolve/,
  );
});

// ===========================================================================
// guardedRead — TOCTOU resistance
// ===========================================================================

test("guardedRead returns bytes for a clean in-root file", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  const real = f.put(ROOT + "/front.png", "hello");
  const r = sec.guardedRead(f, ROOT + "/front.png");
  assert.equal(r.buffer.toString(), "hello");
  assert.equal(r.real, real);
  assert.equal(r.size, 5);
});

test("guardedRead refuses a file larger than maxBytes (measured on the fd, not the name)", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.put(ROOT + "/big.png", "x".repeat(5000));
  assert.throws(() => sec.guardedRead(f, ROOT + "/big.png", null, { maxBytes: 1000 }), /over the 1000 byte limit/);
});

test("guardedRead refuses a DIRECTORY (fstat says not a regular file)", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  assert.throws(() => sec.guardedRead(f, ROOT), /not a regular file/);
});

test("TOCTOU: a symlink planted at the path AFTER the open is caught by the re-lstat", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.mkdir("/secret"); f.put("/secret/id_rsa", "key");
  f.put(ROOT + "/ok.png", "good");
  f._hooks.afterRead = (key) => { f.symlink(key, "/secret/id_rsa"); }; // swap mid-read
  assert.throws(
    () => sec.guardedRead(f, ROOT + "/ok.png"),
    /swapped for a symlink while reading/,
  );
});

test("TOCTOU: an inode swap after the open is caught by dev+ino comparison", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.put(ROOT + "/ok.png", "good");
  f._hooks.afterRead = (key) => { f.put(key, "EVIL"); }; // same name, brand-new inode
  assert.throws(() => sec.guardedRead(f, ROOT + "/ok.png"), /changed under us \(inode swap\)/);
});

test("guardedRead rejects a path outside the roots before opening anything", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/etc"); f.put("/etc/passwd", "pw");
  let opened = 0;
  const orig = f.openSync.bind(f);
  f.openSync = (p, m) => { opened++; return orig(p, m); };
  assert.throws(() => sec.guardedRead(f, "/etc/passwd"), /outside every trusted reference root/);
  assert.equal(opened, 0, "the guard must run before any open");
});

// ===========================================================================
// guardedWrite — O_EXCL semantics + PNG verification
// ===========================================================================

test("guardedWrite creates a new PNG and verifies it on read-back", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  const r = sec.guardedWrite(f, ROOT + "/crop_head.png", null, PNG_B64);
  assert.equal(r.path, ROOT + "/crop_head.png");
  assert.equal(r.bytes, PNG.length);
  assert.deepEqual([...f._nodes.get(ROOT + "/crop_head.png").buf], [...PNG]);
});

test("guardedWrite refuses to overwrite an EXISTING file (no silent replacement)", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.put(ROOT + "/taken.png", "someone else's");
  assert.throws(() => sec.guardedWrite(f, ROOT + "/taken.png", null, PNG_B64), /cannot create .*exists or is a planted link/);
  assert.equal(f._nodes.get(ROOT + "/taken.png").buf.toString(), "someone else's", "the old file survived");
});

test("guardedWrite refuses to write through a symlink planted at the target name", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.mkdir("/secret"); f.put("/secret/.bashrc", "orig");
  f.symlink(ROOT + "/evil.png", "/secret/.bashrc");
  // Two layers can catch this — realpath (resolves to /secret/.bashrc, outside
  // the root) or the O_EXCL open. Either is fine; the target must survive.
  assert.throws(
    () => sec.guardedWrite(f, ROOT + "/evil.png", null, PNG_B64),
    /outside every trusted reference root|cannot create/,
  );
  assert.equal(f._nodes.get("/secret/.bashrc").buf.toString(), "orig", "the symlink target was NOT touched");
});

test("guardedWrite refuses non-PNG bytes (signature checked on read-back)", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  assert.throws(
    () => sec.guardedWrite(f, ROOT + "/x.png", null, Buffer.from("not a png").toString("base64")),
    /does not start with a PNG signature/,
  );
});

test("guardedWrite outside the roots is refused before any open", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/tmp");
  assert.throws(() => sec.guardedWrite(f, "/tmp/x.png", null, PNG_B64), /outside every trusted reference root|cannot resolve/);
});

// ===========================================================================
// guardedListDir — regular files only
// ===========================================================================

test("guardedListDir returns regular files and skips symlinks/dirs with reasons", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/home"); f.mkdir("/home/dev"); f.mkdir(ROOT);
  f.put(ROOT + "/a.png", "1"); f.put(ROOT + "/b.jpg", "22");
  f.mkdir(ROOT + "/sub");
  f.mkdir("/outside"); f.put("/outside/c.png", "3");
  f.symlink(ROOT + "/c.png", "/outside/c.png");
  const r = sec.guardedListDir(f, ROOT);
  assert.deepEqual([...r.entries.map((e) => e.name).sort()], ["a.png", "b.jpg"]);
  assert.deepEqual([...r.skipped.map((s) => s.name).sort()], ["c.png", "sub"]);
  assert.equal(r.skipped.find((s) => s.name === "c.png").reason, "symlink");
});

test("guardedListDir refuses a directory outside the roots", () => {
  const { sec } = load({ roots: ROOT });
  const f = makeFs();
  f.mkdir("/etc");
  assert.throws(() => sec.guardedListDir(f, "/etc"), /outside every trusted reference root/);
});

// ===========================================================================
// reserveRefBytes — the memory budget is checked BEFORE storing
// ===========================================================================

test("reserveRefBytes accumulates and refuses the file that would cross the cap", () => {
  const { sec } = load();
  sec.G.refBytes = 0;
  assert.equal(sec.reserveRefBytes(100, 1000), 100);
  assert.equal(sec.reserveRefBytes(500, 1000), 600);
  assert.throws(
    () => sec.reserveRefBytes(500, 1000),
    /reference memory limit reached: storing this image would use 1100 bytes of the 1000/,
  );
  assert.equal(sec.G.refBytes, 600, "a refused reservation changed nothing");
});

test("reserveRefBytes defaults to a 64 MB cap and reports the used budget", () => {
  const { sec } = load();
  sec.G.refBytes = 63 * 1024 * 1024;
  assert.throws(() => sec.reserveRefBytes(2 * 1024 * 1024), /of the 67108864 allowed \(max_total_bytes\)/);
});

// ===========================================================================
// Source-level guards: the rules that must not silently regress
// ===========================================================================

test("no command accepts a `roots` parameter from MCP input", () => {
  // The security layer must never be steerable by the caller.
  assert.doesNotMatch(source, /p\.roots|params\.roots|p && p\.roots/);
  assert.doesNotMatch(source, /roots:\s*\{\s*type/);
});

test("the trusted-roots Setting exists and documents that MCP cannot pass roots", () => {
  assert.match(source, /PLUGIN_ID \+ '_ref_roots'/);
  assert.match(source, /MCP calls can NEVER pass their own roots/);
});

test("guardedRead opens before it trusts the name (fd-first ordering)", () => {
  const m = source.match(/function guardedRead[\s\S]*?\n\tif \(!buf \|\| !buf\.length\)/);
  assert.ok(m, "guardedRead body found");
  const body = m[0];
  assert.ok(
    body.indexOf("openSync") < body.indexOf("fstatSync"),
    "must open first, then fstat the DESCRIPTOR",
  );
  assert.ok(
    body.indexOf("readFileSync(fd)") > body.indexOf("fstatSync"),
    "must read through the same descriptor it measured",
  );
  assert.ok(body.includes("lstatSync"), "must re-check the path after reading");
});

test("guardedWrite uses O_EXCL, a readable fd ('wx+') and a positioned read-back", () => {
  const m = source.match(/function guardedWrite[\s\S]*?return \{ path: g\.real/);
  assert.ok(m, "guardedWrite body found");
  // 'wx' alone = O_EXCL but write-only: the read-back died with EBADF on the
  // real filesystem (live E2E). 'wx+' keeps O_EXCL and allows verifying reads.
  assert.ok(m[0].includes("'wx+'"), "must open with O_CREAT|O_EXCL|O_RDWR");
  assert.ok(!/writeFileSync\([^,]+,\s*base64,\s*'base64'\)/.test(m[0]), "must not pass an encoding to an fd write");
  // Second real-filesystem trap: readFileSync(fd) starts at the descriptor's
  // CURRENT position — which the write just moved to EOF — and reads nothing.
  // The verification must be a positioned read (pread) from offset 0.
  assert.ok(/readSync\(fd,[^)]*,\s*0\s*\)/.test(m[0]), "must read back with an explicit position 0");
  assert.ok(!/const back = fs\.readFileSync\(fd\)/.test(m[0]), "must not read back from the write position");
});
