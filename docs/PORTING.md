# Porting Apt Ores to a new Minecraft version

A general-purpose playbook for bumping this project (or any small Architectury-based,
client-rendering-only mod) to a newer Minecraft release. Written after the 1.21.1 → 1.21.4 port,
updated after the 1.21.4 → 26.1 port (which was a full rendering-API rewrite, see §8); update
this file with whatever the *next* port teaches you.

The short version: **treat every port as "expect a rendering-API rewrite, hope for a version
bump."** Dependency resolution is never the hard part. The model/rendering pipeline is where
Mojang and the loaders make breaking changes almost every version, and this mod's entire
technique (swap a vanilla block's baked model at bake time) sits directly on top of that
pipeline.

## 0. Before touching anything: read the target version's migration notes

- NeoForge publishes a primer for nearly every version bump:
  `https://github.com/neoforged/.github/blob/main/primers/<version>/index.md` (also mirrored at
  `docs.neoforged.net/docs/<version>/...`). Read the whole thing once, even the parts that look
  irrelevant - rendering changes are often buried under unrelated headings.
- Fabric's own release post (`https://fabricmc.net/<year>/<month>/<day>/<slug>.html`) usually
  calls out API-breaking changes at a high level.
- Regular (Minecraft)Forge does **not** reliably publish an equivalent primer. Don't assume
  parity with NeoForge's docs just because they share history - see step 4.

If the primer/changelog mentions anything like "item model rework," "baked model," "renderer,"
"quad," or "model loading," budget real time for this port, not just a version-number bump.

## 1. Find the dependency versions

For each of the five pins in root `gradle.properties`, get the exact string for the target MC
version. Do not guess/pattern-match from the previous version's string - patch numbers are not
predictable.

| Property | Where to look |
|---|---|
| `minecraft_version` | The target version itself |
| `architectury_api_version` | `https://maven.architectury.dev/dev/architectury/architectury/maven-metadata.xml`, or search `"Architectury API" "<version>"` on CurseForge/Modrinth - cross-check the Fabric and NeoForge listings agree on the same version number (they're usually published together, e.g. `15.0.2` fabric / `15.0.3` neoforge for 1.21.4) |
| `fabric_loader_version` | `https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml` (`<release>`) - loader isn't MC-version-pinned, latest stable is normally fine |
| `fabric_api_version` | Search `"Fabric API" "<version>"`, format is `X.Y.Z+<mc_version>` |
| `neoforge_version` | `https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge` - filter for the `<mc_minor>.<mc_patch>.*` prefix (e.g. `21.4.*` for 1.21.4), take the highest non-beta |
| `forge_version` | `https://files.minecraftforge.net/net/minecraftforge/forge/index_<version>.html` - use the "Recommended" build if one is marked, else "Latest" |

Also check whether `dev.architectury.loom` / `architectury-plugin` (root `build.gradle`) need a
version bump for the target MC version - Loom versions aren't always forward-compatible with
new MC releases, and old Loom versions sometimes have dev-launch bugs on newer MC that only get
fixed on a newer Loom line (see `docs/DEVELOPMENT.md` §3 for a concrete precedent). Check
`architectury-loom`'s GitHub releases/issues if `runClient`/build behaves strangely after
bumping everything else.

**The 26.1 port switched Loom variants entirely.** MC 26.1+ ships unobfuscated (official Mojang
names in the jar), so the remapping Loom no longer applies - the project now uses
`dev.architectury.loom-no-remap` (1.17.491, with `architectury-plugin` 3.5-SNAPSHOT, Gradle
9.5.0, JDK 25 toolchain) and the `mappings` dependency line was deleted. If a future target goes
back to an obfuscated MC, this is a real decision point (swap the plugin id back and re-add a
mappings line), not a no-op.

Update root `gradle.properties` with all of the above. Loader-specific `gradle.properties`
files (currently just `loom.platform = <loader>` in `neoforge/` and `forge/`) don't need version
changes - they're static per-subproject.

## 2. Build, then iterate on compile errors

```
./gradlew clean build
```

If dependency resolution itself fails (Gradle can't reach a repo, or can't find a version you
picked) - **stop and report exactly what failed rather than guessing further.** That's a
different, more fundamental problem than an API break.

If dependencies resolve but compilation fails, work through errors file by file. In this
codebase they will land in a small, predictable set of places, because that's everywhere this
mod touches Minecraft's rendering internals directly:

- `common/.../model/*.java` (nothing currently here touches loader-specific rendering, but check)
- `fabric/.../client/model/*.java`, `fabric/.../client/AptOresModelLoadingPlugin.java`
- `neoforge/.../client/model/*.java`, `neoforge/.../AptOresNeoForgeClient.java`
- `forge/.../client/model/*.java`, `forge/.../client/AptOresForgeClient.java`

Do **not** try to fix everything from memory or from web search alone (see step 3). Compile one
module, read the actual javac error, confirm the real signature, fix, recompile. Every loader
compiles independently, so you can fix and re-run `./gradlew :fabric:compileJava` (etc.)
in isolation rather than always doing a full build.

## 3. When docs/web search disagree or go silent: decompile the actual jar

This is the single most useful technique from the 1.21.4 port, and it's faster and more
trustworthy than continued searching once you've hit a wall. Web search and even official docs
sites frequently:
- describe an older or newer patch version than the one you're actually pinned to,
- get compressed/summarized by fetch tools in a way that silently drops a method or a whole
  overload,
- simply not exist yet for a very recent version.

Gradle has *already downloaded the real jars* for the exact version you pinned, the moment
`build.gradle` resolved them. Go straight to ground truth instead of trusting a paraphrase:

```sh
# Find every jar Gradle pulled for a given loader/version - both a mapped-classes jar
# (Mojave/official names, since Forge/NeoForge use Mojang mappings) and, if you're lucky,
# a matching -sources.jar:
find ~/.gradle/caches/fabric-loom/<mc_version>/<loader>/<loader_version> -iname "*.jar"
find ~/.gradle/caches/modules-2 -ipath "*neoforged/neoforge/<version>*" -iname "*.jar"
find ~/.gradle/caches/fabric-loom/minecraftMaven -iname "*<loader>-<version>*sources.jar"

# A full mapped-and-decompiled sources jar (best case) lives under
# fabric-loom/minecraftMaven/net/minecraft/<loader>-<version>-minecraft-merged/.../*-sources.jar
# - unzip just the class/file you care about:
unzip -o -q "<jar>" "net/minecraft/client/resources/model/ModelBakery*.java" -d /tmp/src

# If only compiled classes are available (no decompiled sources), javap the exact class:
JAVAP=$(find ~/.gradle/jdks -iname "javap.exe" | head -1)   # or plain `javap` on non-Windows
unzip -o -q "<jar>" "net/minecraft/client/resources/model/ModelBakery\$BakingResult.class" -d /tmp/cls
"$JAVAP" -p /tmp/cls/net/minecraft/client/resources/model/'ModelBakery$BakingResult.class'
```

`javap -p` prints every field/method/constructor signature with real (Mojang-mapped) types -
enough to write correct code without ever seeing a decompiled method body. This is how the
1.21.4 port confirmed, in order of actual investigation: the new `BakedQuad` constructor
signature, that `ModelBakery.BakingResult` has a `standaloneModels()` map on NeoForge but *not*
on regular Forge's copy of the same record, and that `BlockModelWrapper` (an `ItemModel`
implementation) exposes its wrapped `BakedModel` via a plain public field. None of that came
back reliably from web search.

The 26.1 port leaned on the same technique for nearly everything - the whole model pipeline was
rewritten (see §8), and the real signatures of `BlockStateModel`, `BlockStateModelPart`,
`BakedQuad`, the loader extension interfaces, and every event record were all read off the
pinned jars with `javap`. Two 26.1-specific notes:

- When the loader's own (non-vanilla) classes aren't in the minecraft deobf jar, they're in the
  loader's `forge-universal.jar` / `*fmlcore*.jar` / `*eventbus*.jar` under `~/.gradle/caches`.
  `find ~/.gradle/caches -name '*.jar' | xargs -I{} sh -c 'unzip -l "{}" | grep -q <class> && echo {}'`
  finds which jar owns a class.
- `javap` alone can't show `@Deprecated` (class-retention). For "uses a deprecated API" warnings,
  either accept them or narrow down with `-Xlint:deprecation` compiler args.

For NeoForge's *own* (non-Minecraft) classes, `net.neoforged:neoforge:<version>:sources` in the
Gradle module cache (`~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/<version>/`)
is a full readable sources jar - just `unzip` the `.java` file you want directly.

## 4. Expect the three loaders to have diverged - don't assume parity

Fabric, NeoForge, and (regular Minecraft)Forge are three independently-maintained codebases that
happen to share a common ancestor (NeoForge forked from Forge; both patch the same Mojang base).
**Nothing guarantees they still expose the same extension points.** Concretely, in the 1.21.4
port:

- Fabric's `ModelLoadingPlugin`/FRAPI API was rewritten from the ground up (no `RenderContext`
  class anymore; `emitBlockQuads`/`emitItemQuads` take a `QuadEmitter` + a cull-test predicate
  directly). Expected, since Fabric API versions independently of MC internals.
