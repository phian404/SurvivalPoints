# SurvivalPoints — Art Vision

> **Status:** Current approved art-direction baseline. Asset-specific specifications may add constraints but must not silently contradict this shared direction.
> **Target:** Minecraft Java Edition 1.20.1 / Forge 47.x / Java 17.  
> **Purpose:** define the shared artistic identity of every SurvivalPoints asset.

## 1. Visual identity

SurvivalPoints follows a **Vanilla+** direction: its assets should look as though they belong naturally in Minecraft Java Edition 1.20.1, while allowing a restrained amount of additional character and visual clarity.

Minecraft is the visual foundation, not merely a loose inspiration. A new asset must remain compatible with the game's block scale, pixel language, proportions, lighting, and material vocabulary. It must not look as though it was imported from a photorealistic, PBR, or high-poly game.

The goal is not to copy every vanilla asset. The goal is to extend the vanilla visual language consistently.

## 2. Approved art-direction decisions

The following decisions are the project's current approved baseline:

| Area | Rule |
|---|---|
| Vanilla similarity | **Vanilla+** — close to vanilla, with restrained SurvivalPoints character |
| Detail level | **Balanced** — neither overly primitive nor visually crowded |
| Form and surface | **Silhouette-first** — geometry defines shape and volume; texture defines surface and small details |
| Pixel density | **16 px per block unit as the default target** for ordinary block assets |
| Cross-category cohesion | **Strict shared visual language** across blocks, items, NPCs, clothing, weapons, and camp assets |
| Material palettes | Separate palettes per material, governed by common saturation, contrast, value, and hue-shifting rules |
| Wear | Contextual — based on the object's purpose, history, and condition; never added automatically to everything |

The 16 px/block rule is a density target, not a command to make every PNG 16×16. Minecraft player skins remain 64×64; other special formats are allowed only where required by the asset format or explicitly approved.

## 3. Shape, silhouette, and proportions

1. The silhouette must communicate the object's identity before small texture details are considered.
2. Proportions should feel consistent with vanilla Minecraft objects and the 16-unit block coordinate system.
3. Geometry should carry the parts that change the silhouette, create volume, or affect the object's read from different camera angles.
4. Texture should carry material identity, grain, small surface variation, wear, and details that do not need independent volume.
5. Avoid decorative geometry that does not improve silhouette, readability, or a functional visual distinction.
6. Do not simplify so aggressively that the object becomes ambiguous or loses its intended identity.
7. Do not add hardware, trim, supports, seams, straps, or other details unless the asset brief or approved reference calls for them.

For Minecraft Java block JSON, geometry must be representable by cuboid elements. More complex geometry is permitted only when the chosen project-supported model format and runtime path are verified for Forge 1.20.1.

## 4. Pixel-art language

All pixel-art assets must share the following characteristics:

- Crisp pixel boundaries and nearest-neighbour scaling for previews and pixel-art editing.
- No anti-aliasing, blur, or smooth gradients that soften the pixel grid.
- A consistent perceived pixel density within a visual set.
- Deliberate clusters of pixels rather than random noise.
- Controlled use of highlights, shadows, and surface marks.
- Readability at ordinary in-game viewing distance, not merely when enlarged in an editor.

Large areas should remain visually calm. Small details must support the form rather than compete with it. Pixel density should not be increased simply to make an asset appear more detailed.

## 5. Color philosophy

### 5.1 Material-specific palettes, shared rules

Each material may have its own palette, but all palettes must follow the same project-wide rules for:

- overall saturation;
- light-to-dark contrast;
- value separation between adjacent materials;
- restrained hue-shifting between highlights, base tones, and shadows;
- number and purpose of colors in a ramp;
- readability at in-game scale.

Do not force wood, iron, stone, leather, cloth, and skin into one identical palette. Their colors should be recognisably different while retaining a common SurvivalPoints visual character.

### 5.2 Vanilla material references

When a material already exists in vanilla Minecraft (for example, wood, stone, iron, leather, or standard cloth-like colors), use the corresponding vanilla resources and in-game colors as the primary palette reference.

Prefer direct references to vanilla resources when the exact vanilla appearance is appropriate. This avoids unnecessary duplicate copies of Mojang textures in the project and allows vanilla resource-pack behavior to remain available.

A direct `minecraft:` texture reference does **not** automatically create biome tinting. If a design requires biome tinting, the implementation must explicitly use the correct tint path (for example, a model tint index and a registered block-color provider) and verify it in-game. Do not assume that a vanilla texture reference alone supplies biome variation.

