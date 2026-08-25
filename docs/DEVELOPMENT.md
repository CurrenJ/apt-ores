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

> **1.21.9 model rework.** Minecraft 1.21.9 replaced `BakedModel`/block-context `getQuads(...)`
> entirely for block-in-world rendering with `BlockStateModel` (one per `BlockState`, exposing
> `collectParts(...)`/`emitQuads(...)`) and `BlockModelPart` (the actual per-quad geometry unit,
> exposing `getQuads(Direction)`). Chunk render-layer selection also moved from a per-model
> `RenderType` set to a per-`BlockModelPart` `ChunkSectionLayer` (via a loader-mixed-in extension
> method), since a block's overall chunk layer used to be looked up once per `Block` - the
> per-part override is what lets this mod put a `SOLID` backdrop and a `TRANSLUCENT` overlay in
> the same block's mesh. See `docs/PORTING.md` §5 for the full breakage history across ports.

### `fabric`
- **`AptOresModelLoadingPlugin`** - registered from `AptOresFabricClient` (the mod's only
  entrypoint, `"client"` in `fabric.mod.json`). On every resource reload:
  1. `OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()))`
     re-reads every `ore_types` JSON off the just-updated resource manager.
  2. `context.addModel(key, SimpleUnbakedExtraModel.blockStateModel(...))` pins each overlay
     model as an "extra" model (Fabric's replacement for the old `addModels`/
     `modifyModelAfterBake` pinning idiom) so it gets loaded, baked, and its texture stitched
     into the block atlas even though no blockstate/item references it directly. The
     `ExtraModelKey<BlockStateModel>` it was baked under is stashed in `OverlayModelRegistry`.
  3. `context.modifyBlockModelAfterBake().register(...)` inspects every *real* block's baked
     model as it comes through: if the block id (`ctx.state().getBlock()`) matches an
     `OreTypeDefinition` (via `OreTypeRegistry.byBlockId`), the vanilla model is wrapped in a new
     `AptOresBakedModel`; anything else passes through untouched. Our own pinned overlay models
     never reach this callback since they're baked through the separate "extra model" pipeline.
- **`AptOresBakedModel`** - implements `BlockStateModel` directly. `emitQuads` samples the
  backdrop, fetches its real `BlockStateModel` via
  `Minecraft.getInstance().getBlockRenderer().getBlockModel(backdrop)`, emits its quads as the
  base layer by delegating straight to that model's own `emitQuads`, then emits the overlay
  model's quads on top through a pushed `QuadTransform` that offsets each quad along its face
  normal and forces `renderLayer(ChunkSectionLayer.TRANSLUCENT)`. **The old "Fabric
  `FabricBakedModel` gotcha" below no longer applies in 1.21.9** - Fabric now mixes
  `FabricBlockStateModel`'s default methods directly onto the `BlockStateModel` interface itself
  (confirmed by decompiling `fabric-renderer-api-v1`'s own `WrapperBlockStateModel`, which
  `implements BlockStateModel` and freely `@Override`s `emitQuads`), so every `BlockStateModel`
  - including plain vanilla ones - can be called through `emitQuads` directly with no
  `isVanillaAdapter()`-style guard needed.
- **`OverlayModelRegistry`** - a tiny static `Map<OreTypeDefinition, ExtraModelKey<BlockStateModel>>`,
  reset and repopulated on every resource reload by the plugin above. The actual baked
  `BlockStateModel` is fetched lazily, on every call, via
  `((FabricBakedModelManager) Minecraft.getInstance().getModelManager()).getModel(key)` - extra
  models are guaranteed baked before anything renders, so this lookup is always safe regardless
  of bake order (same reasoning the old per-model-callback registry relied on).

