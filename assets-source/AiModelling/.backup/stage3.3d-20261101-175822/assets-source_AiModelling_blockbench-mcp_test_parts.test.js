/**
 * Tests for compare_parts (stage 3.2).
 *
 * Two layers, mirroring multi.test.js:
 *
 *  A. PURE units — the part helpers (subtree walk, isolation with guaranteed
 *     restore, mask rasterisers, and above all computePartReport, the
 *     shape/size/placement/occlusion SPLIT). The split is the contract: a
 *     contain-fit IoU normalises scale and position away, so a pure SHAPE
 *     difference must move only shape, a pure SCALE difference only size, and
 *     a pure SHIFT only placement. These tests pin exactly that.
 *
 *  B. COMMAND behaviour — compare_parts driven through a vm sandbox with a
 *     stub renderer. Asserts the contract that does not depend on pixel maths:
 *     layout validation, part_source gating, per-part error isolation (one bad
 *     part never kills the view; one bad view never kills the call), the
 *     no-groups warning, and camera restoration.
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
  "globalThis.__MCP_PARTS__ = { commands, G, MULTI_VIEWS, normaliseViewLabel, aggregateMulti, " +
    "partSubtree, ancestorChain, withPartIsolated, withPartHidden, maskFromPredicate, " +
    "pointInPolygon, centroidInBox, visibleContribution, shapeIoU, computePartReport, referencePartMask };\n" +
    REGISTER
);

const S = 64; // stub canvas size

/** Build a plugin sandbox with a model made of groups/cubes that have meshes. */
function loadPlugin(opts = {}) {
  const calls = { setProjectionMode: [], renders: 0 };

  function squareMask(fill, off) {
    const mask = new Uint8Array(S * S);
    const o = off != null ? off : Math.max(0, Math.floor((S - fill) / 2));
    for (let y = o; y < o + fill && y < S; y++) {
      for (let x = o; x < o + fill && x < S; x++) mask[y * S + x] = 1;
    }
    return mask;
  }
  function imageDataFrom(mask) {
    const data = new Uint8ClampedArray(S * S * 4);
    for (let i = 0; i < S * S; i++) data[i * 4 + 3] = mask[i] ? 255 : 0;
    return { data, width: S, height: S };
  }

  let pending = "model"; // which getImageData is being asked for

  class Group {
    static all = [];
    constructor(d = {}) {
      this.name = d.name || "g";
      this.origin = [0, 0, 0];
      this.rotation = [0, 0, 0];
      this.children = [];
      this.parent = "root";
      this.mesh = { visible: true, isMesh: true };
    }
    init() { Group.all.push(this); return this; }
    addTo(p) {
      this.parent = p || "root";
      if (p && typeof p === "object") p.children.push(this);
      return this;
    }
  }
  class Cube {
    static all = [];
    constructor(d = {}) {
      this.name = d.name || "c";
      this.from = d.from || [0, 0, 0];
      this.to = d.to || [1, 1, 1];
      this.origin = this.from.slice();
      this.rotation = [0, 0, 0];
      this.faces = {};
      this.parent = "root";
      this.mesh = { visible: true, isMesh: true };
    }
    init() { Cube.all.push(this); return this; }
    addTo(p) {
      this.parent = p || "root";
      if (p && typeof p === "object") p.children.push(this);
      return this;
    }
    applyTexture() {}
  }
  class Mesh {
    static all = [];
    constructor(d = {}) {
      this.name = d.name || "mesh";
      this.origin = [0, 0, 0];
      this.children = [];
      this.parent = "root";
      this.mesh = { visible: true, isMesh: true };
    }
    init() { Mesh.all.push(this); return this; }
    addTo(p) { this.parent = p || "root"; if (p && typeof p === "object") p.children.push(this); return this; }
  }

  const camera = {
    position: {
      v: [10, 10, 10],
      toArray() { return this.v.slice(); },
      set(x, y, z) {
        this.v = [x, y, z];
        // infer the view from the direction so the stub answers per view
        const len = Math.hypot(x, y, z) || 1;
        const d = [x / len, y / len, z / len];
        const table = { front: [0, 0, -1], back: [0, 0, 1], left: [-1, 0, 0], right: [1, 0, 0], top: [0, 1, 0] };
        let best = null, bestDot = -Infinity;
        for (const [name, ref] of Object.entries(table)) {
          const dd = d[0] * ref[0] + d[1] * ref[1] + d[2] * ref[2];
          if (dd > bestDot) { bestDot = dd; best = name; }
        }
        opts.__view = bestDot > 0.9 ? best : "isometric";
      },
      get x() { return this.v[0]; }, get y() { return this.v[1]; }, get z() { return this.v[2]; },
    },
  };
  const controls = {
    target: { v: [0, 0, 0], toArray() { return this.v.slice(); }, set(x, y, z) { this.v = [x, y, z]; } },
    updateSceneScale() {},
  };
  const preview = {
    camera, controls,
    isOrtho: false,
    setProjectionMode(v) { calls.setProjectionMode.push(!!v); this.isOrtho = !!v; },
    render() { calls.renders++; },
  };
  preview.canvas = { width: S, height: S };
  Object.defineProperty(preview, "renderer", {
    value: {
      domElement: { width: S, height: S },
      getClearColor() { return null; },
      getClearAlpha() { return 1; },
      setClearColor() {},
    },
  });

  const sandbox = {
    console: { ...console, warn() {}, error() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    Buffer,
    isApp: true,
    Plugin: { register() {} },
    Blockbench: { version: "5.2.2", showQuickMessage() {} },
    Project: { name: "parts_test", geometry_name: "parts_test", texture_width: 64, texture_height: 64 },
    Format: { id: "free", box_uv: false, animation_mode: false, forward_direction: "-z" },
    Formats: {},
    Mode: { selected: { id: "edit" } },
    Texture: Object.assign([], { all: [], getDefault: () => null }),
    Animation: { all: [] },
    Undo: { initEdit() {}, finishEdit() {} },
    Canvas: { updateAll() {} },
    Outliner: { root: [], elements: [] },
    Preview: { selected: preview },
    Group, Cube, Mesh,
    scene: { traverse() {}, background: null },
    THREE: { Color: function () {} },
    Image: function ImageStub() {
      this.naturalWidth = S; this.naturalHeight = S;
      const self = this;
      Object.defineProperty(this, "src", {
        set(value) {
          self._src = value;
          pending = "reference";
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
                  return imageDataFrom(squareMask(opts.refFill != null ? opts.refFill : 40));
                }
                return imageDataFrom(squareMask(opts.fills ? (opts.fills[opts.__view] != null ? opts.fills[opts.__view] : 40) : 40));
              },
              putImageData() {}, fillRect() {}, fillText() {},
              createImageData(w, h) { return { data: new Uint8ClampedArray(w * h * 4), width: w, height: h }; },
            };
          },
          toDataURL() { return "data:image/png;base64,STUB"; },
        };
      },
    },
    ...(opts.extra || {}),
  };
  sandbox.globalThis = sandbox;

  vm.createContext(sandbox);
  vm.runInContext(instrumented, sandbox, { filename: "blockbench_mcp.js" });

  return { api: sandbox.__MCP_PARTS__, sandbox, calls, preview, Group, Cube, Mesh, opts };
}