- NeoForge's `ModelEvent.RegisterAdditional` / `ModifyBakingResult` survived essentially intact,
  just restructured (`getModels()` → `getBakingResult().blockStateModels()`, plus a new
  `standaloneModels()` map that's actually a nicer replacement for the old
  `ModelResourceLocation.standalone()` workaround).
- Regular Forge **removed `ModelEvent.RegisterAdditional` outright, with no replacement event**,
  confirmed by decompiling the real 54.1.14 jar - not by an accidental copy edit; NeoForge kept
  a facility Forge dropped. This forced a real (if small) architectural workaround: shipping a
  throwaway per-item client model JSON (`assets/aptores/items/overlay_*.json`) purely so
  Minecraft's own item-model file scanner (which indexes by file path, not by real registered
  item id) would surface the model in `BakingResult.itemStackModels()` where it could be
  unwrapped back to a `BakedModel`.

The 26.1 port diverged even harder - the three loaders no longer even expose the *same dispatch
interface* to the renderer (the whole story is §8). The highlights, each confirmed against the
real 26.1 jars:

- **NeoForge deleted `ModelData`/`ModelProperty` outright** while **regular Forge kept them**.
  This mod's whole technique (sample neighbors per-position at mesh time) had to become a
  position-aware `collectParts` on NeoForge and a `getModelData`/`collectParts(ModelData)` pair
  on Forge. The two loaders genuinely implemented the same visual feature through different APIs,
  and neither approach compiles on the other loader.
