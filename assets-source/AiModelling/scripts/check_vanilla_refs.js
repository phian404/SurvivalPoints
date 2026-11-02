/**
 * STAGE 3.4 step 2b — verify every "minecraft:" reference used by the mod
 * against the REAL Minecraft 1.20.1 resources ForgeGradle downloaded, not a
 * hand list.
 *
 * Source of truth: the vanilla client jar (…/minecraft_repo/versions/1.20.1/
 * client.jar). It carries assets/minecraft/models/** AND assets/minecraft/
 * textures/** (verified: 2504 texture entries). The Mojang asset index in the
 * ForgeGradle cache here turned out PARTIAL (sounds/icons/lang only), so the
 * jar is authoritative for both kinds; the index is consulted only as an
 * extra "also present" signal when it happens to have the key.
 *
 * The jar's central directory is parsed directly (no unzip dependency):
 * EOCD (0x06054b50) -> entry count + CD offset -> per-entry header (0x02014b50)
 * -> file name. READ-ONLY.
 *
 * Run: node assets-source/AiModelling/scripts/check_vanilla_refs.js
 */
const fs = require("fs");
const path = require("path");
const os = require("os");

const ROOT = path.join(__dirname, "..", "..", "..");
const REPORT = path.join(__dirname, "..", "reports", "stage3.4-assets-report.json");
const FG = path.join(os.homedir(), ".gradle", "caches", "forge_gradle");
const A = path.join(FG, "assets");

/** List every entry name in a zip via its central directory. */
function zipEntries(jarPath) {
  const buf = fs.readFileSync(jarPath);
  // EOCD: fixed 22 bytes + variable comment; scan back up to 64 KiB
  let eocd = -1;
  const min = Math.max(0, buf.length - 22 - 0xffff);
  for (let i = buf.length - 22; i >= min; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("no EOCD — not a zip? " + jarPath);
  const count = buf.readUInt16LE(eocd + 10);
  const cdOff = buf.readUInt32LE(eocd + 16);
  const names = [];
  let p = cdOff;
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error("bad central header at " + p);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    names.push(buf.toString("utf8", p + 46, p + 46 + nameLen));
    p += 46 + nameLen + extraLen + commentLen;
  }
  return new Set(names);
}

function findIndex() {
  // ForgeGradle names the file by ASSET INDEX FORMAT (e.g. "5.json"), not by the
  // Minecraft version. This cache belongs to this 1.20.1 project, so the single
  // index here IS the 1.20.1 one. Accept any *.json; prefer the newest.
  const dir = path.join(A, "indexes");
  if (!fs.existsSync(dir)) return null;
  const cands = fs.readdirSync(dir).filter((f) => f.endsWith(".json"));
  if (!cands.length) return null;
  cands.sort((x, y) => fs.statSync(path.join(dir, y)).mtimeMs - fs.statSync(path.join(dir, x)).mtimeMs);
  return path.join(dir, cands[0]);
}

const idxPath = findIndex();
if (!idxPath) { console.error("no 1.20.1 asset index in forge_gradle/assets/indexes"); process.exit(2); }
const idx = JSON.parse(fs.readFileSync(idxPath, "utf8"));
const map = idx.objects || {};
console.log("index:", path.relative(os.homedir(), idxPath), "| objects:", Object.keys(map).length);

// locate the vanilla client jar ForgeGradle downloaded for 1.20.1
const JAR = path.join(FG, "minecraft_repo", "versions", "1.20.1", "client.jar");
if (!fs.existsSync(JAR)) { console.error("no vanilla client jar at", JAR); process.exit(2); }
const jarNames = zipEntries(JAR);
console.log("client jar:", path.basename(JAR), "| entries:", jarNames.size);

const rep = JSON.parse(fs.readFileSync(REPORT, "utf8"));
const checks = [];
for (const p of rep.external_parents) checks.push({ kind: "parent", ref: p, key: "models/" + p + ".json" });
for (const t of rep.external_textures) checks.push({ kind: "texture", ref: t, key: "textures/" + t + ".png" });

let missing = 0;
for (const c of checks) {
  const full = "assets/minecraft/" + c.key;
  if (!jarNames.has(full)) { missing++; console.log("NOT IN VANILLA JAR:", c.kind, full); continue; }
  const inIndex = !!map[c.key];
  console.log("OK  ", c.kind.padEnd(7), "[jar]", full + (inIndex ? "  (+ in asset index)" : ""));
}
console.log(`\n${checks.length} references: ${checks.length - missing} verified against the real 1.20.1 client jar, ${missing} not found`);
fs.writeFileSync(
  path.join(__dirname, "..", "reports", "stage3.4-vanilla-refs.json"),
  JSON.stringify({ jar: path.basename(JAR), index: path.basename(idxPath), checks, missing }, null, 2)
);
process.exit(missing + objMissing ? 1 : 0);
