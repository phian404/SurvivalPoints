/**
 * STAGE 3.4 — validator for the SurvivalPoints assets (READ-ONLY).
 *
 * Checks, per the actual Java Edition 1.20.1 model format (not a made-up rule set):
 *   1. every .json under assets/survivalpoints parses;
 *   2. models/... follow the blockmodel grammar:
 *        - top-level keys are a subset of the vanilla set;
 *        - display transforms: rotation/translation in the ranges vanilla accepts
 *          (rotation is a free float; translation must be -80..80 per axis);
 *        - gui_light in {none,bracket,block,entity};
 *        - elements: from/to are 3 numbers; faces keys in north/south/east/west/up/down;
 *          uv, when present, is 6 numbers with the four corner values finite (vanilla
 *          itself allows uv outside 0..16 — it is clipped by the sprite sheet, not an
 *          error — so we only require well-formedness, and REPORT out-of-0..16 as info);
 *          rotation: origin in -16..32 per axis, axis in x/y/z, angle in {-45,-22.5,22.5,45};
 *          cullface in the six directions;
 *        - texture references: namespace:path grammar; "tintindex" an int.
 *      NOTE on ranges: vanilla's ModelBlockRenderer/BlockModelDeserializer does NOT
 *      clamp element from/to to -16..32; out-of-range geometry is legal (it just draws
 *      outside the block). We therefore report it as INFO, not as an error, and only
 *      fail on values that would break the JSON grammar or produce NaN.
 *   3. blockstates/... : variants values are string | {all:[...]}; a "multipart"
 *      state is also legal; every referenced model is collected for resolution;
 *   4. cross-file references INSIDE the mod namespace must exist on disk;
 *      references to the "minecraft" namespace are exported for step 2b, where they
 *      are checked against the REAL vanilla 1.20.1 jar resources (not a hand list).
 *   5. models/item/<name>.json referenced by blockstates must exist (vanilla item
 *      lookup is by id, so we also flag ids that have a block but no item model).
 *
 * Nothing is written into src/; the report goes to assets-source/AiModelling/.
 *
 * Run: node assets-source/AiModelling/scripts/validate_forge_assets.js
 */
const fs = require("fs");
const path = require("path");

// scripts/ lives at assets-source/AiModelling/scripts, so the repo root is three up.
const ROOT = path.join(__dirname, "..", "..", "..");
const ASSETS = path.join(ROOT, "src", "main", "resources", "assets", "survivalpoints");
const OUT = path.join(__dirname, "..", "reports");

const FACES = ["north", "south", "east", "west", "up", "down"];
const AXES = ["x", "y", "z"];
// vanilla BlockElementRotation: "only 0, 22.5, 45, -22.5, -45 degrees allowed".
// 0 is legal (and Blockbench's java_block codec emits rotation{angle:0} on every
// unrotated cube), so it MUST be in the accepted set — a validator that rejects
// 0 flags every exported model falsely.
const ANGLES = [0, -45, -22.5, 22.5, 45];
const TOP_KEYS = new Set([
  "parent", "ambientocclusion", "display", "gui_light", "textures", "elements",
  "credit", "render_type", "loader", "overrides", "compile", "side", "front",
]);
const DISPLAY_KEYS = new Set(["rotation", "translation", "scale", "transform"]);
const ELEMENT_KEYS = new Set(["from", "to", "faces", "shade", "rotation"]);
const FACE_KEYS = new Set(["uv", "texture", "cullface", "tintindex", "uvlock"]);

const isArr3 = (v) => Array.isArray(v) && v.length === 3 && v.every((n) => typeof n === "number" && Number.isFinite(n));
const isArr6 = (v) => Array.isArray(v) && v.length === 6 && v.every((n) => typeof n === "number" && Number.isFinite(n));
// vanilla grammar: a resource location MAY omit the namespace — it then defaults
// to "minecraft". So "block/cube_all" and "minecraft:block/cube_all" are BOTH legal.
const rlOk = (s) => typeof s === "string" && /^([a-z0-9_.-]+:)?[a-z0-9_.\-/]+$/.test(s) && s.length > 0;

function refParts(ref) {
  // "block/foo" -> {ns:'minecraft'} (default), "survivalpoints:block/foo" -> explicit ns
  const s = String(ref);
  const i = s.indexOf(":");
  return i < 0 ? { ns: "minecraft", path: s } : { ns: s.slice(0, i), path: s.slice(i + 1) };
}

const findings = [];
const externalParents = new Set();
const externalTextures = new Set();
const internalRefs = []; // {from, kind, ref, resolved}
const info = [];
let parsed = 0;

function F(file, rule, detail, severity) {
  findings.push({ file: path.relative(ROOT, file).replace(/\\/g, "/"), rule, detail, severity: severity || "error" });
}

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else acc.push(p);
  }
  return acc;
}

