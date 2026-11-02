/**
 * Tests for render_previews — the disk-writing preview command.
 *
 * Like the other bridge tests, the plugin is a Blockbench-renderer script, so
 * it is loaded into a vm context with stubbed Blockbench globals and its
 * `commands` table is captured. That means these tests exercise the REAL
 * writing/verification code that ships, not a copy of it.
 *
 * Everything lands in a throwaway folder under os.tmpdir(); no project file,
 * model or texture on disk is read or written.
 *
 *   npm test
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import vm from "node:vm";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN = path.join(here, "..", "plugin", "blockbench_mcp.js");
const source = fs.readFileSync(PLUGIN, "utf8");

const REGISTER = "Plugin.register(PLUGIN_ID, {";
assert.ok(source.includes(REGISTER), "plugin registration call moved — update this harness");
const instrumented = source.replace(
  REGISTER,
  "globalThis.__MCP_COMMANDS__ = commands;\n" + REGISTER
);

const nodeRequire = createRequire(import.meta.url);

// Two different, valid 1x1 PNGs. The second is used to prove a re-run really
// replaces the bytes instead of skipping the write.
const PNG_A =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
const PNG_B =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

/** A scratch directory, removed by the caller. */
function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "bbmcp-previews-"));
}

/**
 * Load the plugin against a stub Blockbench. `opts.shots` controls what the
 * fake screenshot callback returns per view ('png' | 'empty' | 'garbage' |
 * 'nodata'), and `opts.onShot` observes each render call.
 */
function loadPlugin(opts = {}) {
  const shots = opts.shots || {};
  const rendered = [];

  class Group {
    static all = [];
    constructor(data = {}) {
      this.name = data.name || "group";
      this.origin = (data.origin || [0, 0, 0]).slice();
      this.rotation = (data.rotation || [0, 0, 0]).slice();
      this.children = [];
      this.parent = "root";
    }
    init() { Group.all.push(this); return this; }
    addTo(parent) {
      if (parent && parent !== "root") { this.parent = parent; parent.children.push(this); }
      else this.parent = "root";
      return this;
    }
  }

  class Cube {
    static all = [];
    constructor(data = {}) {
      this.name = data.name || "cube";
      this.from = (data.from || [0, 0, 0]).slice();
      this.to = (data.to || [1, 1, 1]).slice();
      this.origin = this.from.slice();
      this.rotation = [0, 0, 0];
      this.faces = {};
      this.parent = "root";
    }
    init() { Cube.all.push(this); return this; }
    addTo(parent) {
      if (parent && parent !== "root") { this.parent = parent; parent.children.push(this); }
      else this.parent = "root";
      return this;
    }
    applyTexture() {}
  }

  // The fake renderer has to know which view is being framed. The plugin sets
  // the camera from `center + dir * dist`; dir has unit length, so the camera's
  // distance from the ORIGIN identifies the view. That is how this stub maps a
  // capture back to "front"/"left"/... without the plugin cooperating.
  const orig = { front: [0, 0, -1], back: [0, 0, 1], left: [-1, 0, 0], right: [1, 0, 0], top: [0, 1, 0] };
  const center = [0, 0, 0];
  const currentView = () => {
    const p = preview.camera.position;
    const d = Math.abs(center[0]) + Math.abs(center[1]) + Math.abs(center[2]);
    const dist = Math.abs(p.x - center[0]) || Math.abs(p.y - center[1]) || Math.abs(p.z - center[2]);
    if (!(dist > 0)) return "unknown";
    const v = [(p.x - center[0]) / dist, (p.y - center[1]) / dist, (p.z - center[2]) / dist];
    let best = "isometric";
    let bestDot = -Infinity;
    for (const [name, ref] of Object.entries(orig)) {
      const dot = v[0] * ref[0] + v[1] * ref[1] + v[2] * ref[2];
      if (dot > bestDot) { bestDot = dot; best = name; }
    }
    // An isometric view is a mix of front and right rather than an axis.
    if (v[0] > 0.3 && v[2] < -0.3) return "isometric";
    return bestDot > 0.95 ? best : "isometric";
  };

  const controls = {
    target: { set() {} },
    updateSceneScale() {},
  };
  const preview = {
    camera: { position: { x: 0, y: 0, z: 0, set(x, y, z) { this.x = x; this.y = y; this.z = z; } } },
    controls,
    render() {},
  };

  const Screencam = {
    screenshotPreview(_preview, _options, callback) {
      const view = currentView();
      rendered.push(view);
      if (typeof opts.onShot === "function") opts.onShot(view);
      const mode = shots[view] || "png";
      if (mode === "nodata") return callback(undefined);
      if (mode === "empty") return callback("data:image/png;base64,");
      if (mode === "garbage") return callback("data:image/png;base64,bm90YXBuZw==");
      return callback("data:image/png;base64," + (opts.png || PNG_A));
    },
  };

  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    Buffer,
    require: nodeRequire,
    isApp: true,
    Plugin: { register() {} },
    Blockbench: { version: "5.1.4", showQuickMessage() {} },
    Project: opts.project === null
      ? null
      : {
          name: opts.projectName || "preview_test",
          geometry_name: "preview_test",
          texture_width: 64,
          texture_height: 64,
          save_path: opts.savePath === undefined ? "C:/tmp/bbmcp/preview_test.bbmodel" : opts.savePath,
        },
    Format: { id: "free", box_uv: false, animation_mode: false, forward_direction: "-z" },
    Formats: {},
    Mode: { selected: { id: "edit" } },
    Texture: Object.assign([], { all: [], getDefault: () => null }),
    Animation: { all: [] },
    Undo: { initEdit() {}, finishEdit() {} },
    Canvas: { updateAll() {} },
    Outliner: { root: [], elements: [] },
    Preview: { selected: preview },
    Screencam,
    Group,
    Cube,
    ...(opts.extra || {}),
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });

  // applyAngleName runs before the capture; record which view is being framed so
  // the fake renderer can answer per view.
  const originalRender = preview.render;
  preview.render = function () {
    originalRender.call(preview);
  };

  const commands = sandbox.__MCP_COMMANDS__;
  return { commands, Cube, Group, sandbox, rendered, preview };
}