/** Seed the standard 4-group model: each group owns one cube. */
function seedModel(pl) {
  const mk = (gname, cname) => {
    const g = new pl.Group({ name: gname }).init();
    g.addTo("root");
    const c = new pl.Cube({ name: cname }).init();
    c.addTo(g);
    return { g, c };
  };
  return {
    head: mk("head_group", "head"),
    body: mk("body_group", "torso"),
    left: mk("left_arm_group", "l_arm"),
    right: mk("right_arm_group", "r_arm"),
  };
}

function seedRef(pl, { name, view }) {
  pl.api.G.references.push({
    id: "ref_" + (pl.api.G.references.length + 1),
    name,
    view: view || null,
    data_url: "data:image/png;base64," + name,
    width: S, height: S,
    source: "test", added_at: Date.now(), ref_image: null,
  });
}

const mkMask = (w, h, paint) => {
  const mask = new Uint8Array(w * h);
  paint(mask, w, h);
  let area = 0, minX = w, minY = h, maxX = -1, maxY = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (mask[y * w + x]) { area++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; }
  }
  return { mask, width: w, height: h, area, bbox: maxX < 0 ? { x: 0, y: 0, w: 0, h: 0 } : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 } };
};
const rect = (x0, y0, x1, y1) => (m, w) => {
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) if (y * w + x < m.length) m[y * w + x] = 1;
};