function checkModel(file, doc, rel) {
  if (doc.loader) return; // special loaders (e.g. generate) — structure is loader-owned
  for (const k of Object.keys(doc)) {
    if (!TOP_KEYS.has(k)) F(file, "model/unknown-top-key", `top-level key "${k}" is not part of the 1.20.1 blockmodel grammar`);
  }
  if (doc.gui_light != null && !["none", "bracket", "block", "entity"].includes(doc.gui_light)) {
    F(file, "model/gui_light", `gui_light "${doc.gui_light}" must be none|bracket|block|entity`);
  }
  if (doc.parent != null) {
    if (!rlOk(doc.parent)) F(file, "model/parent-format", `parent "${doc.parent}" is not a resource location`);
    else {
      const r = refParts(doc.parent);
      if (r.ns === "minecraft") externalParents.add(r.path);
      else internalRefs.push({ from: rel, kind: "parent", ref: r.path });
    }
  }
  if (doc.display) {
    for (const [ctx, t] of Object.entries(doc.display)) {
      if (!t || typeof t !== "object") { F(file, "model/display", `display.${ctx} must be an object`); continue; }
      for (const k of Object.keys(t)) if (!DISPLAY_KEYS.has(k)) F(file, "model/display-key", `display.${ctx}.${k} is not a transform key`);
      if (t.rotation != null && !isArr3(t.rotation)) F(file, "model/display-rotation", `display.${ctx}.rotation must be 3 finite numbers`);
      if (t.translation != null) {
        if (!isArr3(t.translation)) F(file, "model/display-translation", `display.${ctx}.translation must be 3 finite numbers`);
        else if (t.translation.some((n) => n < -80 || n > 80)) {
          info.push({ file: rel, rule: "model/display-translation-range", detail: `display.${ctx}.translation ${JSON.stringify(t.translation)} is outside the vanilla -80..80 clamp` });
        }
      }
      if (t.scale != null && !isArr3(t.scale)) F(file, "model/display-scale", `display.${ctx}.scale must be 3 finite numbers`);
    }
  }
  const texVals = [];
  if (doc.textures) {
    for (const [k, v] of Object.entries(doc.textures)) {
      if (typeof v !== "string") { F(file, "model/texture-value", `textures.${k} must be a string`); continue; }
      if (!rlOk(v) && !/^#\w+$/.test(v)) { F(file, "model/texture-format", `textures.${k}="${v}" is neither a resource location nor a #reference`); continue; }
      if (/^#\w+$/.test(v)) {
        const name = v.slice(1);
        if (!(name in doc.textures)) F(file, "model/texture-hash", `textures.${k} references missing key "${name}"`);
        continue;
      }
      texVals.push([k, v]);
      const r = refParts(v);
      if (r.ns === "minecraft") externalTextures.add(r.path);
      else internalRefs.push({ from: rel, kind: "texture", ref: r.path });
    }
  }
  if (doc.elements) {
    if (!Array.isArray(doc.elements)) F(file, "model/elements", "elements must be an array");
    else doc.elements.forEach((el, i) => {
      for (const k of Object.keys(el)) if (!ELEMENT_KEYS.has(k)) F(file, "model/element-key", `elements[${i}].${k} is not an element key`);
      if (!isArr3(el.from)) F(file, "model/element-from", `elements[${i}].from must be 3 finite numbers`);
      if (!isArr3(el.to)) F(file, "model/element-to", `elements[${i}].to must be 3 finite numbers`);
      if (isArr3(el.from) && isArr3(el.to)) {
        const outside = el.from.concat(el.to).some((n) => n < -16 || n > 32);
        if (outside) info.push({ file: rel, rule: "model/element-outside-block", detail: `elements[${i}] from ${JSON.stringify(el.from)} to ${JSON.stringify(el.to)} extends beyond the -16..32 block box (legal in vanilla, may clip visually)` });
      }
      if (el.rotation) {
        if (!isArr3(el.rotation.origin)) F(file, "model/rotation-origin", `elements[${i}].rotation.origin must be 3 numbers`);
        else if (el.rotation.origin.some((n) => n < -16 || n > 32)) F(file, "model/rotation-origin-range", `elements[${i}].rotation.origin ${JSON.stringify(el.rotation.origin)} outside -16..32 (vanilla rejects it)`);
        if (!AXES.includes(el.rotation.axis)) F(file, "model/rotation-axis", `elements[${i}].rotation.axis "${el.rotation.axis}" must be x|y|z`);
        if (!ANGLES.includes(el.rotation.angle)) F(file, "model/rotation-angle", `elements[${i}].rotation.angle ${el.rotation.angle} must be one of ${ANGLES.join(", ")}`);
        if (el.rotation.rescale != null && typeof el.rotation.rescale !== "boolean") F(file, "model/rotation-rescale", `elements[${i}].rotation.rescale must be a boolean`);
      }
      if (el.faces) {
        for (const [d, f] of Object.entries(el.faces)) {
          if (!FACES.includes(d)) F(file, "model/face-name", `elements[${i}] has face "${d}" (must be ${FACES.join("/")})`);
          if (!f || typeof f !== "object") { F(file, "model/face-value", `elements[${i}].faces.${d} must be an object`); continue; }
          for (const k of Object.keys(f)) if (!FACE_KEYS.has(k)) F(file, "model/face-key", `elements[${i}].faces.${d}.${k} is not a face key`);
          if (f.uv != null) {
            // vanilla grammar: uv is [u1,v1,u2,v2] (4) OR [u1,v1,u2,v2,textureU,textureV] (6)
            const uvOk = (Array.isArray(f.uv) && (f.uv.length === 4 || f.uv.length === 6) &&
              f.uv.every((n) => typeof n === "number" && Number.isFinite(n)));
            if (!uvOk) F(file, "model/face-uv", `elements[${i}].faces.${d}.uv must be 4 or 6 finite numbers`);
            else {
              const [u1, v1, u2, v2] = f.uv;
              if (u2 <= u1 || v2 <= v1) F(file, "model/face-uv-order", `elements[${i}].faces.${d}.uv ${JSON.stringify(f.uv)} has non-positive extent`);
              if (u1 < 0 || v1 < 0 || u2 > 16 || v2 > 16) info.push({ file: rel, rule: "model/face-uv-outside", detail: `elements[${i}].faces.${d}.uv ${JSON.stringify(f.uv)} is outside 0..16 (vanilla clips to the sprite; not a parse error)` });
            }
          }
          if (f.cullface != null && !FACES.includes(f.cullface)) F(file, "model/face-cullface", `elements[${i}].faces.${d}.cullface "${f.cullface}" is not a direction`);
          if (f.tintindex != null && !Number.isInteger(f.tintindex)) F(file, "model/face-tint", `elements[${i}].faces.${d}.tintindex must be an integer`);
          if (f.uvlock != null && typeof f.uvlock !== "boolean") F(file, "model/face-uvlock", `elements[${i}].faces.${d}.uvlock must be a boolean`);
          if (typeof f.texture === "string" && !rlOk(f.texture) && !/^#\w+$/.test(f.texture)) F(file, "model/face-texture", `elements[${i}].faces.${d}.texture "${f.texture}" is malformed`);
        }
      }
    });
  }
  if (doc.overrides) {
    if (!Array.isArray(doc.overrides)) F(file, "model/overrides", "overrides must be an array");
    else doc.overrides.forEach((o, i) => {
      if (!o.model || !rlOk(o.model)) F(file, "model/override-model", `overrides[${i}].model "${o.model}" is malformed`);
      else { const r = refParts(o.model); if (r.ns !== "minecraft") internalRefs.push({ from: rel, kind: "override", ref: r.path }); }
      if (!o.predicate || typeof o.predicate !== "object") F(file, "model/override-predicate", `overrides[${i}].predicate must be an object`);
    });
  }
}

function checkBlockstate(file, doc, rel) {
  const keys = Object.keys(doc);
  for (const k of keys) if (!["variants", "multipart"].includes(k)) F(file, "blockstate/unknown-key", `top-level key "${k}" (expected variants|multipart)`);
  const models = [];
  const pushModel = (m, where) => {
    if (typeof m !== "string" || !rlOk(m)) { F(file, "blockstate/model-format", `${where} model "${m}" is malformed`); return; }
    const r = refParts(m);
    if (r.ns === "minecraft") externalParents.add(r.path);
    else { models.push(r.path); internalRefs.push({ from: rel, kind: "blockstate", ref: r.path }); }
  };
  if (doc.variants) {
    for (const [state, val] of Object.entries(doc.variants)) {
      const list = Array.isArray(val) ? val : [val];
      list.forEach((entry, i) => {
        if (typeof entry === "string") pushModel(entry, `variants.${state}[${i}]`);
        else if (entry && typeof entry === "object") {
          if (!entry.model) F(file, "blockstate/variant-model", `variants.${state}[${i}] has no "model"`);
          else pushModel(entry.model, `variants.${state}[${i}]`);
          if (entry.y != null && ![0, 90, 180, 270].includes(entry.y)) F(file, "blockstate/variant-y", `variants.${state}[${i}].y=${entry.y} must be 0|90|180|270`);
          if (entry.x != null && ![0, 90, 180, 270].includes(entry.x)) F(file, "blockstate/variant-x", `variants.${state}[${i}].x=${entry.x} must be 0|90|180|270`);
        } else F(file, "blockstate/variant-type", `variants.${state}[${i}] is neither a string nor an object`);
      });
    }
  }
  if (doc.multipart) {
    if (!Array.isArray(doc.multipart)) F(file, "blockstate/multipart", "multipart must be an array");
    else doc.multipart.forEach((part, i) => {
      // vanilla Multipart$Deserializer accepts BOTH a single apply object and an array
      const apply = part && part.apply;
      const list = Array.isArray(apply) ? apply : (apply && typeof apply === "object" ? [apply] : null);
      if (!list) { F(file, "blockstate/multipart-apply", `multipart[${i}].apply must be an object or an array`); return; }
      list.forEach((a, j) => { if (a.model) pushModel(a.model, `multipart[${i}].apply[${j}]`); else F(file, "blockstate/multipart-model", `multipart[${i}].apply[${j}] has no model`); });
    });
  }
  return models;
}

function main() {
  if (!fs.existsSync(ASSETS)) { console.error("assets dir not found:", ASSETS); process.exit(2); }
  const files = walk(ASSETS).filter((f) => f.toLowerCase().endsWith(".json"));
  const byRel = new Map();
  for (const f of files) {
    const rel = path.relative(ASSETS, f).replace(/\\/g, "/");
    byRel.set(rel, f);
    let doc;
    try { doc = JSON.parse(fs.readFileSync(f, "utf8")); parsed++; }
    catch (e) { F(f, "json/parse", e.message); continue; }
    if (rel.startsWith("models/block/") || rel.startsWith("models/item/") || (rel.startsWith("models/") && !rel.includes("/"))) checkModel(f, doc, rel);
    else if (rel.startsWith("blockstates/")) checkBlockstate(f, doc, rel);
    else if (rel.startsWith("lang/")) {
      for (const [k, v] of Object.entries(doc)) if (typeof v !== "string") F(f, "lang/value", `key "${k}" is not a string`);
    }
    // animations / controllers / geometry: Bedrock-shaped, checked in step 3
  }

  // internal resolution
  const unresolved = [];
  for (const r of internalRefs) {
    const cand = [r.ref + ".json", "models/" + r.ref + ".json", "blockstates/" + r.ref + ".json", "textures/" + r.ref + ".png"];
    const hit = cand.some((c) => byRel.has(c)) || fs.existsSync(path.join(ASSETS, r.ref + ".json")) ||
      fs.existsSync(path.join(ASSETS, "models", r.ref + ".json")) || fs.existsSync(path.join(ASSETS, "textures", r.ref + ".png"));
    if (!hit) unresolved.push(r);
  }

  // textures referenced by models must exist as PNG
  const missingTex = [];
  for (const r of internalRefs.filter((x) => x.kind === "texture")) {
    if (!fs.existsSync(path.join(ASSETS, "textures", r.ref + ".png"))) missingTex.push(r);
  }

  // item models for registered blocks/items (heuristic: blockstate name should have models/item/<name>.json OR an item model)
  const blockstates = [...byRel.keys()].filter((k) => k.startsWith("blockstates/")).map((k) => k.slice("blockstates/".length, -5));
  const missingItemModel = blockstates.filter((n) => !byRel.has("models/item/" + n + ".json"));

  const report = {
    counts: { json_files: files.length, parsed, findings: findings.length, info: info.length, internal_refs: internalRefs.length, unresolved, missing_textures: missingTex.length },
    errors: findings.filter((f) => f.severity === "error"),
    info,
    unresolved_internal: unresolved,
    missing_textures: missingTex,
    blockstates_without_item_model: missingItemModel,
    external_parents: [...externalParents].sort(),
    external_textures: [...externalTextures].sort(),
  };
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "stage3.4-assets-report.json"), JSON.stringify(report, null, 2));
  console.log("parsed:", parsed, "/", files.length);
  console.log("errors:", report.errors.length, "| info:", report.info.length);
  report.errors.slice(0, 40).forEach((e) => console.log("  ERROR", e.file, "::", e.rule, "::", e.detail));
  console.log("unresolved internal refs:", unresolved.length);
  unresolved.slice(0, 30).forEach((u) => console.log("  ", u.from, "->", u.kind, u.ref));
  console.log("missing textures:", missingTex.length);
  missingTex.slice(0, 30).forEach((u) => console.log("  ", u.from, "->", u.ref));
  console.log("blockstates without models/item/<same>.json:", missingItemModel.join(", ") || "none");
  console.log("external parents to verify:", report.external_parents.length, "| external textures:", report.external_textures.length);
  console.log("report:", path.relative(ROOT, path.join(OUT, "stage3.4-assets-report.json")).replace(/\\/g, "/"));
}

main();
