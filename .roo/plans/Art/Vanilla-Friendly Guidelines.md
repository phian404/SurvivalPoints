# Vanilla-Friendly Guidelines — SurvivalPoints

> **Status:** Draft v1.0 — prepared for owner review; not yet committed to the repository.  
> **Target:** Minecraft Java Edition 1.20.1 / Forge 47.x / Java 17.  
> **Purpose:** convert the shared Art Vision into practical rules for creating, validating, and delivering models and textures.

## 1. Scope and authority

These guidelines apply to all new SurvivalPoints models, block textures, item textures, NPC skins, and other visual assets.

They are technical production rules. They do not replace the artistic direction in `ART_VISION.md` or a pack-specific specification.

### 1.1 Source-of-truth order

1. Approved pack-specific design/implementation specification.
2. Approved pack-specific visual reference.
3. `ART_VISION.md`.
4. This document.
5. Verified vanilla Minecraft Java Edition 1.20.1 resources and Forge 1.20.1 documentation.

When two approved sources conflict, the agent must stop on the affected point and report the conflict with one or two bounded options. It must not silently redesign the asset.

### 1.2 No implicit implementation permission

Reading these guidelines does not authorise changes to Java code, gameplay, registrations, existing models, blockstates, MCP configuration, or runtime resources. Changes require a separately approved task.

## 2. Platform constraints

- Target only Minecraft Java Edition 1.20.1 and the project's pinned Forge 47.x / Java 17 environment.
- Do not use Minecraft or Forge APIs introduced in 1.21 or later.
- Verify unfamiliar model, texture, rendering, and tint behavior against the 1.20.1 documentation and the project's actual Forge sources.
- Minecraft block/item JSON models support cuboid elements. They do not describe arbitrary triangular meshes.
- A model preview in Blockbench is not proof that the model renders correctly in Forge 1.20.1.

Primary references:

- Forge 1.20.1 model and texture documentation: <https://docs.minecraftforge.net/en/1.20.1/resources/client/models/>
- Forge 1.20.1 render types: <https://docs.minecraftforge.net/en/1.20.1/rendering/modelextensions/rendertypes/>
- Minecraft block model format: <https://minecraft.wiki/w/Block_model>
- Minecraft texture atlas and mipmapping notes: <https://minecraft.wiki/w/Atlas>

## 3. Geometry standards

### 3.1 Coordinate system

- A normal block uses a 16×16×16 model coordinate space.
- Stay within the block boundary unless the approved specification explicitly permits an overhang or an element outside the block.
- Prefer the smallest geometry that preserves the correct silhouette and function.
- Avoid many tiny cubes when the same detail can be represented clearly in a texture.
- Avoid adding decorative geometry that is not present in the approved brief or reference.

### 3.2 Minimum thickness for volumetric details

For a **volumetric cuboid detail** (for example, a wire, rod, support, or small attachment), the smallest non-zero dimension must be at least **1 model unit (1/16 of a block)** by default.

This is a SurvivalPoints production rule intended to avoid sub-pixel geometry that flickers, disappears, or reads inconsistently at gameplay distance. It is not a claim that Minecraft's model format rejects thinner elements.

Exceptions require explicit justification and an in-game test. A deliberate flat cutout panel is not treated as a volumetric cuboid; its plane thickness and rendering approach must be stated in the pack specification.

For an existing legacy model that fails this rule, the validator reports the issue. It must not enlarge or rewrite the model automatically. The owner decides whether the asset is migrated or receives a documented legacy exception.

### 3.3 Geometry versus texture

Choose the method based on the required result:

- Use geometry when the feature changes the silhouette, has meaningful volume, or must read differently from multiple viewing angles.
- Use a thin panel with a cutout texture for flat perforated surfaces where the surface is visually planar and the holes should be transparent.
- Use texture for grain, pixel wear, shallow surface marks, and details that do not need independent volume.
- Do not represent a volumetric wire as a flat texture if its rounded or raised silhouette is an important part of the design.

A flat cutout panel must be checked from both sides and at the pack's intended viewing distances.

## 4. UV mapping and texel density

### 4.1 Default density

The default target is **one texture pixel per one model unit** for ordinary 16×16 block assets. A non-16 texture may be used only when an approved format or pack decision requires it; do not introduce higher density merely to add detail.

Minecraft model UV coordinates are expressed on a 0–16 basis. For an N×N texture, the corresponding source-texel scale is N/16 pixels per UV unit.

### 4.2 Face dimensions must match UV dimensions

For every explicitly UV-mapped face:

1. Determine the two geometric dimensions of that face in the element's local coordinate system.
2. Read the UV rectangle `[u1, v1, u2, v2]` and calculate `du = abs(u2-u1)` and `dv = abs(v2-v1)`.
3. Apply the face-level `rotation` to the UV orientation. For 0°/180°, the effective UV dimensions remain `(du, dv)`. For 90°/270°, the effective orientation is `(dv, du)` relative to the face's local U/V axes.
4. Convert UV units to source pixels using the texture's N/16 scale.
5. Compare the effective UV width and height with the two face dimensions. They must match 1:1 unless the specification explicitly calls for a deliberate stretch, crop, or repeat.

The `rotation` field permutes the selected UV vertices; it does not select a different UV rectangle. The validator must therefore account for the resulting orientation rather than comparing only the unrotated rectangle.

**Example:** a 2×16 face may use a 16×2 UV rectangle when the face UV rotation is 90° or 270°, so that the effective mapped size becomes 2×16. Without the appropriate rotation, the same rectangle is mismatched and can stretch the texture by a factor of eight on one axis.

**Example: 8×16×8 post.** For a side face that is 8 units wide and 16 units high, a 16×16 texture should use the central 8×16 region:

```json
"uv": [4, 0, 12, 16]
```

For an 8×8 top face, a matching 8×8 region may be used, for example:

```json
"uv": [4, 4, 12, 12]
```

The exact region depends on the source texture and approved reference; the invariant is 1:1 texel density after face rotation.

### 4.3 Validation boundaries

- UV values outside the expected 0–16 model UV space require explicit review; behavior outside that range can be inconsistent.
- Mirroring is permitted when intentional; the validator compares absolute spans while preserving the flip in its report.
- Blockstate/model rotation is distinct from the face-level UV `rotation`. The validator must not treat them as the same field.
- If a model uses an unsupported or ambiguous mapping, the validator must report `manual review required`, not claim it has proved 1:1 mapping.

## 5. Static texture rules and atlas safety

### 5.1 Ordinary static textures in mipmapped block atlases

For static PNG sprites assigned to the mipmapped block atlas:

- The image must be square.
- Each side must be a power of two.
- Each side must be at least 16 pixels.
- Do not place 16×2, 32×4, 64×8, or other non-square/smaller-than-16 strips in the block atlas.
- Do not use 32×32 or 64×64 block textures merely to add extra detail; the approved default remains 16 px per block unit.

Minecraft limits the usable mip level when an atlas sprite is too small. A single undersized sprite can therefore affect mipmapping for the atlas and produce a warning in the log. The validator must check the actual atlas assignment rather than relying only on the PNG's folder name.

### 5.2 Animated textures

The only general exception to the square static-sprite rule is an animated texture using a vertical frame strip and a matching `.mcmeta` file. Each animation frame must follow the approved square-frame dimensions and the metadata must describe the intended frame sequence. The animation exception does not permit arbitrary small frames in the block atlas.

### 5.3 Entity skins

Minecraft player-format skins are a separate format and remain **64×64**. They are not subject to the 16×16 block-atlas rule. Their pixel art must still follow the project's shared 16 px/block visual language and the exact skin UV layout.

### 5.4 UV subregions inside a square texture

A narrow visual strip must be stored inside a valid square texture and selected through the model face UVs.

For example, a 16×2 strip can occupy part of a 16×16 PNG, and a face may sample the region `[0, 0, 16, 2]`, with face rotation chosen to match the target face dimensions. This keeps the atlas sprite square and avoids introducing a tiny standalone sprite.

## 6. Alpha and render types

### 6.1 Alpha policy

- For hard cutout surfaces, alpha must be binary: 0 or 255.
- Do not use semi-transparent edge pixels for a standard `cutout` texture.
- Use partial alpha only when the asset genuinely requires translucent rendering and the render type is approved for it.
- Check edge pixels and transparent holes, not just the visual appearance over a dark editor background.

### 6.2 Default render-type policy

| Render type | SurvivalPoints use |
|---|---|
| `minecraft:solid` | Fully opaque surfaces |
| `minecraft:cutout` | Default for hard-edged wires, mesh holes, grates, and similar crisp pixel cutouts |
| `minecraft:cutout_mipped` | Dense foliage/vegetation and other textures that need mipmapped reduction at distance |
| `minecraft:translucent` | Only for actual partial transparency, such as glass-like materials |

For fence meshes and thin cutout details, prefer `cutout` as the starting point. It preserves hard pixel boundaries but does not guarantee that fine geometry will remain stable at every distance. Test aliasing, flicker, and disappearance in-game.

The implementation mechanism (model `render_type`, Forge registration, or another project-established route) must be checked against the local project and the pack specification. Do not migrate the project's render-layer registration mechanism as an incidental part of an art task.

## 7. Vanilla material references and resource reuse