// ===========================================================================
// A. PURE: subtree + isolation
// ===========================================================================

test("partSubtree collects nested groups AND their cubes via children", () => {
  const pl = loadPlugin();
  const body = new pl.Group({ name: "body_group" }).init(); body.addTo("root");
  const torso = new pl.Cube({ name: "torso" }).init(); torso.addTo(body);
  const sub = new pl.Group({ name: "sub_group" }).init(); sub.addTo(body);
  const leg = new pl.Cube({ name: "leg" }).init(); leg.addTo(sub);
  const head = new pl.Group({ name: "head_group" }).init(); head.addTo("root");
  new pl.Cube({ name: "head" }).init().addTo(head);

  const set = pl.api.partSubtree(body);
  assert.ok(set.has(body) && set.has(torso) && set.has(sub) && set.has(leg), "the whole subtree is in");
  assert.ok(!set.has(head), "a sibling group is NOT in the subtree");
});

test("ancestorChain walks object parents up to the root string", () => {
  const pl = loadPlugin();
  const top = new pl.Group({ name: "top" }).init(); top.addTo("root");
  const mid = new pl.Group({ name: "mid" }).init(); mid.addTo(top);
  const cube = new pl.Cube({ name: "c" }).init(); cube.addTo(mid);

  const chain = pl.api.ancestorChain(mid);
  assert.ok(chain.has(top) && !chain.has(mid), "ancestors only, not self");
  assert.equal(chain.has(cube), false);
  assert.equal(pl.api.ancestorChain(top).size, 0, "a root child has no object ancestors");
});

test("withPartIsolated hides everything outside the subtree and RESTORES it", () => {
  const pl = loadPlugin();
  seedModel(pl);
  const head = pl.sandbox.Group.all.find((g) => g.name === "head_group");
  const headCube = pl.sandbox.Cube.all.find((c) => c.name === "head");
  const otherCube = pl.sandbox.Cube.all.find((c) => c.name === "torso");

  const seen = pl.api.withPartIsolated(pl.preview, head, () => ({
    headVis: head.mesh.visible,
    headCubeVis: headCube.mesh.visible,
    otherVis: otherCube.mesh.visible,
  }));
  assert.equal(seen.headVis, true, "the group node stays visible (THREE renders children)");
  assert.equal(seen.headCubeVis, true, "its cube is visible");
  assert.equal(seen.otherVis, false, "everything else is hidden");
  assert.ok(
    pl.sandbox.Group.all.every((g) => g.mesh.visible === true) &&
    pl.sandbox.Cube.all.every((c) => c.mesh.visible === true),
    "after the call EVERY node is back to visible"
  );
});

test("withPartIsolated restores even when fn throws (try/finally contract)", () => {
  const pl = loadPlugin();
  seedModel(pl);
  const head = pl.sandbox.Group.all.find((g) => g.name === "head_group");
  assert.throws(
    () => pl.api.withPartIsolated(pl.preview, head, () => { throw new Error("boom"); }),
    /boom/
  );
  assert.ok(
    pl.sandbox.Group.all.every((g) => g.mesh.visible === true) &&
    pl.sandbox.Cube.all.every((c) => c.mesh.visible === true),
    "a throw inside the capture must not leave parts of the model hidden"
  );
});

test("withPartHidden is the exact mirror: only the subtree goes dark", () => {
  const pl = loadPlugin();
  seedModel(pl);
  const body = pl.sandbox.Group.all.find((g) => g.name === "body_group");
  const torso = pl.sandbox.Cube.all.find((c) => c.name === "torso");
  const headCube = pl.sandbox.Cube.all.find((c) => c.name === "head");

  const seen = pl.api.withPartHidden(pl.preview, body, () => ({
    torsoVis: torso.mesh.visible,
    headVis: headCube.mesh.visible,
  }));
  assert.equal(seen.torsoVis, false, "the part is hidden");
  assert.equal(seen.headVis, true, "the rest of the model stays visible");
  assert.ok(pl.sandbox.Cube.all.every((c) => c.mesh.visible === true), "restored");
});

// ===========================================================================
// A. PURE: mask rasterisers
// ===========================================================================