### `neoforge`
- **`AptOresNeoForgeClient`** - the entire mod on this loader. `ModelEvent.RegisterStandalone`
  (NeoForge's 1.21.9 replacement for the removed `ModelEvent.RegisterAdditional`) first calls
  `OreTypeRegistry.reload(OreTypeLoader.load(...))` (same as Fabric), then pins each overlay
  model via `event.register(key, SimpleUnbakedStandaloneModel.quadCollection(modelId))`, keyed by
  a `StandaloneModelKey<QuadCollection>` stashed per ore type. `ModelEvent.ModifyBakingResult`
  then gets the *whole* bake result as one mutable `Map<BlockState, BlockStateModel>` (blockstate
  models are now keyed directly by `BlockState`, not `ModelResourceLocation`) - for every entry
  whose block id matches an `OreTypeDefinition`, it looks up that type's already-baked overlay
  `QuadCollection` from `bakingResult.standaloneModels()` and replaces the entry's value with a
  new `AptOresModel` wrapping both.
- **`AptOresModel`** - implements NeoForge's `DynamicBlockStateModel` (a `BlockStateModel`
  subtype whose `collectParts` overload *does* receive a `BlockAndTintGetter`/`BlockPos`, unlike
  plain vanilla `BlockStateModel`). It samples the backdrop directly in `collectParts`, delegates
  to the backdrop's own `collectParts` for the base layer, then appends a single synthetic
  `BlockModelPart` wrapping the overlay `QuadCollection` (quads offset via `QuadHelper`) whose
  `getRenderType(BlockState)` override (from the mixed-in `BlockModelPartExtension`) forces
  `ChunkSectionLayer.TRANSLUCENT` regardless of the backdrop's own layer.

### `forge`
- Regular (Minecraft)Forge 1.21.9 still has **no equivalent of `ModelEvent.RegisterAdditional` or
  a `standaloneModels()` facility** (same gap as 1.21.4, see `docs/PORTING.md` §4). Instead of the
  old throwaway-item-model-JSON trick (`assets/aptores/items/overlay_*.json`, which stopped
  working once `BlockModelWrapper` lost its public `model: BakedModel` field in 1.21.9's item
  rework), each overlay model is now shadowed by a **synthetic, never-registered `Block`** whose
  `StateDefinition` is registered via the new `ModelEvent.RegisterModelStateDefinitions` event -
  the vanilla blockstate-model loader then bakes `assets/aptores/blockstates/overlay_*.json` for
  it exactly like a real block's blockstate file, and the baked result shows up in
  `ModelBakery.BakingResult.blockStateModels()` keyed by that synthetic block's default state,
  right alongside the real ore blocks.
  - **`AptOresForge`** - the required `@Mod(MOD_ID)` entry point. Loader-neutral, does nothing,
    exists only because Forge requires exactly one `@Mod`-annotated class per mod id.
  - **`AptOresForgeClient`** - everything else, gated with
    `@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)` so
    Forge never loads this class - and therefore never resolves its client-only imports like
    `BlockStateModel`/`Minecraft` - on a dedicated server. Note Forge's event-bus annotations moved
    package in 1.21.9 (`@SubscribeEvent` is now `net.minecraftforge.eventbus.api.listener.SubscribeEvent`)
    and `ModelEvent`'s member events became records (`ModifyBakingResult.getResults()` instead of
    `.getBakingResult()`) - `@Mod.EventBusSubscriber` + `@SubscribeEvent` on a static method still
    works unchanged otherwise.
  - **`AptOresModel`** - implements plain `BlockStateModel`, using Forge's own `IForgeBlockStateModel`
    extension (`getModelData`/`collectParts(random, dest, data, renderType)`) to sample the
    backdrop and stash it in a `ModelProperty<BlockState>`, the same pattern the pre-1.21.9 Forge
    and NeoForge models both used. The overlay's own baked `BlockStateModel` (from the synthetic
    block above) is asked for its parts via `collectParts(random)`, each of which is wrapped to
    offset its quads (`QuadHelper`) and force `layer()` (from `IForgeBlockModelPart`) to
    `ChunkSectionLayer.TRANSLUCENT`.

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
- No item models, blockstates, or loot tables for *real* blocks/items anywhere - there's nothing
  here for datagen to produce, since no blocks/items are registered. (`forge/src/main/resources/
  assets/aptores/blockstates/overlay_*.json` is the one exception - see the `forge` section above;
  it exists purely as a loader-specific plumbing trick, not a real block.)

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

### 2. Fabric: the old `instanceof FabricBakedModel` gotcha (pre-1.21.9 only, kept for history)
This bit on Fabric versions through 1.21.4/1.21.6-ish and is worth knowing about even though it
**no longer applies as of the 1.21.9 port** (see below) - it caused a real, confusing bug
(backdrop rendered as solid black, overlay rendered fine on top).