For materials represented in vanilla Minecraft:

- Use vanilla color ranges as the primary visual reference.
- Prefer a direct `minecraft:` resource reference when the exact vanilla texture is appropriate.
- Do not copy vanilla texture files into the SurvivalPoints repository simply to avoid using a resource location.
- Keep local vanilla reference files outside the public mod assets; record the resource location and version instead.
- If biome tinting is required, explicitly implement and test the relevant tint path. A direct vanilla texture reference alone does not guarantee biome tinting.

For custom materials, create material palette files that follow `ART_VISION.md`.

## 8. NPC skin production constraints

### 8.1 Existing runtime contract

The current project uses 14 regular NPC skin variants in `SurvivorSkin.VARIANTS`. Their indices, texture resource paths, gender assignments, and body profile assignments are compatibility-sensitive.

The current published `master` snapshot of `SurvivorSkin.java` contains:

| Index | Skin | Body profile |
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

This yields **8 WIDE and 6 SLIM** variants in that snapshot. The local working tree must be checked before implementation; the public branch may lag behind local changes.

### 8.2 Master template requirements

- Keep the canonical Minecraft skin canvas at 64×64.
- Share head, torso, and leg layers where the design permits.
- Maintain separate WIDE (4-pixel arm) and SLIM (3-pixel arm) variants for every arm/hand/sleeve-related layer that touches arm UV regions.
- Do not create a single arm layer and stretch it between profiles.
- Preserve the correct UV layout, outer-layer transparency, and face orientation.
- Build final skin PNGs from editable source layers and palette manifests; do not require runtime compositing.

### 8.3 Actual NPC model is the acceptance model

The project uses the custom `SurvivorBodyModel`, built from the vanilla player mesh with the project's additional segmented torso geometry. WIDE/SLIM arms are selected through the model profile. The additional chest geometry is gender-dependent and is hidden when chest armor is worn.

Therefore, the Carpenter WIDE and Cook SLIM golden samples must be accepted **in-game on the actual `SurvivorBodyModel`**, not only on a vanilla player preview or a flat skin viewer. The review must include the torso appearance, arm fit, outer layers, and the armor/no-armor condition where applicable.

### 8.4 Index and file-path preservation

- Preserve indices 0–13.
- Preserve the order of `SurvivorSkin.VARIANTS`.
- Preserve existing runtime texture resource paths and file names unless a separate migration is approved.
- Do not rename or reorder a skin because its visual concept changed.
- Keep the four leader skins as a separate content set unless the owner approves a separate integration plan.

## 9. Model and texture validation

Before final delivery, validate:

1. JSON syntax and required model structure.
2. Referenced texture resources and missing paths.
3. Static atlas PNG dimensions: square, power-of-two, minimum 16×16.
4. Alpha channel against the selected render type.
5. Volumetric cuboid minimum thickness (at least 1 unit for new assets unless an approved exception exists).
6. UV-to-face 1:1 dimensions after face-level UV rotation.
7. Geometry bounds and unapproved protrusions.
8. Texture seams and repeat behavior where the pack requires tiling.
9. Visual read from front, side, top, and angled views.
10. In-game rendering at the distances and conditions specified by the asset pack.
11. Runtime log for missing textures/models and mip-level limitation warnings.

Automated checks are a first gate, not a replacement for visual review or game testing.

## 10. Legacy assets and exceptions

Existing assets may predate these rules. The validator must distinguish:

- **New asset:** all mandatory checks apply; failure blocks approval.
- **Legacy asset under review:** report violations without modifying the file.
- **Approved exception:** record the asset, the rule waived, the reason, the owner decision, and any test requirement.

A global rule must not trigger a mass rewrite of existing assets. Migration is performed as a separate, scoped task with an explicit asset list.

## 11. Deliverables and source/runtime separation

- `assets-source/AiModelling/` contains editable sources: `.bbmodel`, PNG layers, palette JSON, manifests, scripts, and generated previews/exports.
- `.roo/plans/Art/` contains the text standards and workflow documents.
- `.roo/skills/` contains compact Roo Code operational skills.
- `src/main/resources/assets/survivalpoints/` contains only approved runtime assets and their game resource files.

A generation or validation command must not silently install, overwrite, rename, or delete runtime assets. Copying outputs into `src/main/resources` requires an explicit integration step and owner approval.

## 12. Asset handoff checklist

A model/texture pack is ready for integration only when:

- the pack specification and visual reference are respected;
- automated validation has no unresolved blocking errors;
- exceptions are documented;
- preview images have been inspected;
- the final asset has been checked in Minecraft Forge 1.20.1;
- resource locations, blockstates, and runtime paths are verified;
- the owner has accepted the result;
- the integration scope is explicit.