test("maskFromPredicate builds mask + bbox + area from an (x,y) predicate", () => {
  const pl = loadPlugin();
  const m = pl.api.maskFromPredicate(32, 32, (x, y) => x >= 4 && x < 12 && y >= 6 && y < 10);
  assert.equal(m.area, 8 * 4);
  assert.deepEqual({ x: m.bbox.x, y: m.bbox.y, w: m.bbox.w, h: m.bbox.h }, { x: 4, y: 6, w: 8, h: 4 });
});

test("pointInPolygon: convex, concave and outside cases", () => {
  const pl = loadPlugin();
  const square = [[0, 0], [10, 0], [10, 10], [0, 10]];
  assert.equal(pl.api.pointInPolygon(5, 5, square), true);
  assert.equal(pl.api.pointInPolygon(15, 5, square), false);
  // U-shape: the notch in the top middle is OUTSIDE
  const u = [[0, 0], [30, 0], [30, 30], [20, 30], [20, 10], [10, 10], [10, 30], [0, 30]];
  assert.equal(pl.api.pointInPolygon(15, 20, u), false, "inside the notch");
  assert.equal(pl.api.pointInPolygon(5, 20, u), true, "inside the left wall");
});

test("centroidInBox normalises the part centroid into the whole-model box", () => {
  const pl = loadPlugin();
  const part = mkMask(64, 64, rect(8, 8, 15, 15)); // 8x8 block, centre (11.5,11.5)
  const full = { bbox: { x: 0, y: 0, w: 32, h: 32 } };
  const c = pl.api.centroidInBox(part, full.bbox);
  assert.ok(Math.abs(c.x - 11.5 / 32) < 1e-9 && Math.abs(c.y - 11.5 / 32) < 1e-9);
  assert.equal(pl.api.centroidInBox(mkMask(8, 8, () => {}), full.bbox), null, "empty mask -> null");
});

test("visibleContribution counts pixels that vanish when the part is removed", () => {
  const pl = loadPlugin();
  const full = mkMask(16, 16, rect(0, 0, 15, 15)); // everything lit
  const rest = mkMask(16, 16, rect(0, 0, 15, 15));
  // punch a 4x4 hole in `rest` -> those 16 px are the part's visible contribution
  for (let y = 2; y < 6; y++) for (let x = 2; x < 6; x++) rest.mask[y * 16 + x] = 0;
  assert.equal(pl.api.visibleContribution(full, rest), 16);
  assert.equal(pl.api.visibleContribution(full, full), 0, "nothing vanishes");
});

test("shapeIoU is 1 for identical shapes and 0 for disjoint ones", () => {
  const pl = loadPlugin();
  const a = mkMask(64, 64, rect(10, 10, 30, 40));
  assert.equal(Math.round(pl.api.shapeIoU(a, a) * 100), 100);
  const b = mkMask(64, 64, rect(0, 0, 5, 5));
  const c = mkMask(64, 64, rect(58, 58, 63, 63));
  // both tiny but in opposite corners: after contain-fit they OVERLAP (each is
  // scaled to the box) — IoU measures SHAPE, not position. Same square -> 100.
  assert.equal(Math.round(pl.api.shapeIoU(b, c) * 100), 100, "contain-fit erases position — by design");
  const wide = mkMask(64, 64, rect(4, 30, 60, 34)); // 57x5 bar
  const tall = mkMask(64, 64, rect(30, 4, 34, 60)); // 5x57 bar
  assert.ok(pl.api.shapeIoU(wide, tall) < 0.25, "different shapes score low");
});

// ===========================================================================
// A. PURE: the metric SPLIT (the contract of the whole stage)
// ===========================================================================

test("computePartReport: a pure POSITION shift moves placement, not shape or size", () => {
  const pl = loadPlugin();
  const iso = mkMask(64, 64, rect(4, 4, 19, 19));            // 16x16 top-left
  const refPart = mkMask(64, 64, rect(40, 40, 55, 55));      // same size, bottom-right
  const full = mkMask(64, 64, rect(0, 0, 63, 63));
  const rep = pl.api.computePartReport({ iso, full, rest: full, refPart, refFull: full });
  assert.equal(rep.shape.match_percent, 100, "identical shape -> 100 even when displaced");
  assert.equal(rep.size.size_ratio, 1, "identical area share");
  assert.ok(rep.placement.delta && Math.abs(rep.placement.delta.dx) > 0.4, "placement MUST see the shift");
  assert.ok(Math.abs(rep.placement.delta.dy) > 0.4);
});