`fabric-renderer-api-v1` used to mix `FabricBakedModel` onto **every** `BakedModel` in the game
via a mixin on the concrete baked-model class, with `isVanillaAdapter()` defaulting to `true` for
anything that didn't explicitly implement the interface itself. That made a plain
`instanceof FabricBakedModel` check always true, including for plain stone, silently dropping
that model's quads unless the code also checked `!isVanillaAdapter()` and manually iterated
`model.getQuads(...)` with an explicit `emitter.fromVanilla(quad, material, side).emit()` for the
vanilla-adapter case (note the `.emit()` - `fromVanilla(...)` alone only populates the emitter's
current quad slot).

**As of the 1.21.9 port, this class of gotcha is gone.** Fabric's rewritten renderer API mixes
`FabricBlockStateModel`'s default methods (`emitQuads`, `particleSprite`) directly onto the
`BlockStateModel` *interface itself*, not onto one concrete implementing class - confirmed by
decompiling `fabric-renderer-api-v1`'s own `WrapperBlockStateModel`, which plainly
`implements BlockStateModel` and `@Override`s `emitQuads` with no separate interface cast needed.
Every `BlockStateModel`, including vanilla ones, can now be called through `emitQuads` directly;
its default implementation already safely delegates to `collectParts` + each part's own
`emitQuads`. `AptOresBakedModel` on Fabric no longer needs an `isVanillaAdapter()`-style guard at
all - see the `fabric` section above. **If a future Fabric API version reintroduces a similar
split, re-check this before assuming a plain `instanceof` test is safe.**

NeoForge's and Forge's models don't have an equivalent gotcha: their `BlockModelPart`/
`BlockStateModel` extension methods work directly off the vanilla interfaces (no Fabric-style
multi-material emitter), so `AptOresModel` on those loaders never had this class of bug.

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

Requires JDK 21. Targets Minecraft 1.21.9 (Fabric + NeoForge + Forge, via Architectury Loom).

```
./gradlew :fabric:runClient
./gradlew :neoforge:runClient
./gradlew :forge:runClient
```

each launches a dev client with the mod active. As of the 1.21.9 port, this has been verified by
a clean `gradlew clean build` across all three loaders but **not** yet manually confirmed
in-game via `runClient` - a clean compile does not guarantee the new API calls actually produce
the same visual result (see `docs/PORTING.md` §6). Do that check before relying on this section's
"both loaders have been manually verified" claim again.

## Deliberately not done / known limitations

- **No manual backdrop override.** Earlier design discussion considered keeping a
  right-click-to-set-backdrop interaction (like the old mod had); it was dropped in favor of a
  fully stateless design. "Adapting" now means literally "place the material you want touching
  the ore" - there's no way to give an ore a backdrop that isn't actually present next to it.
- **Item/inventory rendering always uses a stone backdrop** (the context-free `collectParts`/
  `getQuads` fallbacks use `Blocks.STONE.defaultBlockState()`) since there's no world/position
  available for an `ItemStack` in a hand or GUI slot. Same fallback the old mod used.
- **As of the 1.21.9 port, item/inventory rendering for the ore items themselves is not
  intercepted at all** on any loader. Through 1.21.4, block and item baking still shared enough
  machinery that replacing a block's baked model (via `ModifyBakingResult`) transparently also
  affected the block's auto-derived item model. 1.21.9 split block rendering (`BlockStateModel`)
  and item rendering (`ItemModel`, baked into a separate, eagerly-flattened `List<BakedQuad>`
  inside `BlockModelWrapper`) into two independent bake passes that both complete *before*
  `ModifyBakingResult`/`ModelEvent.ModifyBakingResult` fires, so mutating `blockStateModels()` no
  longer has any effect on `itemStackModels()`. Concretely: holding an adapted ore in your hand or
  looking at it in an inventory slot now shows the **plain vanilla item texture**, not the
  backdrop+overlay composite. Fixing this would mean also replacing entries in
  `bakingResult.itemStackModels()` for each real ore item id with a custom `ItemModel` (not just
  the existing overlay-model-pinning workaround, which only concerns the *overlay's own*
  placeholder id) - not attempted in this port; flagged here as a known regression for whoever
  picks it up next.
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
