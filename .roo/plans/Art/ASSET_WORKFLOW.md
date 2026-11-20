# SurvivalPoints — Asset Workflow

> **Status:** Draft v1.0 — prepared for owner review; not yet committed to the repository.  
> **Purpose:** define how the owner, Roo Code, and the asset tools move from a natural-language request to an accepted in-game asset pack.

## 1. Core principle

The owner describes the desired result in ordinary language. The agent studies the project and the approved references, drafts the asset-pack brief, proposes a visual target, and then produces and validates the pack.

The owner should not have to manually specify every UV coordinate, PNG size, model element, file name, or implementation detail. The agent is responsible for translating the requested result into a technical plan and surfacing only the decisions that materially affect the result.

The agent must not silently change approved design decisions or write to runtime resources without explicit approval.

## 2. Document and file layout

### 2.1 Project documentation

```text
.roo/plans/Art/
├── ART_VISION.md
├── Vanilla-Friendly Guidelines.md
└── ASSET_WORKFLOW.md
```

### 2.2 Roo Code skills

```text
.roo/skills/
├── survivalpoints-asset-director/
│   └── SKILL.md
├── survivalpoints-block-assets/
│   └── SKILL.md
├── survivalpoints-npc-skins/
│   └── SKILL.md
└── survivalpoints-asset-validation/
    └── SKILL.md
```

### 2.3 Source assets and tools

```text
assets-source/AiModelling/
├── palettes/
│   ├── wood.json
│   ├── stone.json
│   ├── metal.json
│   ├── leather.json
│   ├── cloth.json
│   └── npc-skin.json
├── References/
│   └── vanilla-reference-notes.md
├── Fences/
│   ├── source/
│   ├── textures/
│   ├── models/
│   └── previews/
├── NPC-Skins/
│   ├── template/
│   ├── layers/shared/
│   ├── layers/arms/WIDE/
│   ├── layers/arms/SLIM/
│   ├── manifests/
│   ├── exports/
│   └── previews/
└── tools/
    ├── validate_assets.py
    ├── preview_textures.py
    └── compose_skins.py
```

`assets-source/AiModelling/` is for working sources and outputs, not a second runtime resource tree. Vanilla Minecraft files are not copied into the public mod assets; record resource locations and keep any permitted local reference copies outside distributable assets.

## 3. Standard production cycle

### Stage A — understand the request

1. Read the local project context relevant to the task.
2. Read `ART_VISION.md` and `Vanilla-Friendly Guidelines.md`.
3. Find the approved specification and visual reference for the asset pack.
4. Inspect existing assets, registrations, resource paths, and callers before proposing replacements.
5. Identify which files are active, unused, experimental, or legacy. Do not infer that a file is active merely because it exists.

### Stage B — prepare the asset-pack brief

The agent creates a compact internal brief containing:

- requested result and gameplay purpose;
- asset inventory and variants;
- approved visual references;
- material palettes and condition/wear level;
- target model/texture formats;
- geometry and UV constraints;
- render type and transparency requirements;
- source and output paths;
- validation checks;
- open questions and assumptions.

Only ask the owner about decisions that cannot safely be inferred from existing approved sources. State assumptions rather than hiding them.

### Stage C — create a golden sample

1. Produce one representative sample or a very small representative pair.
2. Run the automated validator and generate enlarged/tiled previews.
3. Show the result and explain any deviations or limitations.
4. Obtain owner acceptance before producing the full pack.
5. If the defect is common to the whole pack, fix the shared rule, master template, or palette rather than patching every output separately.

### Stage D — produce the pack

1. Reuse approved geometry, layers, palettes, and modules.
2. Generate each asset from a declared manifest or source file.
3. Preserve existing IDs, resource paths, variant indices, and other compatibility-sensitive identifiers.
4. Keep generated previews and intermediate exports separate from runtime files.

### Stage E — validate and review

1. Run JSON/PNG validation.
2. Generate 16× nearest-neighbour and 3×3 tile previews for repeating textures.
3. Generate contact sheets for asset sets such as NPC skins.
4. Review geometry, seams, UV density, alpha, palette consistency, and overall readability.
5. Run the project and inspect the actual Minecraft Forge 1.20.1 result.
6. Check logs for missing resources, model errors, and mipmap limitations.

### Stage F — integrate

Integration is a separate, explicitly approved step. Before copying files into `src/main/resources`:

- verify exact destination paths and all references;
- preserve current runtime names and indices;
- check whether a model or texture is shared by another block/state;
- identify any required Java, datagen, blockstate, loot, language, or recipe changes;
- list all files to be changed;
- obtain owner approval for the integration scope.