test("computePartReport: a pure SCALE change moves size, not shape", () => {
  const pl = loadPlugin();
  const iso = mkMask(64, 64, rect(24, 24, 39, 39));           // 16x16, centred
  const refPart = mkMask(64, 64, rect(16, 16, 47, 47));       // 32x32, SAME centre
  const full = mkMask(64, 64, rect(0, 0, 63, 63));
  const rep = pl.api.computePartReport({ iso, full, rest: full, refPart, refFull: full });
  assert.equal(rep.shape.match_percent, 100, "contain-fit: same square shape");
  assert.equal(rep.size.size_ratio, 0.25, "16x16 vs 32x32 share -> quarter");
  assert.ok(rep.placement.delta && Math.abs(rep.placement.delta.dx) < 0.05, "centres coincide -> placement clean");
});

test("computePartReport: a pure SHAPE change moves shape, not size", () => {
  const pl = loadPlugin();
  const iso = mkMask(64, 64, rect(8, 20, 55, 24));            // wide bar
  const refPart = mkMask(64, 64, rect(20, 8, 24, 55));        // tall bar
  const full = mkMask(64, 64, rect(0, 0, 63, 63));
  const rep = pl.api.computePartReport({ iso, full, rest: full, refPart, refFull: full });
  assert.ok(rep.shape.match_percent < 30, "cross shapes must score low");
  assert.equal(rep.size.size_ratio, 1, "same area share — size must NOT react to a shape change");
});

// --- referencePartMask: the layout region -> mask, with pixel scaling ---------

test("referencePartMask bbox honours the natural->decoded pixel scale", async () => {
  const pl = loadPlugin();
  const refData = { width: 64, height: 64 };
  // a 10x20 region in NATURAL pixels, doubled by the scale -> 20x40 decoded
  const m = await pl.api.referencePartMask(refData, { bbox: [4, 4, 10, 20] }, 2);
  assert.equal(m.method, "bbox");
  assert.equal(m.confidence, "low");
  assert.deepEqual({ x: m.bbox.x, y: m.bbox.y, w: m.bbox.w, h: m.bbox.h }, { x: 8, y: 8, w: 20, h: 40 });
  assert.equal(m.area, 20 * 40);
});

test("referencePartMask polygon rasterises the scaled outline", async () => {
  const pl = loadPlugin();
  const refData = { width: 64, height: 64 };
  const m = await pl.api.referencePartMask(
    refData,
    { polygon: [[0, 0], [10, 0], [10, 10], [0, 10]] },
    2
  );
  assert.equal(m.method, "polygon");
  assert.equal(m.confidence, "medium");
  // 20x20 square at the origin
  assert.deepEqual({ x: m.bbox.x, y: m.bbox.y, w: m.bbox.w, h: m.bbox.h }, { x: 0, y: 0, w: 20, h: 20 });
  assert.equal(m.area, 400);
});

test("referencePartMask returns null for an unknown spec (caller turns it into an error)", async () => {
  const pl = loadPlugin();
  assert.equal(await pl.api.referencePartMask({ width: 8, height: 8 }, {}, 1), null);
  assert.equal(await pl.api.referencePartMask({ width: 8, height: 8 }, { group: "x" }, 1), null);
});

test("the mask_png decode matches the reference's own resolution (regression: 3.2 scale bug)", () => {
  // The bug: mask_png was decoded at refData.width while the reference itself
  // was decoded at 512, so for a non-square reference the two masks lived in
  // different pixel spaces and size/placement silently drifted. The fix decodes
  // the mask at max(refData.width, refData.height). Pinned at source level
  // because the vm's Image stub cannot model a real resize.
  assert.match(source, /imageDataFromSource\(url, Math\.max\(refData\.width, refData\.height\)\)/);
});

