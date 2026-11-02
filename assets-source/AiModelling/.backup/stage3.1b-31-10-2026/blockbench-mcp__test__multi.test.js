/**
 * Tests for compare_reference_multi.
 *
 * Two layers, deliberately separated:
 *
 *  A. PURE units — `aggregateMulti` (the readiness rule) and `normaliseViewLabel`
 *     (the view vocabulary). These hold the logic that must not regress, and they
 *     need no Blockbench at all, so they are tested exhaustively and cheaply.
 *
 *  B. COMMAND behaviour — `compare_reference_multi` driven through a vm sandbox
 *     with stubbed Blockbench globals. Here we assert the contract that does not
 *     depend on exact pixel maths: per-view errors, camera/projection restoration,
 *     which references get paired, and that one failure never aborts the call.
 *     Exact match_percent values are covered by the live E2E run instead
 *     (the stub renderer cannot reproduce real rasterisation faithfully, and
 *     faking it would test the fake).
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
  "globalThis.__MCP__ = { commands, normaliseViewLabel, MULTI_VIEWS, MATCH_THRESHOLDS, aggregateMulti, compareMasks, G };\n" +
    REGISTER
);

/** Build a plugin sandbox. Silhouettes are synthesised from a per-view fill. */
function loadPlugin(opts = {}) {
  const calls = { setProjectionMode: [] };
  const S = 64;
  // Model silhouette per view: a centred square of side `fills[view]`.
  const fills = opts.fills || {};
  // Reference silhouette per loaded reference name.
  const refFills = opts.refFills || {};

  function squareMask(fill) {
    const mask = new Uint8Array(S * S);
    const off = Math.max(0, Math.floor((S - fill) / 2));
    for (let y = off; y < off + fill; y++) {
      for (let x = off; x < off + fill; x++) {
        if (y >= S || x >= S) continue;
        mask[y * S + x] = 1;
      }
    }
    return mask;
  }
  function imageDataFrom(mask, uniform) {
    const data = new Uint8ClampedArray(S * S * 4);
    for (let i = 0; i < S * S; i++) {
      if (uniform) {
        data[i * 4] = 128; data[i * 4 + 1] = 128; data[i * 4 + 2] = 128; data[i * 4 + 3] = 255;
      } else {
        data[i * 4 + 3] = mask[i] ? 255 : 0;
      }
    }
    return { data, width: S, height: S };
  }

  // Which "kind" of getImageData is being asked for: the reference decode goes
  // through new Image() + drawImage, the capture goes straight through.
  let pending = "model";

  class Group {
    static all = [];
    constructor(d = {}) { this.name = d.name || "g"; this.origin = [0, 0, 0]; this.rotation = [0, 0, 0]; this.children = []; this.parent = "root"; }
    init() { Group.all.push(this); return this; }
    addTo(p) { this.parent = p || "root"; return this; }
  }
  class Cube {
    static all = [];
    constructor(d = {}) { this.name = d.name || "c"; this.from = d.from || [0, 0, 0]; this.to = d.to || [1, 1, 1]; this.origin = this.from.slice(); this.rotation = [0, 0, 0]; this.faces = {}; this.parent = "root"; }
    init() { Cube.all.push(this); return this; }
    addTo(p) { this.parent = p || "root"; return this; }
    applyTexture() {}
  }

  const camera = {
    position: {
      v: [10, 10, 10],
      toArray() { return this.v.slice(); },
      set(x, y, z) { this.v = [x, y, z]; },
      get x() { return this.v[0]; }, get y() { return this.v[1]; }, get z() { return this.v[2]; },
    },
  };
  const controls = {
    target: {
      v: [0, 0, 0],
      toArray() { return this.v.slice(); },
      set(x, y, z) { this.v = [x, y, z]; },
    },
    updateSceneScale() {},
  };
  const preview = {
    camera, controls,
    isOrtho: opts.startOrtho === true,
    setProjectionMode(v) { calls.setProjectionMode.push(!!v); this.isOrtho = !!v; },
    render() {},
  };

  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    Buffer,
    isApp: true,
    Plugin: { register() {} },
    Blockbench: { version: "5.1.4", showQuickMessage() {} },
    Project: { name: "multi_test", geometry_name: "multi_test", texture_width: 64, texture_height: 64 },
    Format: { id: "free", box_uv: false, animation_mode: false, forward_direction: "-z" },
    Formats: {},
    Mode: { selected: { id: "edit" } },
    Texture: Object.assign([], { all: [], getDefault: () => null }),
    Animation: { all: [] },
    Undo: { initEdit() {}, finishEdit() {} },
    Canvas: { updateAll() {} },
    Outliner: { root: [], elements: [] },
    Preview: { selected: preview },
    Group, Cube,
    scene: { traverse() {}, background: null },
    THREE: { Color: function () {} },
    Image: function ImageStub() {
      this.naturalWidth = S; this.naturalHeight = S;
      const self = this;
      Object.defineProperty(this, "src", {
        set(value) {
          self._src = value;
          pending = "reference";
          const name = String(value).split(",").pop();
          const spec = refFills[name];
          self._uniform = spec === "junk";
          self._fill = typeof spec === "number" ? spec : 40;
          if (typeof self.onload === "function") setTimeout(() => self.onload(), 0);
        },
        get() { return self._src; },
      });
    },
    document: {
      createElement() {
        return {
          width: S, height: S,
          getContext() {
            return {
              drawImage() {},
              getImageData() {
                if (pending === "reference") {
                  pending = "model";
                  // The stub Image carries the spec; find it via the last loaded src.
                  const name = String(opts.__lastSrc || "").split(",").pop();
                  const spec = refFills[name];
                  const uniform = spec === "junk";
                  const fill = typeof spec === "number" ? spec : 40;
                  return imageDataFrom(squareMask(fill), uniform);
                }
                return imageDataFrom(squareMask(fills[opts.__view] || 40), false);
              },
              putImageData() {}, fillRect() {}, fillText() {},
              createImageData(w, h) { return { data: new Uint8ClampedArray(w * h * 4), width: w, height: h }; },
            };
          },
          toDataURL() { return "data:image/png;base64,COMPOSITE"; },
        };
      },
    },
    // withSilhouetteCapture reads preview.renderer + scene.
    __setView(v) { opts.__view = v; },
    ...(opts.extra || {}),
  };
  sandbox.globalThis = sandbox;

  // renderer stub for withSilhouetteCapture
  Object.defineProperty(preview, "renderer", {
    value: {
      domElement: { width: S, height: S },
      getClearColor() { return null; },
      getClearAlpha() { return 1; },
      setClearColor() {},
    },
  });
  preview.canvas = { width: S, height: S };

  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });

  const api = sandbox.__MCP__;

  // Patch applyAngleName's effect: record the requested view so the stub can
  // answer with the matching model silhouette. The plugin sets camera.position
  // from viewOffsetDir, so infer from the direction.
  const origSet = camera.position.set.bind(camera.position);
  camera.position.set = function (x, y, z) {
    origSet(x, y, z);
    const len = Math.hypot(x, y, z) || 1;
    const d = [x / len, y / len, z / len];
    const table = { front: [0, 0, -1], back: [0, 0, 1], left: [-1, 0, 0], right: [1, 0, 0], top: [0, 1, 0] };
    let best = null, bestDot = -Infinity;
    for (const [name, ref] of Object.entries(table)) {
      const dd = d[0] * ref[0] + d[1] * ref[1] + d[2] * ref[2];
      if (dd > bestDot) { bestDot = dd; best = name; }
    }
    opts.__view = bestDot > 0.9 ? best : "isometric";
  };

  return { api, sandbox, calls, preview, Cube, opts };
}

