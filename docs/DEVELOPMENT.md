# Apt Ores — Development Notes

Internal architecture and implementation notes. See the top-level `README.md` for a
public-facing summary.

Ores visually take on the material touching them. This is a purely client-side rendering
effect, not a gameplay feature: no blocks, items, block entities, or worldgen are added or
replaced anywhere in this codebase. The mod hooks *model baking* and swaps the baked model used
for each vanilla ore block with a composite that:

1. samples the six neighboring block states live, every time the containing chunk section is
   (re)meshed, to pick a "backdrop" material (weighted so a deliberately-placed non-stone/
   deepslate neighbor wins over plain stone), and
2. renders that backdrop's own baked model as the base layer, with the ore's cutout overlay
   texture on top, offset slightly to avoid z-fighting.

This is the same technique connected-texture mods (e.g.
[Continuity](https://github.com/PepperCode1/Continuity)) use to pick a texture variant from
neighbor state at bake/mesh time - applied here to swap a whole backdrop *model* instead of a
single texture tile. Because it's a pure function of current world contents, there's nothing to
sync, persist, or right-click to set: break or place the neighbor and the ore's appearance
updates on its own, the same way glass-pane connections do.

This is a rewrite of an earlier mod (`adaptive-ores`, sibling repo) that achieved the same look
by replacing every vanilla ore with a custom `Block` + `BlockEntity` that stored a sampled
backdrop and synced it to clients. That approach worked but was heavy-handed and a real
mod-compatibility risk (anything checking `block instanceof OreBlock`, or another mod that also
touches ore blocks, would conflict). Apt Ores never touches the block registry at all - it's a
model-loading hook only, so from every other mod's perspective the ore blocks are 100% vanilla.

## Why (and how) it can be client-only

Since nothing but rendering is touched, this mod runs purely client-side:
- `fabric.mod.json` declares `"environment": "client"`.
- The NeoForge entry point (`AptOresNeoForgeClient`) is annotated `@Mod(value = MOD_ID, dist =
  Dist.CLIENT)` and is the *only* `@Mod` class in the project - nothing runs on a dedicated
  server.

A player without the mod, or a server without the mod, just sees vanilla ore textures. No
version/presence handshake is needed.

## Architecture

### `common`
Loader-agnostic pieces only - `OreTypeDefinition`, `OreTypeLoader`, `OreTypeRegistry`, and
`BackdropSampler` use nothing but vanilla Minecraft classes, no Fabric/NeoForge APIs.

- **`OreTypeDefinition`** - one entry per ore family (coal, iron, copper, gold, redstone, lapis,
  diamond, emerald, plus whatever third-party mods/packs add - see below). Each entry knows:
  - the block ids it covers (e.g. `minecraft:iron_ore` / `minecraft:deepslate_iron_ore`),
  - the corresponding *block model* ids (`minecraft:block/iron_ore`, etc. - these are what the
    Fabric hook actually matches against),
  - its overlay texture id and overlay model id (`aptores:block/overlay_iron_ore`, a tiny
    `block/cube_all` model wearing the cutout texture - see Assets below).
- **`OreTypeLoader`** - reads every `assets/<namespace>/aptores/ore_types/*.json` resource off the
  live `ResourceManager` and turns each into an `OreTypeDefinition`. This is the extension point:
  adding a new ore family (nether ores, a modded ore, a third-party datapack/resourcepack) is one
  JSON file plus one overlay texture - no Java/code changes, no PR to this repo needed. Schema:
  ```json
  {
    "blocks": ["othermod:copper2_ore", "othermod:deepslate_copper2_ore"],
    "overlay_texture": "othermod:block/overlay/copper2_ore_overlay",
    "overlay_model": "othermod:block/overlay_copper2_ore"
  }
  ```
  `overlay_model` is optional and defaults to `<namespace>:block/overlay_<filename>` (matching the
  convention the 8 built-in ores use). Deliberately *not* a `SimpleJsonResourceReloadListener`:
  since this mod is purely a client rendering effect, definitions only need to be fresh by the
  time model baking runs, and both loaders' model-baking hooks already fire on every client
  resource reload with an up-to-date resource manager already available - no reload-listener
  ordering to get right.
- **`OreTypeRegistry`** - static holder rebuilt by `OreTypeRegistry.reload(...)` at the top of each
  loader's model-baking hook (see below); exposes `all()`, `byBlockId(...)`, `byBlockModelId(...)`,
  `isAdaptedOreBlockId(...)`. This is the single source of truth for "which things does this mod
  touch" at any given moment, and it's what `BackdropSampler` and both loaders' baking hooks read.

- **`BackdropSampler`** - `sample(BlockGetter level, BlockPos origin)` looks at the six face
  neighbors of `origin`, keeps the ones that are opaque, non-ore blocks, and returns the
  highest-weighted one (weight 4 for anything that isn't plain stone/deepslate, weight 1 for
  stone/deepslate, so one deliberately-placed special block beats the stone the ore usually sits
  in). Falls back to `Blocks.STONE.defaultBlockState()` if nothing qualifies. This is called
  fresh on every render-relevant call - nothing is cached or stored.

### `fabric`
- **`AptOresModelLoadingPlugin`** - registered from `AptOresFabricClient` (the mod's only
  entrypoint, `"client"` in `fabric.mod.json`). On every resource reload:
  1. `OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()))`
     re-reads every `ore_types` JSON off the just-updated resource manager.
  2. `context.addModel(key, SimpleUnbakedExtraModel.blockStateModel(...))` pins each ore's
     overlay-only model id under a fresh `ExtraModelKey<BlockStateModel>` so it gets loaded,
     baked, and stitched into the block atlas even though no blockstate/item references it
     directly (Fabric's typed replacement, as of 1.21.5, for the old untyped
     `context.addModels(List<ResourceLocation>)`).
  3. `context.modifyBlockModelAfterBake().register(...)` fires once per baked block-state model
     with the real `BlockState` available via `ctx.state()` (no more matching a
     `ModelResourceLocation`/model id back to a block by hand): if `OreTypeRegistry.byBlockId`
     recognizes it, the original model is wrapped in a new `AptOresBakedModel` carrying that
     type's `ExtraModelKey`; anything else passes through untouched.
- **`AptOresBakedModel`** - implements both `BlockStateModel` (vanilla's block-model type, shared
  with NeoForge/Forge since the 1.21.5 rework) and `FabricBlockStateModel` (the Fabric Renderer
  API's position-aware quad-emission interface, mixed onto every `BlockStateModel` - see the
  "Fabric FabricBlockStateModel is always true (and that's fine now)" section below before
  touching this file). `emitQuads` samples the backdrop, fetches its real block-state model via
  `Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop)`, delegates to it for the
  base layer, then emits the overlay's quads (translucent material, `disableDiffuse`) on top. The
  overlay's baked `BlockStateModel` is looked up lazily via
  `FabricBakedModelManager.getModel(overlayKey)` on first render rather than cached at bake time,
  since nothing guarantees our `ExtraModelKey`'s own bake has finished by the time
  `modifyBlockModelAfterBake` fires for an ore block earlier in the same reload - only that it's
  finished before anything actually renders.
- **`QuadHelper.offsetQuad`** - nudges a quad along its face normal to prevent z-fighting between
  backdrop and overlay; used both by `AptOresBakedModel`'s real emission path and its
  position-blind `collectParts` fallback.

### `neoforge`
- **`AptOresNeoForgeClient`** - the entire mod on this loader. `ModelEvent.RegisterStandalone`
  first calls `OreTypeRegistry.reload(OreTypeLoader.load(...))` (same as Fabric), then pins each
  ore's overlay model under a `StandaloneModelKey<QuadCollection>` (NeoForge's 1.21.5 typed
  replacement for the old `ModelResourceLocation.standalone(...)` + `RegisterAdditional`
  mechanism - conceptually the same idea as Fabric's `ExtraModelKey` above).
  `ModelEvent.ModifyBakingResult` then gets the *whole* bake result as one mutable
  `Map<BlockState, BlockStateModel>` (keyed by real `BlockState`, not a `ModelResourceLocation`
  needing a manual block-id lookup) - for every entry whose block matches an `OreTypeDefinition`,
  it looks up that type's already-baked overlay `QuadCollection` from `bakingResult.standaloneModels()`,
  builds a single translucent, outward-offset `BlockModelPart` from it via `SimpleModelWrapper`,
  and replaces the entry's value with a new `AptOresModel` wrapping both. Much simpler than
  Fabric's per-model-callback + lazy-lookup approach, since NeoForge hands you the complete map
  (and the standalone models, already baked) in one shot.
- **`AptOresModel`** - implements vanilla's `BlockStateModel`. NeoForge patches an extra,
  position-aware `collectParts(BlockAndTintGetter, BlockPos, BlockState, RandomSource, List)`
  overload directly onto `BlockStateModel` (not present on plain vanilla/Fabric - see
  `docs/PORTING.md` §4), so the backdrop is sampled and composited straight from that overload;
  the position-blind `collectParts(RandomSource, List)` overload is a fallback for contexts with
  no position (e.g. inventory/GUI).

### `forge`
- Regular (Minecraft)Forge 1.21.5 has **no equivalent of NeoForge's `StandaloneModelKey`/
  `RegisterStandalone`** - the same real gap documented for Forge 1.21.4 in `docs/PORTING.md`.
  Each overlay-only model is instead shadowed by a throwaway client item definition
  (`assets/aptores/items/overlay_*.json`); Minecraft's own per-item model JSON loader indexes
  these by file path regardless of whether a real item exists with that id, so they surface in
  `ModelBakery.BakingResult.itemStackModels()` as a `BlockModelWrapper` - whose baked quads are
  recovered with a narrow reflective field read (`BlockModelWrapper`'s `quads` field has no
  public accessor on this loader as of 1.21.5). Forge's `@Mod` annotation has no `dist`
  parameter, so the mod is split into two classes instead of NeoForge's one:
  - **`AptOresForge`** - the required `@Mod(MOD_ID)` entry point. Loader-neutral, does nothing,
    exists only because Forge requires exactly one `@Mod`-annotated class per mod id.
  - **`AptOresForgeClient`** - everything else, gated with
    `@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)` so
    Forge never loads this class - and therefore never resolves its client-only imports like
    `Minecraft` - on a dedicated server.
- **`AptOresModel`** - same `BlockStateModel` + position-aware-`ModelData` design as `neoforge`'s
  (via `net.minecraftforge.client.model.data.*` / `net.minecraftforge.client.ChunkRenderTypeSet`
  instead of the `net.neoforged.neoforge.*` equivalents), since regular Forge *did* keep the
  `ModelData` mechanism from 1.21.4 (unlike NeoForge, which moved to the position-aware
  `collectParts` overload described above - see that file's class javadoc for the full contrast).
- **`QuadHelper`** - looks byte-for-byte like `neoforge`'s but is **not** - Forge's patched
  `BakedQuad` names its extra boolean field's accessor `ambientOcclusion()`, while NeoForge's
  is `hasAmbientOcclusion()`; likewise Forge's `SimpleModelWrapper` kept vanilla's plain 3-arg
  constructor (`QuadCollection, boolean, TextureAtlasSprite`) while NeoForge's gained a 4-arg
  overload taking a trailing `RenderType`. Confirmed by decompiling both loaders' real 1.21.5
  jars, not assumed - see `docs/PORTING.md` §4/§7.

### Assets (`common/src/main/resources/assets/aptores/`)
- `aptores/ore_types/<type>.json` - the `OreTypeLoader` definitions for the 8 built-in ores (see
  `common` above for the schema). This is the same mechanism a third-party mod/pack uses to add
  its own ore.
- `textures/block/overlay/<type>_ore_overlay.png` - the cutout ore-fragment textures, carried
  over asset-for-asset from the old `adaptive-ores` mod's overlay art (transparent background,
  only the ore flecks opaque).
- `models/block/overlay_<type>_ore.json` - trivial `{"parent": "minecraft:block/cube_all",
  "textures": {"all": "aptores:block/overlay/<type>_ore_overlay"}}` per ore type. These exist
  purely so vanilla's own model baker produces a correctly-UV'd/wound cube model wearing the
  overlay texture - neither loader hand-bakes this themselves (the old mod did, by force-feeding
  a sprite into a manually-invoked `cube_all` bake; this version just lets the normal pipeline do
  it and asks for the result via `addModels`/`RegisterAdditional`).
- No item models, blockstates, or loot tables anywhere - there's nothing here for datagen to
  produce, since no blocks/items are registered.

## Known non-obvious bugs already fixed here (read before changing rendering code)

### 1. `neoforge/gradle.properties` must contain `loom.platform = neoforge`
This is a **per-subproject** properties file (separate from the root `gradle.properties`), and
nothing in `neoforge/build.gradle` references it - it's easy to not even notice it exists.
Without it, `architectury { neoForge() }` in `neoforge/build.gradle` fails at configuration time
with:
```
Could not find method neoForge() for arguments [net.neoforged:neoforge:21.1.209] on object of
type org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler.
```
even though the `neoForge()` DSL call itself runs without error - it just silently fails to
register the `neoForge` dependency configuration. This reproduces with byte-identical Gradle
config to `adaptive-ores` (confirmed via exhaustive bisection - directory location, mod
id/rootProject name, exact plugin version pins, Gradle version, all ruled out one at a time) and
in IntelliJ, not just this environment's CLI - it's a genuine trap in the
`architectury-plugin:3.4-SNAPSHOT` / `architectury-loom:1.11-SNAPSHOT` pairing for MC 1.21.1,
where the DSL call alone isn't sufficient. `adaptive-ores` carries this file forward invisibly
because it was generated once by whatever Architectury project wizard was originally used.
**If you ever regenerate or copy just the `build.gradle`/`settings.gradle` files from a working
Architectury project without also copying every per-subproject `gradle.properties`, you will hit
this again.**

### 2. Fabric (MC ≤1.21.4): `instanceof FabricBakedModel` is always true - check `isVanillaAdapter()` instead
**Superseded on MC 1.21.5+** - see the next section for the current (`FabricBlockStateModel`)
equivalent, which looks structurally the same but is *not* the same bug. Kept here for history
and because the same trap shape can recur on a future rewrite.

This one is subtle and caused a real, confusing bug (backdrop rendered as solid black, overlay
rendered fine on top - see `AptOresBakedModel` git history around the "black background" fix).

`fabric-renderer-api-v1` mixes `FabricBakedModel` onto **every** `BakedModel` in the game via
`BakedModelMixin` (`@Mixin(BakedModel.class)` merging the interface in). `FabricBakedModel`
declares `isVanillaAdapter()`, `emitBlockQuads(...)`, and `emitItemQuads(...)` all as `default`
methods, with `isVanillaAdapter()` defaulting to `true` for anything that didn't explicitly
implement the interface itself (plain vanilla models like `stone`'s baked model, or our own
overlay `cube_all` models - anything baked through the ordinary vanilla pipeline). This means:

```java
if (backdropModel instanceof FabricBakedModel fabricBackdrop) {
    fabricBackdrop.emitBlockQuads(...); // WRONG: always taken, backdrop is always "true" here
}
```

is **always true**, for every model, including plain stone - so this branch is always taken, and
for a vanilla-adapter model the default `emitBlockQuads` is a stub that emits nothing. The
correct check, used throughout `AptOresBakedModel` now:

```java
private static boolean isRealFabricModel(@Nullable BakedModel model) {
    return model instanceof FabricBakedModel fabricModel && !fabricModel.isVanillaAdapter();
}
```

Only delegate to `emitBlockQuads`/`emitItemQuads` when a model is a *genuine* Fabric-aware model
(another mod's custom `FabricBakedModel` that wants control over its own per-quad materials).
For everything else (the overwhelming common case - plain vanilla blocks), manually iterate
`model.getQuads(state, side, random)` per direction and push each quad through
`context.getEmitter().fromVanilla(quad, material, side).emit()` - note the explicit `.emit()`;
`fromVanilla(...)` only populates the emitter's current quad slot, it does not submit it on its
own, so a *second*, once-real bug (missing `.emit()` calls) was fixed here too. Both bugs
happened to mask each other during debugging - fixing `.emit()` alone did nothing because the
buggy `instanceof` check meant that code path was never even reached for stone; only fixing both
together resolved it.

NeoForge's model doesn't have an equivalent gotcha: `getQuads`/`getRenderTypes` (≤1.21.4) and
`collectParts` (1.21.5+) on that loader work directly off the vanilla model interface (no
Fabric-style multi-material emitter), so `AptOresModel` never had this class of bug.

### 2a. Fabric (MC 1.21.5+): `instanceof FabricBlockStateModel` is *also* always true - but this time it's not a bug
The 1.21.5 Fabric Renderer API rewrite (`FabricBakedModel` → `FabricBlockStateModel` +
`FabricBlockModelPart`, see `AptOresBakedModel`) looks like the same trap as bug #2 above at
first glance: `fabric-renderer-api-v1` mixes `FabricBlockStateModel` onto **every**
`BlockStateModel` again, so `instanceof FabricBlockStateModel` is again always true for any
model, vanilla or not.

The difference (confirmed by disassembling `FabricBlockStateModel.class`'s default method with
`javap -c`, not assumed from the similarity to bug #2): this time the mixin is an *interface*
mixin (`@Mixin(BlockStateModel.class) interface BlockStateModelMixin extends
FabricBlockStateModel {}`), not a per-implementation one, and `emitQuads(...)`'s default body is
a real implementation - it casts `this` back to `BlockStateModel`, calls
`collectParts(randomSource)`, and pushes each resulting `BlockModelPart` through the emitter via
`FabricBlockModelPart`'s own (also real) default `emitQuads`. There is no `isVanillaAdapter()`-
style flag and no silent no-op stub anymore. **Practical effect: an unconditional
`((FabricBlockStateModel) backdropModel).emitQuads(...)` call is correct here** - no
`isRealFabricModel`-style guard is needed on 1.21.5, and adding one back (out of habit, or by
copying bug #2's fix forward) would be a no-op at best. `AptOresBakedModel` casts directly for
exactly this reason. See `docs/PORTING.md` §5 for the general lesson this teaches about not
assuming a past "always-true instanceof" caveat still means the same thing after a rewrite.

### 3. Forge needed `architectury-loom` bumped to `1.17-SNAPSHOT` (and Gradle to 9.5.0) just to launch
`:forge:runClient` crashed on startup with `1.11-SNAPSHOT` (the version `fabric`/`neoforge` still
use fine) no matter what: fixing `forge/src/main/resources/META-INF/mods.toml` (Forge's own
`mods.toml` schema uses `mandatory = true`, not NeoForge's `type = "required"`) only got past the
mod-file-parsing stage. Deeper in, Forge's own early-display module-reads logic
(`ImmediateWindowHandler`/`DisplayWindow`) threw `NullPointerException`/`NoSuchElementException`
regardless of the `earlyWindowProvider` setting in `forge/run/config/fml.toml`. This is a
confirmed upstream bug - [architectury-loom#308](https://github.com/architectury/architectury-loom/issues/308),
MC 1.21.1 / Forge 52.1.x, same symptom - fixed by loom PR #343 ("Fix dev launch issues in newer
Forge"), but that fix only landed on the loom `dev/1.17` branch, never backported to `dev/1.11`
(confirmed via the GitHub API: `dev/1.11`'s last commit predates the fix). Getting it meant
bumping `dev.architectury.loom` to `1.17-SNAPSHOT` and `architectury-plugin` to `3.5-SNAPSHOT` in
the root `build.gradle`, which in turn requires Gradle 9.5.0 (`gradle/wrapper/gradle-wrapper.properties`)
since 1.17-SNAPSHOT calls a `Configuration.extendsFrom(Provider[])` overload that doesn't exist on
Gradle 8.x. All three platforms still build and run fine after this bump.

Even after the bump, `forge/build.gradle`'s `configurations` block needed one more Forge-specific
fix beyond what `neoforge/build.gradle` does: extend `developmentForge` from `common` (needed -
Loom's Forge dev launcher merges it into one `generated_XXX` module so the actual game layer gets
`:common`'s classes at all - `NoClassDefFoundError: grill24/aptores/OreTypeLoader` without it) but
do **not** also extend plain `runtimeClasspath` from `common` (that puts a second, independently-
loaded copy of the same package on the classpath used to build the parent module layer, either as
a straight JPMS split package - `java.lang.module.ResolutionException: Modules aptores.common.dev
and generated_XXX export package grill24.aptores` - or, if only `developmentForge` is removed
instead, as a `LinkageError` the first time a common class crosses layers, since it's now bound to
a different copy of the `minecraft` module than the mod code calling it). NeoForge doesn't need
this distinction; `developmentNeoForge.extendsFrom common` alone is fine there.

## Building

```
./gradlew build
```

Requires JDK 21. Targets Minecraft 1.21.5 (Fabric + NeoForge + Forge, via Architectury Loom).

```
./gradlew :fabric:runClient
./gradlew :neoforge:runClient
```

both launch a dev client with the mod active; both loaders have been manually verified to render
correctly (backdrop + overlay compositing) as of this writing.

## Deliberately not done / known limitations

- **No manual backdrop override.** Earlier design discussion considered keeping a
  right-click-to-set-backdrop interaction (like the old mod had); it was dropped in favor of a
  fully stateless design. "Adapting" now means literally "place the material you want touching
  the ore" - there's no way to give an ore a backdrop that isn't actually present next to it.
- **Item/inventory rendering always uses a stone backdrop** (`getQuads`/`emitItemQuads` fall back
  to `Blocks.STONE.defaultBlockState()`) since there's no world/position available for an
  `ItemStack` in a hand or GUI slot. Same fallback the old mod used.
- **No item models, loot tables, or datagen** - there's genuinely nothing to generate, since this
  mod registers zero blocks/items. If that ever changes (e.g. adding a config/debug item), datagen
  would need to be wired up from scratch; neither loader module has any `runs { data { ... } }`
  wired to a data generator entrypoint at the moment even though the Gradle run configs for it
  exist (copied from the old project's scaffolding, currently unused).
- **Neighbor sampling doesn't exclude non-ore-but-still-"ore-like" ambiguity beyond the ore-block
  check itself** - e.g. it doesn't specifically avoid picking another mod's ore block as a
  backdrop if that mod's ore isn't covered by an `ore_types` JSON the way
  `OreTypeRegistry.isAdaptedOreBlockId` expects (it only excludes block ids some loaded
  `ore_types` definition actually claims). Not considered a bug, just worth knowing if a
  modded-ore-compat pass is ever done.
- **Overlay textures are the original `adaptive-ores` art**, reused as-is. They've been visually
  confirmed to still work correctly now that they sit directly on live-sampled backdrops instead
  of the old mod's block-entity-stored one, but haven't been redone/improved for this project.
- **No resource-pack conflict handling.** If another resource pack or mod also tries to replace
  one of the 16 target ore block models, whichever model-loading hook runs last during that
  reload wins outright (no attempt to detect or merge). Same class of limitation Continuity and
  other CTM mods have with each other.