Do not silently replace assets used by another system.

## 4. Pilot 1 — Fences v1.1.7-final

### 4.1 Canonical inputs

- `SurvivalPoints_Fences_v1.1.7-final.md`
- `SurvivalPoints_Fences_Visual_Reference_v5.md`

These two files are the authoritative requirements for the Fences pack. The new global rules add production constraints but do not silently revise the approved geometry, behavior, IDs, states, palette, or accepted limitations.

### 4.2 Important distinction: Fences pack versus existing camp perimeter

The modular Fences design is **not automatically the same system** as the currently registered camp perimeter. The public `master` snapshot inspected for this draft contains the following separate camp blocks:

- `camp_fence_post` — `CampFencePostBlock`, derived from `IronBarsBlock`, with a `TOP` state and a 4×16×4 central beam shape in the inspected source;
- `camp_fence` — `CampFenceBlock`, derived from `IronBarsBlock`, with horizontal connection properties;
- `camp_barbed_wire` — `CampBarbedWireBlock`, with its own NORTH/EAST/SOUTH/WEST connection properties and empty collision shape;
- `camp_barbed_wire_bracket` — a separate directional bracket block;
- `camp_wire_web` — a test wire/web variant;
- `camp_gate` — a separate gate system.

The published resource tree also contains camp-specific models and blockstates such as `camp_fence_mesh`, `camp_fence_post_*`, `camp_barbed_wire_*`, `camp_gate_*`, and textures including `camp_fence*.png`, `camp_fence_post.png`, `camp_wire_web.png`, and `block_steel.png`. The public snapshot includes `steel_fence_32.png`; whether it is referenced by an active model must be established from the local resource graph.

The inspected `camp_barbed_wire.json` uses the vanilla chain texture and contains wire elements with a 0.5-unit dimension. This is below the new 1-unit default for volumetric details. It is a legacy candidate for review, **not permission to enlarge it automatically**.

The published `master` branch may lag the user's local working tree. This audit is therefore a discovery baseline only. At pilot start, Roo must inspect the actual local repository, current `AI_CONTEXT.md`, registrations, generator, blockstates, models, textures, datagen sources, and resource references before making a final asset inventory.

Files inspected in the public `master` snapshot for this draft:

- [`AI_CONTEXT.md`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md)
- [`ModBlocks.java`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/init/ModBlocks.java)
- [`CampFencePostBlock.java`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/camp/block/CampFencePostBlock.java)
- [`CampFenceBlock.java`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/camp/block/CampFenceBlock.java)
- [`CampBarbedWireBlock.java`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBlock.java)
- [`CampBarbedWireBracketBlock.java`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBracketBlock.java)
- [`CampWireWebBlock.java`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/java/com/phian404/survivalpoints/camp/block/CampWireWebBlock.java)
- [`camp_barbed_wire.json`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/resources/assets/survivalpoints/models/block/camp_barbed_wire.json)
- [`camp_barbed_wire_bracket.json`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/resources/assets/survivalpoints/models/block/camp_barbed_wire_bracket.json)
- [`camp_fence_mesh.json`](https://github.com/phian404/SurvivalPoints/blob/master/src/main/resources/assets/survivalpoints/models/block/camp_fence_mesh.json)
- [`camp_fence.json` blockstate](https://github.com/phian404/SurvivalPoints/blob/master/src/main/resources/assets/survivalpoints/blockstates/camp_fence.json)

The inspected public repository metadata showed its last push on 2026-11-17. This is not evidence that the local working tree is identical or that the local `assets-source` folder is fully represented on GitHub.

### 4.3 Fences migration matrix

Do not assume same-looking names mean compatible assets.

| Fences v1.1.7 component | Relationship to camp perimeter | Pilot treatment |
|---|---|---|
| `post` | Not automatically equivalent to `camp_fence_post`; approved 8×8 footprint differs from the inspected camp post beam | Build/validate as the Fences pack's own asset; map to camp perimeter only after an explicit design decision |
| `plank_section` | No direct equivalent identified in the inspected camp registration | New asset for the Fences pack |
| `wire_section` | Not equivalent to overhead `camp_barbed_wire`; Fences specifies two simple 2-unit-thick strands, while camp wire has directional connections and a different role | Keep separate; do not substitute one for the other |
| `mesh_section` | `camp_fence` is a possible visual/functional comparison, not an assumed match | Inspect actual in-game model and generator usage before reuse or replacement |
| `sheet_section` | No direct equivalent confirmed in the inspected camp registration | New asset for the Fences pack |
| Bracket / gate / wire-web | Outside the five-component Fences v1.1.7 set | Preserve current resources unless a separate task authorises changes |

Classify every existing file as `KEEP`, `REUSE`, `REPLACE`, `LEGACY`, `EXPERIMENTAL`, or `UNUSED` only after checking actual references and runtime behavior. Do not delete or rename a resource as part of the pilot.

### 4.4 Production sequence

1. **Local audit:** create a resource dependency map for the five Fences IDs and the existing camp-perimeter system.
2. **Post golden sample:** validate the 8×16×8 post, vanilla stripped-log texture references, side-face UV `[4,0,12,16]`, and matching top UV.
3. **Wire golden sample:** implement the approved two-strand geometry. Use a square 16×16 atlas texture with narrow regions sampled by UV. Verify face UV rotation and 1:1 texel density.
4. **Mesh sample:** 16×16 binary-alpha cutout texture on the approved thin panel; verify both sides, horizontal/vertical tiling, and distant readability.
5. **Plank and sheet samples:** 16×16 square textures; preserve two boards, sheet panel, lower metal rail, and all approved geometry and palette constraints.
6. **Pack assembly:** produce the five Fences model JSONs and five custom textures plus the two vanilla post texture references as specified.
7. **Game verification:** test all required states, connections, stacks, and viewing distances from the Fences specification. Review `latest.log` for missing resources and mipmap warnings.
8. **Integration decision:** only after visual acceptance, decide whether and how any Fences assets are used by the camp generator. This is not implied by acceptance of the standalone Fences pack.

The Fences specification's 16/32/64 texture-resolution question remains open until the owner closes it after the pilot. The new standard proposes 16×16 square sprites as the default; the pilot must test the exact cutout patterns in-game before treating the open pack decision as closed.

## 5. Pilot 2 — 14 NPC skins

### 5.1 Compatibility baseline

Use the current local `SurvivorSkin.VARIANTS` as the final authority. The inspected public `master` snapshot has 14 regular entries, with 8 WIDE and 6 SLIM profiles:

| Index | Variant | Profile |
|---:|---|---|
| 0 | Carpenter | WIDE |
| 1 | Cook | SLIM |
| 2 | Farmer 0 | SLIM |
| 3 | Farmer 1 | SLIM |
| 4 | Farmer 2 | WIDE |
| 5 | Guard | WIDE |
| 6 | Hunter | SLIM |
| 7 | Mechanic 0 | WIDE |
| 8 | Mechanic 1 | WIDE |
| 9 | Medic | SLIM |
| 10 | Ranger | WIDE |
| 11 | Scavenger | WIDE |
| 12 | Settler | SLIM |
| 13 | Trader | WIDE |

Preserve all 14 indices, file names, and runtime resource paths. The four leader skins remain a separate pack for later work.

### 5.2 Layered 64×64 master template

Build a single canonical 64×64 source template with full-canvas transparent layers and a manifest describing composition order and palette use.

Suggested layer families:

1. Identity: face, skin tone, hair, and head details.
2. Clothing base: shirt, trousers, footwear, gloves.
3. Profession modules: apron, work jacket, vest, uniform details, and other role identifiers.
4. Equipment: belt, pouch, straps, bag, and carried accessories.
5. Outer layer: jacket/coat overlays, sleeves, hat, collar, and other standard skin outer-layer pixels.
6. Wear and variation: selective patches, fading, dirt, and repairs.

Head, torso, and legs may use shared layer layouts when the design allows. Every layer that paints arm/hand/sleeve UV regions must provide a WIDE and a SLIM source variant. Do not stretch a 4-pixel WIDE sleeve to fit a 3-pixel SLIM arm.

The composer outputs a conventional 64×64 PNG for each NPC. The game does not perform runtime layer composition.

### 5.3 Golden samples

- **Carpenter — WIDE:** first sample for the 4-pixel arm layout.
- **Cook — SLIM:** first sample for the 3-pixel arm layout.

Both must be inspected in-game using the actual `SurvivorBodyModel`. The current model is based on the vanilla player mesh but adds segmented torso geometry; the additional chest segments are visible for the applicable female body state and are hidden while chest armor is worn. Acceptance must therefore inspect the actual torso/skin alignment, arm profile, outer layers, and armor/no-armor appearance.

Do not accept the two samples using only a generic vanilla player preview.

### 5.4 Production and acceptance

1. Audit all 14 existing PNGs and produce a contact sheet before editing.
2. Check dimensions, transparency, file names, and the exact mapping in the local `SurvivorSkin.VARIANTS`.
3. Create the 64×64 master template, shared layers, WIDE/SLIM arm branches, and palette JSONs.
4. Rebuild Carpenter and Cook as golden samples; review both in-game on `SurvivorBodyModel`.
5. Freeze the shared layer and palette rules after approval.
6. Compose the remaining 12 NPC skins from modules and targeted unique details.
7. Produce a 14-skin contact sheet, validate every PNG, and review the complete set together.
8. Compare each final PNG against the existing index/path manifest; reject any unapproved reordering or renaming.
9. Integrate only after owner approval; preserve the current `SurvivorSkin.VARIANTS` order and file paths.

## 6. Roo Code skills and Python tools

The first implementation should remain small and local. No MCP server, external agent framework, Blender pipeline, or new runtime dependency is required for the initial pilots.

### 6.1 Skills

| Skill | Responsibility |
|---|---|
| `survivalpoints-asset-director` | Entry point. Reads project context and all three Art documents, identifies the asset type, prepares the pack brief, and routes work to a specialised skill. |
| `survivalpoints-block-assets` | Block model/texture workflow: model JSON, UV, texture atlas rules, palettes, cutout, Blockbench previews, and Fences constraints. |
| `survivalpoints-npc-skins` | 64×64 layered skin manifests, WIDE/SLIM arm branches, palette usage, `compose_skins.py`, and `SurvivorSkin` compatibility. |
| `survivalpoints-asset-validation` | Runs validators and preview generation, interprets errors/warnings, and produces a review report. |

Skills should be concise operational instructions that point to the canonical Art documents. Do not duplicate the full standards inside every skill.

### 6.2 Python tool 1 — `validate_assets.py`

**Purpose:** catch mechanical model/texture errors before visual review or game launch.

Checks:

- static atlas PNG is square, power-of-two, and at least 16×16;
- PNG assigned to `cutout` / `cutout_mipped` has only alpha values 0 and 255;
- referenced textures resolve where local files are expected;
- model JSON parses and contains valid `elements`, `faces`, and texture references;
- new volumetric cuboids satisfy the 1-unit minimum-thickness rule;
- explicit UV spans match face dimensions 1:1 after accounting for face-level rotation 0/90/180/270 and texture-size scaling;
- geometry does not protrude beyond an approved block boundary unless the manifest permits it.

The tool must support a `new` scope (violations are blocking) and a `legacy`/`audit` scope (violations are reported without changing files). Vanilla external resource locations such as `minecraft:block/stripped_oak_log` are recorded as external references, not reported missing from the SurvivalPoints asset folder.

Ambiguous UV layouts must produce `manual review required`, not a false pass.

### 6.3 Python tool 2 — `preview_textures.py`

**Purpose:** produce quick visual checks without launching Minecraft.

Outputs:

- 16× nearest-neighbour enlargement of each source texture;
- 3×3 tiling preview for textures intended to repeat;
- optional transparency checkerboard behind cutout textures;
- a simple contact sheet for a selected folder.

The tool reads source assets and writes only to the configured preview folder. It must not modify source PNGs.

### 6.4 Python tool 3 — `compose_skins.py`

**Purpose:** deterministically build the 14 final skin PNGs from 64×64 modular source layers and JSON manifests.

Capabilities:

- read a per-NPC manifest with layer order, source layer paths, palette ID, and arm profile;
- composite shared head/torso/leg layers;
- select WIDE or SLIM arm/sleeve variants for all arm-related layers;
- apply palette substitutions only to explicitly masked/declared colors;
- preserve alpha and outer-layer regions;
- export final 64×64 PNGs using the existing file names;
- generate a contact sheet of all 14 results and a machine-readable build report.

The default output is under `assets-source/AiModelling/NPC-Skins/exports/`. Copying results into `src/main/resources` must require a separate explicit install command and owner approval.

### 6.5 Dependencies and safety

Pillow may be used for PNG reading, compositing, nearest-neighbour previews, and contact sheets. Prefer Python standard library for JSON, paths, CLI parsing, and reports. Keep the dependency list minimal and document its version.

All scripts must be deterministic, report changed/output paths, and avoid destructive defaults. A dry-run or output-to-source separation is preferred. No script may edit Java files, block registrations, blockstates, or runtime resources by default.

## 7. Completion criteria for the asset system

The production system is considered validated when:

- Fences passes model/texture validation and the approved in-game visual checks;
- the Carpenter WIDE and Cook SLIM golden samples pass on the actual `SurvivorBodyModel`;
- the remaining 12 NPC skins are composed through the same template and produce a coherent 14-skin contact sheet;
- the 14 indices and runtime texture paths remain unchanged;
- the scripts catch the intended classes of errors without modifying source/runtime assets;
- a subsequent asset category can use the same workflow without copying a fence- or skin-specific rule into the global standard.