function seedCube(plugin) {
  const c = new plugin.Cube({ name: "body", from: [-4, 0, -4], to: [4, 8, 4] }).init();
  c.addTo("root");
}

/** Push a reference straight into the plugin store (bypasses file I/O). */
function seedRef(plugin, { name, view }) {
  plugin.api.G.references.push({
    id: "ref_" + (plugin.api.G.references.length + 1),
    name,
    view: view || null,
    data_url: "data:image/png;base64," + name,
    width: 64, height: 64,
    source: "test", added_at: Date.now(), ref_image: null,
  });
  plugin.opts.__lastSrc = "data:image/png;base64," + name;
}

// ===========================================================================
// A. PURE: view vocabulary
// ===========================================================================

test("normaliseViewLabel accepts the five views, normalises case/separators, rejects the rest", () => {
  const { api } = loadPlugin();
  for (const v of ["front", "back", "left", "right", "top"]) assert.equal(api.normaliseViewLabel(v), v);
  assert.equal(api.normaliseViewLabel("FRONT"), "front");
  assert.equal(api.normaliseViewLabel("  back "), "back");
  assert.equal(api.normaliseViewLabel("side"), null);
  assert.equal(api.normaliseViewLabel("isometric"), null);
  assert.equal(api.normaliseViewLabel(""), null);
  assert.equal(api.normaliseViewLabel(null), null);
  assert.equal(api.normaliseViewLabel(42), null);
});