- **NeoForge's `@EventBusSubscriber` lost its `bus()` element.** Registering a handler now means
  an explicit `modEventBus.addListener(this::method)` in the `@Mod` constructor. Forge's
  `@Mod.EventBusSubscriber(modid, value, bus)` survived.
- **Forge's event API moved package**: `@SubscribeEvent` is now
  `net.minecraftforge.eventbus.api.listener.SubscribeEvent`, and events are records with a static
  `BUS` field rather than a class-wide registry.
- **Forge made `BlockStateModelWrapper.model` private.** The 1.21.4 trick (read the wrapped model
  straight off a public field) needed an Access Transformer (`META-INF/accesstransformer.cfg`) -
  Loom applies it at compile time, Forge at runtime.

**Canonical Forge overlay-pinning (cross-version review):** regular Forge *does* have a hook for
pinning an unreferenced model — `ModelEvent.RegisterModelStateDefinitions`, available since 1.21.4.
Register a plain, never-registered `Block`'s `StateDefinition` under a synthetic id backed by a
`blockstates/*.json` file, and the model is baked into `BakingResult.blockStateModels()` like a real
block. The throwaway item-model-JSON workaround described above was never necessary (it stemmed from
missing this event) and is superseded — every port now uses `RegisterModelStateDefinitions`.