test("computePartReport: occlusion is isolated minus visible-in-composite", () => {
  const pl = loadPlugin();
  const iso = mkMask(32, 32, rect(0, 0, 31, 31));             // 1024 px alone
  const full = mkMask(32, 32, rect(0, 0, 31, 31));            // whole model
  // rest = full with a 16x16 hole punched out: those 256 px are the part's
  // visible contribution — everything else of the part hides behind neighbours.
  const rest = mkMask(32, 32, rect(0, 0, 31, 31));
  for (let y = 8; y < 24; y++) for (let x = 8; x < 24; x++) rest.mask[y * 32 + x] = 0;
  const rep = pl.api.computePartReport({ iso, full, rest, refPart: iso, refFull: full });
  assert.equal(rep.occlusion.isolated_area, 1024);
  assert.equal(rep.occlusion.visible_in_composite, 256, "only the hole vanishes when the part is removed");
  assert.equal(rep.occlusion.occluded_pct, 75, "the rest hides behind neighbours");
});

test("computePartReport: empty sides degrade to nulls, never fake scores", () => {
  const pl = loadPlugin();
  const rep = pl.api.computePartReport({ iso: mkMask(8, 8, () => {}), full: mkMask(8, 8, rect(0, 0, 7, 7)) });
  assert.equal(rep.shape.match_percent, null);
  assert.equal(rep.size.size_ratio, null);
  assert.equal(rep.placement.model_centroid, null);
});

// ===========================================================================
// B. COMMAND: compare_parts contract through the sandbox
// ===========================================================================

const layout = (groups) => ({
  views: {
    front: groups.map((g) => ({ group: g, bbox: [8, 8, 48, 48] })),
  },
});

test("compare_parts refuses a missing layout with an actionable message", async () => {
  const pl = loadPlugin();
  seedModel(pl); seedRef(pl, { name: "r" });
  await assert.rejects(() => pl.api.commands.compare_parts({}), /parts layout/i);
});

test("compare_parts refuses part_source=cubes instead of silently falling back", async () => {
  const pl = loadPlugin();
  seedModel(pl); seedRef(pl, { name: "r" });
  await assert.rejects(
    () => pl.api.commands.compare_parts({ parts: layout(["head_group"]), part_source: "cubes" }),
    /only "groups" is implemented/i
  );
});

test("compare_parts: happy path returns the four metric blocks per part", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const res = await pl.api.commands.compare_parts({ parts: layout(["head_group", "body_group"]) });
  assert.equal(res.metric_version, 3);
  assert.equal(res.part_source, "groups");
  assert.equal(res.views.length, 1);
  assert.equal(res.views[0].ok, true);
  assert.equal(res.views[0].parts.length, 2);
  for (const pt of res.views[0].parts) {
    assert.equal(pt.ok, true, pt.error);
    assert.ok(pt.shape && typeof pt.shape.match_percent === "number", "shape block");
    assert.ok(pt.size && "size_ratio" in pt.size, "size block");
    assert.ok(pt.placement && "delta" in pt.placement, "placement block");
    assert.ok(pt.occlusion && "occluded_pct" in pt.occlusion, "occlusion block");
    assert.match(pt.shape.note, /SHAPE only/i, "the shape block must disclaim size/position");
  }
  assert.ok(res.aggregate && Array.isArray(res.aggregate.block_reasons));
  assert.match(res.note, /never inferred from the IoU/i);
});

test("compare_parts: an unknown group is an error for THAT part only", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const res = await pl.api.commands.compare_parts({ parts: layout(["head_group", "ghost_group"]) });
  assert.equal(res.views[0].ok, true, "the VIEW still succeeds");
  const [good, bad] = res.views[0].parts;
  assert.equal(good.ok, true);
  assert.equal(bad.ok, false);
  assert.match(bad.error, /no model group named "ghost_group"/);
  assert.equal(res.aggregate.errors, 1);
  assert.equal(res.aggregate.compared, 1);
});

test("compare_parts: a bad view kills only that view; the good one is returned", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const res = await pl.api.commands.compare_parts({
    parts: { views: { front: [{ group: "head_group", bbox: [8, 8, 48, 48] }], back: [{ group: "head_group", bbox: [8, 8, 48, 48] }] } },
  });
  const front = res.views.find((v) => v.view === "front");
  const back = res.views.find((v) => v.view === "back");
  assert.equal(front.ok, true);
  assert.equal(back.ok, false, "no reference labelled 'back' -> that view errors");
  assert.match(back.error, /No reference for view "back"/);
  assert.equal(res.aggregate.compared >= 1, true, "the good view still counts");
});