test("MULTI_VIEWS is exactly the five supported angles", () => {
  const { api } = loadPlugin();
  assert.deepEqual([...api.MULTI_VIEWS], ["front", "back", "left", "right", "top"]);
});

test("MATCH_THRESHOLDS are the same bands compare_reference has always used", () => {
  const { api } = loadPlugin();
  assert.deepEqual({ ...api.MATCH_THRESHOLDS }, { excellent: 93, good: 85, close: 70, rough: 50 });
});

// ===========================================================================
// A. PURE: the aggregation rule
// ===========================================================================

const r = (view, pct) => (pct === null ? { view, ok: false, error: "no ref" } : { view, ok: true, match_percent: pct });

test("aggregate: all five views good -> mean/min/max correct, not blocked", () => {
  const { api } = loadPlugin();
  const agg = api.aggregateMulti([r("front", 95), r("back", 92), r("left", 88), r("right", 90), r("top", 85)]);
  assert.equal(agg.compared, 5);
  assert.equal(agg.errors, 0);
  assert.equal(agg.min_match_percent, 85);
  assert.equal(agg.max_match_percent, 95);
  assert.equal(agg.average_match_percent, Math.round((95 + 92 + 88 + 90 + 85) / 5));
  assert.equal(agg.worst_view, "top");
  assert.equal(agg.best_view, "front");
  assert.equal(agg.spread, 10);
  assert.deepEqual([...agg.problem_views], []);
  assert.equal(agg.blocks_further_work, false);
  assert.match(agg.verdict, /GOOD on every compared view/);
});

test("aggregate: ONE bad view is NOT masked by a high average", () => {
  const { api } = loadPlugin();
  // front/back/top are excellent, left is awful: the average stays high.
  const agg = api.aggregateMulti([r("front", 93), r("back", 90), r("top", 88), r("left", 31)]);
  assert.equal(agg.average_match_percent, Math.round((93 + 90 + 88 + 31) / 4)); // 76 — looks fine
  assert.equal(agg.min_match_percent, 31);              // the truth
  assert.equal(agg.worst_view, "left");
  assert.deepEqual([...agg.problem_views], ["left"]);
  assert.equal(agg.blocks_further_work, true, "a sub-CLOSE view must block readiness");
  assert.match(agg.verdict, /NOT READY/);
  assert.match(agg.verdict, /left 31%/);
  assert.match(agg.verdict, /Do NOT read this as done/, "the high views must be named as a trap");
  // The average must never be used to explain readiness.
  assert.match(agg.note, /informational only/i);
});

test("aggregate: average ABOVE the min is exactly the masking case the tool exists for", () => {
  const { api } = loadPlugin();
  const agg = api.aggregateMulti([r("front", 100), r("back", 100), r("left", 0)]);
  assert.ok(agg.average_match_percent > agg.min_match_percent, "precondition: average hides the min");
  assert.equal(agg.blocks_further_work, true);
  assert.equal(agg.worst_view, "left");
});

test("aggregate: verdict band comes from the WORST view, not the average", () => {
  const { api } = loadPlugin();
  const cases = [
    [93, "EXCELLENT"],
    [85, "GOOD"],
    [70, "CLOSE"],
    [50, "NOT READY"],
    [10, "NOT READY"],
  ];
  for (const [pct, expect] of cases) {
    const agg = api.aggregateMulti([r("front", 99), r("back", 99), r("left", pct)]);
    assert.match(agg.verdict, new RegExp(expect), `worst ${pct} should read as ${expect}`);
  }
});

test("aggregate: errors are counted separately and named", () => {
  const { api } = loadPlugin();
  const agg = api.aggregateMulti([r("front", 90), r("left", null), r("top", null)]);
  assert.equal(agg.compared, 1);
  assert.equal(agg.errors, 2);
  assert.deepEqual([...agg.error_views], ["left", "top"]);
  assert.match(agg.verdict, /2 view\(s\) could not be compared: left, top/);
  assert.equal(agg.average_match_percent, 90, "average covers only the compared views");
});