**Lesson: verify each loader's relevant API independently. Do not port NeoForge's fix to Forge
(or vice versa) without re-checking that the same classes/methods still exist there.** If one
loader's public API for something this mod needs has genuinely disappeared, look for:
1. A different but equivalent event/hook (search the loader's own javadoc/package summary, or
   decompile and grep the loader's client-event package wholesale for likely-named classes).
2. A vanilla mechanism that gets the same result through an unrelated, already-loaded pathway
   (this is how the Forge item-model-JSON workaround was found - vanilla's item model system
   happened to expose a side door once regular block-model events stopped covering it).
3. Only as a last resort, a mixin - this project has the Architectury Loom mixin plugin applied
   in `common/build.gradle` but currently ships **no** mixins; adding one is a much bigger
   commitment (new infra, more version fragility) than options 1-2, and should be a deliberate
   choice, not a shortcut.

## 5. Recurring categories of breakage to expect (grounded in the 1.21.1→1.21.4 and 1.21.4→26.1 diffs)

These are the specific things that broke last time, listed as *categories* so you know what
class of change to look for even if the exact API differs again next time. The 1.21.4→26.1
categories are marked **[26.1]**:

- **`BakedModel` interface shrinking.** `getOverrides()`/`isCustomRenderer()` were removed
  entirely as part of the item-model rework. If a future version removes/adds more `BakedModel`
  methods, the fix pattern is the same: delete now-invalid `@Override`s (if this mod never
  relied on the removed behavior beyond a dummy return value), or - if a *required* piece of
  functionality moves elsewhere - migrate to wherever it moved.
- **Record/DTO shape changes to bake-time data classes** (`BakedQuad`, `ModelBakery.BakingResult`,
  `ModelResourceLocation`). These are usually additive (one new trailing field/param) and
  mechanical to fix once you have the real signature (step 3) - but the mapping from old call
  site to new one isn't always 1:1 (e.g. `ModelResourceLocation.standalone()` disappearing
  entirely rather than just being renamed).