/** The plugin resolves the view name internally; capture it from the shot. */
function seedModel(plugin, count = 2) {
  for (let i = 0; i < count; i++) {
    const cube = new plugin.Cube({
      name: "cube_" + i,
      from: [i, 0, 0],
      to: [i + 1, 1, 1],
    }).init();
    cube.addTo("root");
  }
  return plugin;
}

const listPngs = (dir) =>
  fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith(".png")).sort() : [];

// --- happy path -------------------------------------------------------------

test("render_previews writes all six standard views as verifiable PNGs", async () => {
  const out = tmpDir();
  try {
    // The stub renderer needs to know which view is being rendered. The plugin
    // sets the camera through applyAngleName, which we cannot observe directly,
    // so respond with a valid PNG for every view.
    const plugin = seedModel(loadPlugin({ projectName: "six_views" }));
    const res = await plugin.commands.render_previews({ out_dir: out });

    assert.equal(res.ok, true, res.message);
    assert.equal(res.failed_count, 0);
    assert.equal(res.written_count, 6);
    // Values built inside the vm carry that realm's Array prototype, so compare
    // by value rather than by identity.
    assert.deepEqual(
      [...res.requested_views],
      ["front", "back", "left", "right", "top", "isometric"]
    );

    const dir = path.join(out, "six_views");
    assert.deepEqual(listPngs(dir), [
      "back.png", "front.png", "isometric.png", "left.png", "right.png", "top.png",
    ]);

    for (const w of res.written) {
      assert.equal(w.verified, true);
      assert.ok(w.bytes > 0, `${w.view} reported 0 bytes`);
      const onDisk = fs.readFileSync(w.file);
      assert.equal(onDisk.length, w.bytes);
      assert.deepEqual(
        [...onDisk.subarray(0, 4)],
        [0x89, 0x50, 0x4e, 0x47],
        `${w.view} is not a PNG on disk`
      );
    }
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

test("each written view reports what the camera was actually looking at", async () => {
  const out = tmpDir();
  try {
    const plugin = seedModel(loadPlugin({ projectName: "labelled" }));
    const res = await plugin.commands.render_previews({ out_dir: out, views: ["front", "left", "top"] });
    assert.equal(res.ok, true, res.message);
    assert.equal(res.written.length, 3);
    const byView = Object.fromEntries(res.written.map((w) => [w.view, w]));
    assert.match(String(byView.front.looking_at), /FRONT/i);
    assert.match(String(byView.left.looking_at), /LEFT/i);
    assert.match(String(byView.top.looking_at), /ABOVE/i);
    // The mirror trap must be spelled out for the front view.
    assert.equal(byView.front.model_right_on, "image_left");
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

// --- overwrite behaviour ----------------------------------------------------

test("a second run OVERWRITES the same filenames instead of adding files", async () => {
  const out = tmpDir();
  try {
    const first = seedModel(loadPlugin({ projectName: "overwrite", png: PNG_A }));
    const r1 = await first.commands.render_previews({ out_dir: out });
    assert.equal(r1.ok, true, r1.message);
    const dir = path.join(out, "overwrite");
    const filesAfterFirst = listPngs(dir);
    const bytesA = fs.readFileSync(path.join(dir, "front.png"));
    assert.equal(filesAfterFirst.length, 6);

    // A fresh run with DIFFERENT image bytes: the filename set must not grow and
    // the content must actually change.
    const second = seedModel(loadPlugin({ projectName: "overwrite", png: PNG_B }));
    const r2 = await second.commands.render_previews({ out_dir: out });
    assert.equal(r2.ok, true, r2.message);

    assert.deepEqual(listPngs(dir), filesAfterFirst, "no new files may appear");
    const bytesB = fs.readFileSync(path.join(dir, "front.png"));
    assert.notDeepEqual([...bytesA], [...bytesB], "the file should be replaced, not skipped");
    assert.equal(r2.written_count, 6);
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

// --- error handling ---------------------------------------------------------

test("no open project -> refuses before writing anything", async () => {
  const out = tmpDir();
  try {
    const plugin = loadPlugin({ project: null });
    await assert.rejects(
      () => plugin.commands.render_previews({ out_dir: out }),
      /No project is open/
    );
    assert.deepEqual(fs.readdirSync(out), []);
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

test("an empty model -> refuses instead of saving blank images", async () => {
  const out = tmpDir();
  try {
    const plugin = loadPlugin({ projectName: "empty" });
    await assert.rejects(
      () => plugin.commands.render_previews({ out_dir: out }),
      /no cubes/i
    );
    assert.ok(!fs.existsSync(path.join(out, "empty")) || listPngs(path.join(out, "empty")).length === 0);
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

test("an unknown view is refused BEFORE any render happens", async () => {
  const out = tmpDir();
  try {
    const plugin = seedModel(loadPlugin());
    await assert.rejects(
      () => plugin.commands.render_previews({ out_dir: out, views: ["front", "fron"] }),
      /Unknown view/i
    );
    assert.equal(plugin.rendered.length, 0, "nothing should have been rendered");
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

test("a relative out_dir with an unsaved project is refused, not guessed", async () => {
  const plugin = seedModel(loadPlugin({ savePath: null }));
  await assert.rejects(
    () => plugin.commands.render_previews({ out_dir: "export/previews" }),
    /has not been saved/i
  );
});

test("image data problems are reported per view and never reported as saved", async () => {
  for (const [mode, pattern] of [
    ["nodata", /no image data|no shot/i],
    ["empty", /no image data|empty/i],
    ["garbage", /not a PNG/i],
  ]) {
    const out = tmpDir();
    try {
      const plugin = seedModel(loadPlugin({ projectName: "bad_" + mode, shots: { left: mode } }));
      const res = await plugin.commands.render_previews({ out_dir: out, views: ["front", "left"] });

      assert.equal(res.ok, false, `${mode} should not pass`);
      assert.equal(res.written_count, 1);
      assert.equal(res.failed_count, 1);
      assert.equal(res.failed[0].view, "left");
      assert.equal(res.failed[0].verified, false);
      assert.match(res.failed[0].error, pattern);
      assert.match(res.message, /^NOT saved/);
      // The failing view must not have left a file behind.
      assert.equal(fs.existsSync(path.join(out, "bad_" + mode, "left.png")), false);
      // ...and the view that did work is still on disk.
      assert.equal(fs.existsSync(path.join(out, "bad_" + mode, "front.png")), true);
    } finally {
      fs.rmSync(out, { recursive: true, force: true });
    }
  }
});

test("a preview folder that cannot be created surfaces as an error", async () => {
  // Point the base at an existing FILE: mkdirSync can never create a directory there.
  const out = tmpDir();
  try {
    const blocker = path.join(out, "blocked");
    fs.writeFileSync(blocker, "not a directory");
    const plugin = seedModel(loadPlugin({ projectName: "x" }));
    await assert.rejects(
      () => plugin.commands.render_previews({ out_dir: blocker }),
      /could not create the preview folder/i
    );
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

// --- the model itself is untouched ------------------------------------------

test("previews never modify the model or its geometry", async () => {
  const out = tmpDir();
  try {
    const plugin = seedModel(loadPlugin({ projectName: "readonly" }), 3);
    const before = JSON.stringify(plugin.Cube.all.map((c) => ({ n: c.name, f: c.from, t: c.to })));
    const beforeCount = plugin.Cube.all.length;

    const res = await plugin.commands.render_previews({ out_dir: out });
    assert.equal(res.ok, true, res.message);

    const after = JSON.stringify(plugin.Cube.all.map((c) => ({ n: c.name, f: c.from, t: c.to })));
    assert.equal(plugin.Cube.all.length, beforeCount, "no cube may be added or removed");
    assert.equal(after, before, "no cube may be moved or resized");
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});

test("a preview file is not created inside the project folder by accident", async () => {
  const out = tmpDir();
  const projectDir = path.join(out, "project");
  fs.mkdirSync(projectDir, { recursive: true });
  try {
    const plugin = seedModel(
      loadPlugin({ projectName: "scoped", savePath: path.join(projectDir, "scoped.bbmodel") })
    );
    // Relative out_dir resolves against the PROJECT folder, keeping previews
    // beside the source file rather than wherever the process happens to run.
    const res = await plugin.commands.render_previews({ out_dir: "export/previews" });
    assert.equal(res.ok, true, res.message);
    assert.equal(
      path.resolve(res.dir),
      path.resolve(path.join(projectDir, "export", "previews", "scoped"))
    );
    assert.equal(listPngs(res.dir).length, 6);
  } finally {
    fs.rmSync(out, { recursive: true, force: true });
  }
});