test("aggregate: several failing views do not break the maths", () => {
  const { api } = loadPlugin();
  const agg = api.aggregateMulti([r("front", null), r("back", null), r("left", 80)]);
  assert.equal(agg.compared, 1);
  assert.equal(agg.errors, 2);
  assert.equal(agg.average_match_percent, 80);
  assert.equal(agg.min_match_percent, 80);
  assert.equal(agg.max_match_percent, 80);
  assert.equal(agg.spread, 0);
});

test("aggregate: all views failed -> NO COMPARISON, blocked, null numbers", () => {
  const { api } = loadPlugin();
  const agg = api.aggregateMulti([r("front", null), r("back", null)]);
  assert.equal(agg.compared, 0);
  assert.equal(agg.errors, 2);
  assert.equal(agg.min_match_percent, null);
  assert.equal(agg.max_match_percent, null);
  assert.equal(agg.average_match_percent, null);
  assert.equal(agg.worst_view, null);
  assert.equal(agg.best_view, null);
  assert.equal(agg.blocks_further_work, true);
  assert.match(agg.verdict, /NO COMPARISON/);
});

test("aggregate: thresholds are echoed so a reader can check the bands used", () => {
  const { api } = loadPlugin();
  const agg = api.aggregateMulti([r("front", 90)]);
  assert.deepEqual({ ...agg.thresholds }, { excellent: 93, good: 85, close: 70, rough: 50 });
});

// ===========================================================================
// B. COMMAND: pairing, per-view errors, restoration
// ===========================================================================

test("an unknown view name is refused before anything is rendered", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  await assert.rejects(
    () => plugin.api.commands.compare_reference_multi({ views: ["front", "sideways"] }),
    /Unknown view\(s\): sideways/
  );
});

test("an empty model is refused", async () => {
  const plugin = loadPlugin();
  await assert.rejects(() => plugin.api.commands.compare_reference_multi({ views: ["front"] }), /no cubes/i);
});

test("a view with no reference is an error FOR THAT VIEW; the others still compare", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front", "left"] });
  assert.equal(res.views.length, 2);
  const front = res.views.find((v) => v.view === "front");
  const left = res.views.find((v) => v.view === "left");
  assert.equal(front.ok, true, "front had a reference and must be compared");
  assert.equal(left.ok, false);
  assert.match(left.error, /No reference for view "left"/);
  assert.equal(res.aggregate.compared, 1);
  assert.equal(res.aggregate.errors, 1);
  assert.deepEqual([...res.aggregate.error_views], ["left"]);
});

test("an explicit references map overrides the view label", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "labelled-front", view: "front" });
  seedRef(plugin, { name: "explicit-pick", view: null });
  const res = await plugin.api.commands.compare_reference_multi({
    views: ["front"],
    references: { front: "explicit-pick" },
  });
  assert.equal(res.views[0].ok, true);
  assert.equal(res.views[0].reference, "explicit-pick");
  assert.equal(res.views[0].reference_id, "ref_2");
});

test("a reference selector may be an object {name} or {id}", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "by-name", view: null });
  const byName = await plugin.api.commands.compare_reference_multi({
    views: ["front"], references: { front: { name: "by-name" } },
  });
  assert.equal(byName.views[0].reference, "by-name");

  const byId = await plugin.api.commands.compare_reference_multi({
    views: ["front"], references: { front: { id: "ref_1" } },
  });
  assert.equal(byId.views[0].reference, "by-name");
});

test("a bad reference selector fails only that view", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "good", view: "back" });
  const res = await plugin.api.commands.compare_reference_multi({
    views: ["front", "back"],
    references: { front: "nope" },
  });
  const front = res.views.find((v) => v.view === "front");
  const back = res.views.find((v) => v.view === "back");
  assert.equal(front.ok, false);
  assert.match(front.error, /No reference matches "nope"/);
  assert.equal(back.ok, true, "back must still be compared");
});

test("metric_version and normalization are on the envelope and on every successful view", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  seedRef(plugin, { name: "b", view: "back" });
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front", "back"] });
  assert.equal(res.metric_version, 2);
  assert.equal(res.normalization, "contain");
  for (const v of res.views) {
    if (v.ok) {
      assert.equal(v.metric_version, 2, `${v.view} must carry metric_version`);
      assert.equal(v.normalization, undefined, "normalization is on the envelope");
      assert.ok(typeof v.match_percent === "number");
      assert.ok(typeof v.verdict === "string");
      assert.ok("model_cropped" in v);
      assert.ok("reference_touches_edge" in v);
      assert.ok(Array.isArray(v.warnings));
      assert.ok("aspect_delta_pct" in v);
      assert.ok("ref_only_pct" in v);
      assert.ok("model_only_pct" in v);
      assert.ok("centroid_delta" in v);
    }
  }
});