test("compare_parts: a model with no groups warns with instructions, not a crash", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  const c = new pl.Cube({ name: "loner" }).init(); c.addTo("root");
  seedRef(pl, { name: "r", view: "front" });
  const res = await pl.api.commands.compare_parts({ parts: layout(["head_group"]) });
  assert.ok(res.warnings.some((w) => /NO groups/i.test(w)), "explicit warning");
  assert.ok(res.warnings.some((w) => /add_group/i.test(w)), "the warning says what to do");
  assert.equal(res.views[0].parts[0].ok, false);
});

test("compare_parts: an empty isolated part is an error, not a 0% match", async () => {
  const pl = loadPlugin({ fills: { front: 0 }, refFill: 40 }); // every model capture is empty
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const res = await pl.api.commands.compare_parts({ parts: layout(["head_group"]) });
  assert.equal(res.views[0].ok, false);
  assert.match(res.views[0].error, /silhouette was empty/i, "the view-level guard fires first");
});

test("compare_parts: a bbox entirely outside the reference is an error for that part", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const res = await pl.api.commands.compare_parts({
    parts: { views: { front: [{ group: "head_group", bbox: [500, 500, 20, 20] }] } },
  });
  assert.equal(res.views[0].ok, true);
  assert.equal(res.views[0].parts[0].ok, false);
  assert.match(res.views[0].parts[0].error, /region .* is empty|outside/i);
});

test("compare_parts: max_parts refuses an oversized view before rendering", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const many = ["head_group", "body_group", "left_arm_group", "right_arm_group"];
  const res = await pl.api.commands.compare_parts({ parts: layout(many), max_parts: 2 });
  assert.equal(res.views[0].ok, false);
  assert.match(res.views[0].error, /over max_parts=2/);
});

test("compare_parts: camera position and target are restored after the run", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  const pos0 = pl.preview.camera.position.toArray();
  const tgt0 = pl.preview.controls.target.toArray();
  await pl.api.commands.compare_parts({ parts: layout(["head_group", "body_group"]) });
  assert.deepEqual(pl.preview.camera.position.toArray(), pos0, "camera back where it was");
  assert.deepEqual(pl.preview.controls.target.toArray(), tgt0, "target back where it was");
});

test("compare_parts: ortho defaults off, and ortho:true is restored afterwards", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  // applyAngleName pins perspective while aiming (setProjectionMode(false)) —
  // that is expected. The contract is about ORTHO: never turned on by default,
  // and turned back off after an ortho:true run.
  await pl.api.commands.compare_parts({ parts: layout(["head_group"]) });
  assert.equal(pl.calls.setProjectionMode.includes(true), false, "ortho never switched on by default");
  pl.calls.setProjectionMode.length = 0;
  await pl.api.commands.compare_parts({ parts: layout(["head_group"]), ortho: true });
  assert.equal(pl.calls.setProjectionMode[0], true, "ortho:true switches ON first");
  assert.equal(pl.calls.setProjectionMode[pl.calls.setProjectionMode.length - 1], false, "restored to OFF last");
});

test("compare_parts: every touched mesh visibility is back after a full run", async () => {
  const pl = loadPlugin({ fills: { front: 40 }, refFill: 40 });
  seedModel(pl); seedRef(pl, { name: "r", view: "front" });
  await pl.api.commands.compare_parts({ parts: layout(["head_group", "body_group", "left_arm_group"]) });
  assert.ok(pl.sandbox.Cube.all.every((c) => c.mesh.visible === true));
  assert.ok(pl.sandbox.Group.all.every((g) => g.mesh.visible === true));
});

test("compare_parts: HEAVY_ACTIONS on the plugin and the client both know the tool", () => {
  const pluginSrc = fs.readFileSync(PLUGIN, "utf8");
  assert.match(pluginSrc, /compare_parts: true/, "plugin HEAVY_ACTIONS entry");
  const clientSrc = fs.readFileSync(path.join(here, "..", "src", "client.ts"), "utf8");
  assert.match(clientSrc, /"compare_parts"/, "client HEAVY_ACTIONS entry");
  const toolsSrc = fs.readFileSync(path.join(here, "..", "src", "tools.ts"), "utf8");
  assert.match(toolsSrc, /name: "compare_parts"/, "the MCP tool is registered");
});