### 5.3 Palette discipline

- Keep each material's color ramp compact and purposeful.
- Use hue-shifting as a controlled transition, not as arbitrary color variation.
- Avoid excessive saturation, pure white highlights, chrome-like metal, and black shadows unless a specific reference requires them.
- Use accent colors sparingly and consistently across related assets.
- Reuse approved palette entries where materials or clothing modules should visually match.

## 6. Material identity

Material rendering should be recognisable at normal gameplay scale.

| Material | Visual direction |
|---|---|
| Wood | Vanilla wood-family color ranges; readable grain direction; restrained contrast; no photographic grain |
| Stone / concrete | Vanilla stone-family values; intentional pixel clusters; limited cracks and surface variation |
| Iron / steel | Vanilla metal-family grays; controlled highlights; no chrome or glossy PBR reflections by default |
| Rust | A separate, restrained warm ramp; appears only where the object's condition and context justify it |
| Leather | Vanilla leather-family browns; clear value separation from wood and fabric |
| Cloth | Material-specific but coordinated colors; folds and seams remain pixel-defined and low-detail |
| Skin / hair | Compact, readable ramps; consistent shading and pixel density across the NPC set |

These descriptions establish direction, not a universal fixed hex palette. Concrete color ramps belong in material palette files and asset-pack briefs.

## 7. Wear and condition

Wear is contextual, not a universal post-apocalyptic filter.

Use the following conceptual states where relevant:

1. **Clean / maintained:** little or no visible wear.
2. **Used:** small, selective marks consistent with normal use.
3. **Worn:** visible fading, scratches, dirt, or local damage.
4. **Damaged / improvised:** stronger defects justified by the object's story and function.

Not every asset needs all states. A newly made object may be clean; medical equipment may be maintained; a scavenged metal panel may be rusty. Do not apply dirt, rust, scratches, or patches automatically to every object.

Within a single asset pack, wear levels must be coherent. A set of related metal parts should not accidentally mix pristine chrome with heavily rusted surfaces unless the brief explicitly describes different histories or conditions.

## 8. Cross-category consistency

The shared visual language applies to all asset categories:

- blocks and structures;
- tools, weapons, and items;
- NPC skins and clothing;
- furniture, camp equipment, and decorations;
- future asset categories and optional content packs.

Categories may differ in silhouette, material palette, and functional detail. They must not drift into unrelated rendering styles, pixel densities, contrast levels, or surface treatments.

NPCs should look like members of the same world and community without becoming identical. Profession identity should come from a readable combination of silhouette, clothing, equipment, and restrained color coding—not from unrelated art styles.

## 9. Reference hierarchy

Use references in this order:

1. The approved specification for the exact asset pack.
2. The approved visual reference for that pack.
3. This Art Vision.
4. The technical production rules in `Vanilla-Friendly Guidelines.md`.
5. Verified vanilla Minecraft Java Edition 1.20.1 resources.

Pack-specific document sets that refine this order are listed in `.roo/plans/README.md`. For the fence pack the order is defined by the Fences document hierarchy: the main specification first, then the accepted change plans, then historical plans that are kept for traceability only.

A pack-specific approved decision may be more specific than this general vision. It must not be silently overridden by a general rule. If two approved sources conflict, stop and report the conflict rather than choosing one without the owner's decision.

Vanilla resources may be inspected locally as references. Do not copy Mojang assets into the public project repository or redistribute them as part of SurvivalPoints. Record resource locations and reference notes instead.

## 10. Visual acceptance criteria

An asset or pack is visually ready when:

- its silhouette and proportions are clear at ordinary gameplay scale;
- it looks compatible with Minecraft Java Edition 1.20.1;
- pixel boundaries remain crisp;
- material colors follow the approved material palette and shared color rules;
- detail density is balanced and consistent with related assets;
- wear is justified by the asset's condition and purpose;
- it remains visually coherent with other SurvivalPoints assets;
- approved references and pack-specific constraints are respected;
- in-game review confirms that the editor preview was not misleading.

## 11. What this document does not authorise

This document does not authorise the agent to:

- change gameplay, block behavior, collision, block states, entity behavior, or registration;
- alter an already approved asset specification;
- rename resource locations, model files, or skin indices;
- overwrite runtime resources or copy files into `src/main/resources` without task approval;
- introduce new model loaders, rendering frameworks, or runtime texture composition without a separate technical decision.

It defines the visual direction only. Production and integration are governed by `ASSET_WORKFLOW.md` and the applicable asset specification.