test("ortho defaults to FALSE and no projection switch happens", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front"] });
  assert.equal(res.ortho_requested, false);
  assert.equal(res.projection, "perspective");
  assert.equal(plugin.calls.setProjectionMode.includes(true), false);
});

test("ortho:true switches projection and RESTORES the original mode", async () => {
  const plugin = loadPlugin({ startOrtho: false });
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front"], ortho: true });
  assert.equal(res.ortho_requested, true);
  assert.equal(plugin.calls.setProjectionMode.includes(true), true, "must switch to ortho");
  assert.equal(plugin.preview.isOrtho, false, "original mode must be restored");
  assert.equal(
    plugin.calls.setProjectionMode[plugin.calls.setProjectionMode.length - 1], false,
    "the LAST projection call must be the restore"
  );
});

test("ortho restore also happens when a later view fails", async () => {
  const plugin = loadPlugin({ startOrtho: false });
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  // "left" has no reference -> throws inside the loop.
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front", "left"], ortho: true });
  assert.equal(res.aggregate.errors, 1);
  assert.equal(plugin.preview.isOrtho, false, "projection must be restored despite the failure");
});

test("camera position and target are restored after the run, even with a failing view", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  const beforePos = plugin.preview.camera.position.toArray();
  const beforeTarget = plugin.preview.controls.target.toArray();
  await plugin.api.commands.compare_reference_multi({ views: ["front", "top"] });
  assert.deepEqual(plugin.preview.camera.position.toArray(), beforePos);
  assert.deepEqual(plugin.preview.controls.target.toArray(), beforeTarget);
});

test("camera is restored even when EVERY view fails", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  const beforePos = plugin.preview.camera.position.toArray();
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front", "back", "left"] });
  assert.equal(res.aggregate.errors, 3);
  assert.deepEqual(plugin.preview.camera.position.toArray(), beforePos);
});

test("an undefined view list defaults to all five", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  for (const v of ["front", "back", "left", "right", "top"]) seedRef(plugin, { name: v, view: v });
  const res = await plugin.api.commands.compare_reference_multi({});
  assert.deepEqual([...res.views.map((v) => v.view)], ["front", "back", "left", "right", "top"]);
});

test("include_composite stays off by default (no base64 bloat)", async () => {
  const plugin = loadPlugin();
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  const res = await plugin.api.commands.compare_reference_multi({ views: ["front"] });
  assert.equal(res.views[0].ok, true);
  assert.equal(res.views[0].composite_data_url, undefined);
});

test("HEAVY_ACTIONS on the plugin and the client both know about the tool", () => {
  assert.match(source, /compare_reference_multi:\s*true/, "plugin HEAVY_ACTIONS");
  const clientSrc = fs.readFileSync(path.join(here, "..", "src", "client.ts"), "utf8");
  assert.match(clientSrc, /"compare_reference_multi"/, "client HEAVY_ACTIONS");
});

// ===========================================================================
// B. COMMAND: compare_reference still behaves the same after the refactor
// ===========================================================================

test("compare_reference still refuses a uniform reference (metric v2 behaviour kept)", async () => {
  const plugin = loadPlugin({ refFills: { junk: "junk" } });
  seedCube(plugin);
  seedRef(plugin, { name: "junk", view: "front" });
  await assert.rejects(
    () => plugin.api.commands.compare_reference({ view: "front" }),
    /Could not extract a silhouette/
  );
});

test("compare_reference output still carries metric_version, normalization and warnings", async () => {
  const plugin = loadPlugin({ refFills: { f: 40 } });
  seedCube(plugin);
  seedRef(plugin, { name: "f", view: "front" });
  const res = await plugin.api.commands.compare_reference({ view: "front" });
  assert.equal(res.metric_version, 2);
  assert.equal(res.normalization, "contain");
  assert.equal(res.projection, "perspective");
  assert.ok(Array.isArray(res.warnings));
  assert.ok("centroid_delta" in res);
  assert.ok(typeof res.match_percent === "number");
});