- **Renderer-plugin API churn on Fabric specifically** (FRAPI/Indigo). Fabric's rendering API is
  versioned and evolved independently of vanilla, and has historically been rewritten more
  aggressively than the loaders' own model-baking hooks. Expect `RenderContext`/`RendererAccess`
  or their equivalents to keep changing shape; the *concepts* (a per-quad emitter, a material
  finder, a vanilla-adapter flag to avoid double-processing plain models - see
  `docs/DEVELOPMENT.md` §"Known non-obvious bugs" #2) have stayed stable across at least two
  rewrites so far.
- **Loader-specific event/hook removal** (see §4). This is the one category that isn't just
  "update a call site" - budget real design time if it recurs.
- **[26.1] The `BakedModel` interface is gone - split into `BlockModel`/`ItemModel`/`BlockStateModel`.**
  Vanilla deleted the single "one model type for everything" abstraction and split it by usage.
  Block rendering dispatches through `BlockStateModel` (with per-position variants for this
  mod's needs); the old `getQuads(...)@Override`/`getParticleIcon()`/`getTransforms()` shape is
  unrecognizable. This is a *design* change, not a rename: even "which methods to override"
  differs per loader (see §4 and §8). Treat any future version's `BakedModel`/model changes as
  potentially the same scale of rewrite.
- **[26.1] `ResourceLocation` → `Identifier`.** A drop-in API rename
  (`net.minecraft.resources.Identifier`, same factory/parse helpers) but it touches every
  reference in the codebase, so it generates a wall of mechanical compile errors. Grep-and-replace
  the import plus any `new ResourceLocation(...)` call sites; no logic changes.
- **[26.1] `BakedQuad` became a record** (in `net.minecraft.client.resources.model.geometry`) with
  four `Vector3fc` positions, four packed-UV `long`s, a `Direction`, and a `MaterialInfo`.
  Quad rebuilding (this mod offsets vertices to avoid z-fighting) now reads via `position(i)` and
  reconstructs with the record constructor - and **the constructor differs between loaders**
  (NeoForge's has two ASM-added `BakedNormals`/`BakedColors` components, so 12 args vs Forge's
  pure-vanilla 10). Anything touching `BakedQuad`'s innards must be written per-loader.
- **[26.1] Position-aware rendering hooks.** Vanilla's mesher now feeds the per-position bake
  context (level, pos, neighbor-dependent data) into model dispatch instead of baking it in at
  model-bake time. This mod moved from "swap in a pre-baked composite" to "sample neighbors at
  mesh time" - the *kind* of change where the old API simply no longer expresses the feature, so
  look for a new hook that receives the `BlockAndTintGetter`/`BlockPos` rather than trying to
  force the old API.

## 6. After it compiles

- `./gradlew clean build` (not just `build` - confirms nothing is hiding behind Gradle's
  up-to-date cache).
- Run whatever `check`/`test` tasks the project defines (currently none have real test sources -
  `NO-SOURCE` in the build log is expected and fine, not a skipped failure).
- If you can, `./gradlew :fabric:runClient` / `:neoforge:runClient` / `:forge:runClient` and
  visually confirm ore rendering (backdrop + overlay compositing) still looks right in-game -
  a clean compile does not guarantee the new API calls actually produce the same visual result
  (e.g. a swapped parameter order could compile fine and render wrong).
- Update `docs/DEVELOPMENT.md`'s "Building" section and any version-specific prose
  (`minecraft_version` mentions, loom/Gradle version mentions) to match the new target.

## 7. Parallelizing across multiple target versions

If porting to several MC versions at once (e.g. one agent per target version), each agent
should:
- Treat this file as the starting checklist, but expect to *add* newly-discovered gotchas back
  into it (or a per-version equivalent) rather than only reading it passively.
- Independently verify dependency versions and API shapes for its own target - don't assume a
  sibling agent's findings for a different MC version transfer, even between adjacent versions.
- Use the decompile-the-real-jar technique (§3) as soon as web search gives a vague or
  conflicting answer, rather than spending many search queries trying to disambiguate secondhand
  descriptions.
- Explicitly re-check both NeoForge *and* regular Forge's relevant APIs independently (§4) - do
  not port one loader's fix to the other on the assumption they stayed in sync.
- Report honestly if dependency resolution fails outright (network/repo access) - that's a
  different, more fundamental blocker than an API break, and worth surfacing immediately rather
  than working around.

## 8. The 26.1 rendering rewrite (what this port actually did)

Vanilla 26.1 removed the `BakedModel` interface and replaced it with three separate
position-independent model types - `BlockModel` (baked standalone/stateless), `ItemModel`, and
`BlockStateModel` - and moved position/lighting concerns out of the baked model entirely. A model
no longer produces textured, positioned geometry; it produces *parts* (via
`collectParts(RandomSource, List<BlockStateModelPart>)`), and the mesher applies AO, lighting,
and the block's position when assembling the world mesh. `BakedQuad` is now a record in
`net.minecraft.client.resources.model.geometry` (four `Vector3fc` positions, four packed-UV
`long`s, a `Direction`, a `MaterialInfo`) rather than a mutable class you build via a builder.

This mod's technique - "swap a target ore's baked model for a composite that renders its neighbor
as the base layer" - stopped being expressible as a bake-time swap, because the old composite
relied on knowing the block's world position (to sample neighbors) *before* baking, and the new
pipeline deliberately decouples geometry from position. The port moved neighbor-sampling from
bake time to mesh time, via each loader's per-position dispatch hook. The three loaders wired
that up three different ways:

| Concern | Fabric | NeoForge | Forge |
|---|---|---|---|
| Per-position hook | `FabricBlockStateModel.emitQuads(emitter, view, pos, state, random, cullTest)` (FRAPI) | 5-arg `collectParts(level, pos, state, random, parts)` via ASM `BlockStateModelExtension` | `getModelData(level, pos, state, modelData)` then 3-arg `collectParts(random, parts, modelData)` (kept `ModelData`) |
| Position data plumbing | passed straight to `emitQuads` | passed straight to `collectParts` | stored in `ModelData` via a `ModelProperty<BlockState>` |
| Composite base | wrapper around vanilla model | `DelegateBlockStateModel` wrapper | plain `BlockStateModel` + `ModelData` |
| Pinning unreferenced overlay models | `ExtraModelKey` + `SimpleUnbakedExtraModel.blockStateModel(id)` + `FabricModelManager.getModel(key)` | `StandaloneModelKey` + `SimpleUnbakedStandaloneModel.blockStateModel(id)` + `ModelEvent.BakingCompleted` → `BakingResult.standaloneModels()` | no hook at all - item-shadow JSONs (`items/overlay_*.json`) surfaced in `BakingResult.itemStackModels()`, unwrapped from `BlockStateModelWrapper.model` (access-transformed) |
| `BakedQuad` rebuild (offset) | FRAPI `QuadEmitter` | 12-arg record ctor (+`bakedNormals`/`bakedColors` ASM components) | 10-arg record ctor |

Other 26.1 changes that mattered: `ResourceLocation` → `Identifier` (mechanical); NeoForge's
`@EventBusSubscriber` dropped `bus()` so handlers register via `modEventBus.addListener` in the
`@Mod` constructor; Forge's `@SubscribeEvent` moved to
`net.minecraftforge.eventbus.api.listener.SubscribeEvent` and events became records with static
`BUS` fields; Forge made `BlockStateModelWrapper.model` private (fixed with an access
transformer). The immutable `blockStateModels()` map of 1.21.4 is a mutable `HashMap` in 26.1, so
wrapping an existing ore model via `entry.setValue(...)` is the supported move on both Forge and
NeoForge.

Two take-aways to carry into the next port. First, **"expect a rendering-API rewrite" now means
"expect the loaders to disagree about the rendering API"** - the model rewrite landed in vanilla,
but each loader's extension surface sat on top of it differently, and the correct per-loader hook
was only discoverable by decompiling each loader's jar (see §3 and §4). Second, if a model isn't
referenced by any blockstate or item, **each loader has a different mechanism to pin it into the
bake** (ExtraModelKey / StandaloneModelKey / item-shadow) - this is the third time the
"unreferenced overlay model" problem has surfaced, and it's worth checking all three mechanisms
fresh rather than assuming they survived from one version to the next.

## Prior-version port history (learnings carried forward)

Each port was done independently from the 1.21.4 base, so this file's own sections above record
what *this* port observed jumping straight from 1.21.4 to the target, and may describe a change
that actually first landed in an earlier intermediate version as if it were new. The subsections
below consolidate each intermediate version's findings, correctly attributed, so no later port
has to re-derive them.

### From 1.21.5 — `BakedModel` → `BlockStateModel`, and the loaders' first real divergence
- Vanilla replaced `BakedModel` (`net.minecraft.client.resources.model`) with `BlockStateModel` +
  `BlockModelPart` (`net.minecraft.client.renderer.block.model`) for block rendering, on **all
  three loaders** (Fabric's `FabricBakedModel` layer no longer insulated it from this churn).
- Fabric's model-loading API became typed: `Context.addModel(ExtraModelKey<T>,
  UnbakedExtraModel<T>)` / `Context.modifyBlockModelAfterBake()`; `FabricBakedModel`/
  `isVanillaAdapter()` became `FabricBlockStateModel`/`FabricBlockModelPart` (mixed onto the
  vanilla `BlockStateModel`/`BlockModelPart` interfaces via an *interface* mixin).
- NeoForge grew a typed `StandaloneModelKey`/`ModelEvent.RegisterStandalone` facility.
- Regular Forge still has **no** standalone-model registration at all; the 1.21.4 throwaway
  item-model-JSON workaround carried forward, adapted to recover quads from `BlockModelWrapper`'s
  now-private `quads` field via a narrow reflective read.
- **NeoForge and Forge quietly diverged on two details of the same rework**: `BakedQuad`'s extra
  boolean accessor is `hasAmbientOcclusion()` on NeoForge but `ambientOcclusion()` on Forge;
  `SimpleModelWrapper` gained a 4-arg ctor (trailing `RenderType`) on NeoForge but stayed 3-arg on
  Forge. Each compiles in isolation and only fails on the other loader — verify each loader's jar
  independently (`javap`).
- §5 lesson: a documented "always-true `instanceof` is a bug" caveat can flip to "always-true and
  that's fine" after a rewrite — re-verify the actual default-method body (see `DEVELOPMENT.md`
  bug #2 vs #2a) rather than assuming a past caveat still holds.

### From 1.21.6 — Forge's corrupted "Latest" jar, and `RegisterModelStateDefinitions`
- `forge_version`: **don't trust "Latest"/"Recommended" blindly.** Forge 56.0.9 produced a
  corrupted merged Minecraft+Forge jar (`:forge:compileJava` failing with ~50 nonsensical `cannot
  find symbol` errors), while 56.0.8 compiled cleanly. If a "Latest"/"Recommended" build does this,
  **bisect `forge_version` across nearby builds first** (`sed` + `:forge:compileJava` per
  candidate) before assuming a Loom/tooling dead end.
- Forge gained `ModelEvent.RegisterModelStateDefinitions` (new in 1.21.6): register a
  `StateDefinition` from a plain never-registered `new Block(...)` under a synthetic id with a
  matching `assets/aptores/blockstates/overlay_*.json`, and the overlay is baked through the
  normal blockstate pipeline into `BakingResult.blockStateModels()` — the official replacement for
  the item-JSON shadow trick. (Caveat: a third-party ore type added purely via an `ore_types` JSON
  still needs to ship a matching blockstate JSON to get baked on Forge.)
- `BakedQuad` gained a 7th ctor param (`ambientOcclusion`) and its accessors became record-style
  (`direction()`/`vertices()`/`tintIndex()`/...). As of 1.21.6, item models bake entirely
  separately from block-state models, so composite classes no longer need an item path.

### From 1.21.9 — per-part render layers, and item rendering decoupled
- **Chunk render-layer selection moved from per-model to per-part.** `RenderType.translucent()` is
  gone; use the `ChunkSectionLayer` enum and each loader's per-`BlockModelPart` override
  (`getRenderType(BlockState)` on NeoForge, `layer()`/`layerFast()` on Forge). That per-part
  override is what makes a solid-backdrop + translucent-overlay composite possible at all.
- `ModelBakery.BakingResult.blockStateModels()` became keyed by `BlockState` directly.
- NeoForge's `ModelEvent.RegisterAdditional` was removed, replaced by `ModelEvent.RegisterStandalone`
  + `StandaloneModelKey` (`SimpleUnbakedStandaloneModel.quadCollection(id)`).
- Forge's event API moved: `@SubscribeEvent` is now
  `net.minecraftforge.eventbus.api.listener.SubscribeEvent`, and events became records with
  accessor-style getters (`ModifyBakingResult.getResults()`); `@Mod.EventBusSubscriber` +
  `@SubscribeEvent` still works unchanged otherwise.
- **Item rendering fully decoupled from block rendering** — mutating `blockStateModels()` no longer
  affects `itemStackModels()`, so held/inventory ore items show the plain vanilla texture (a known
  regression, not yet fixed — see `DEVELOPMENT.md`).

### From 1.21.11 — `ResourceLocation` → `Identifier`, and Forge's typed event bus
- **`ResourceLocation` was renamed to `Identifier`** (`net.minecraft.resources.Identifier`, same
  API shape). Grep the target jar for a same-shaped class under a new name before assuming a
  "cannot find symbol" is a typo.
- Forge still had no `RegisterAdditional`/`RegisterStandalone` (re-confirmed against 61.2.0), so the
  overlay-shadow approach persisted, now via a reflective read of `BlockModelWrapper`'s private
  `quads` field and manually rebuilding a `BlockModelPart` from the flattened quads.
- Forge's `SimpleModelWrapper` carries `layer`/`layerFast` (5 record components), not NeoForge's/
  vanilla's single `renderType` — the two loaders' "rebuild a wrapper" helpers cannot share an
  implementation.
- Forge's event bus became typed `EventBus<T>`/`BUS` fields (`ModelEvent` is no longer an
  `IModBusEvent`); `@SubscribeEvent` moved to `net.minecraftforge.eventbus.api.listener.SubscribeEvent`.
