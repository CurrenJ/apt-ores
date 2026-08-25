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

As of the 1.21.11 port, Mojang's own model-baking rework replaced the old per-block `BakedModel`
interface with a `BlockStateModel` (a stateless-per-reload object whose `collectParts(...)`
produces a list of `BlockModelPart`s, each holding its own `QuadCollection`/render layer) - see
"The `BakedModel` → `BlockStateModel` rework" below for why this changed every loader's
implementation at once. `QuadHelper` (offsetting a baked `QuadCollection` outward along its face
normal, to keep the overlay from z-fighting with the backdrop) is loader-agnostic under this new
API and now lives in `common`, shared by all three loaders - it did not before.

### `fabric`
- **`AptOresModelLoadingPlugin`** - registered from `AptOresFabricClient` (the mod's only
  entrypoint, `"client"` in `fabric.mod.json`). On every resource reload:
  1. `OreTypeRegistry.reload(OreTypeLoader.load(Minecraft.getInstance().getResourceManager()))`
     re-reads every `ore_types` JSON off the just-updated resource manager.
  2. `context.addModel(key, SimpleUnbakedExtraModel.blockStateModel(...))` pins each overlay
     model as an "extra model" (Fabric's replacement for the old `addModels(...)` id-list) so it
     gets loaded, baked, and its texture stitched into the block atlas even though no
     blockstate/item references it; the returned `ExtraModelKey<BlockStateModel>` is stashed in
     `OverlayModelRegistry`.
  3. `context.modifyBlockModelAfterBake().register(...)` gets the target block's `BlockState`
     directly (no more resource-id matching): if `OreTypeRegistry.byBlockId(...)` matches, the
     vanilla baked `BlockStateModel` is wrapped in a new `AptOresBlockStateModel`.
- **`AptOresBlockStateModel`** - extends Fabric's `WrapperBlockStateModel` and overrides
  `emitQuads(QuadEmitter, BlockAndTintGetter, BlockPos, BlockState, RandomSource, Predicate<Direction>)`
  (the `FabricBlockStateModel` contract, mixed onto every `BlockStateModel`): samples the
  backdrop, delegates straight to its `emitQuads` for the base layer, then emits the overlay's
  offset quads (via `QuadHelper.offset` on the overlay part's `QuadCollection`, if it's a
  `SimpleModelWrapper`) through the emitter. Unlike the pre-1.21.11 `FabricBakedModel`, the
  default `emitQuads` correctly delegates to `collectParts` for plain vanilla models, so the old
  "`isVanillaAdapter()` always true" trap (see git history) no longer applies - there is nothing
  loader-specific to check before delegating.
- **`OverlayModelRegistry`** - a tiny static `Map<OreTypeDefinition, ExtraModelKey<BlockStateModel>>`,
  reset and repopulated on every resource reload; resolves the baked overlay lazily via the
  mixed-in `FabricBakedModelManager.getModel(key)`, safe any time after baking completes.

### `neoforge`
- **`AptOresNeoForgeClient`** - the entire mod on this loader, across three `IEventBus` listeners
  registered in the constructor (NeoForge no longer needs a class-level `@Mod.EventBusSubscriber`
  for this):
  1. `ModelEvent.RegisterStandalone` (NeoForge's replacement for the removed
     `ModelEvent.RegisterAdditional`) reloads `OreTypeRegistry` and registers each overlay model
     under a `StandaloneModelKey<BlockStateModel>` via `SimpleUnbakedStandaloneModel.blockStateModel(...)`.
  2. `ModelEvent.ModifyBakingResult` gets the whole bake result as one mutable
     `Map<BlockState, BlockStateModel>` (keyed by `BlockState` directly now, not
     `ModelResourceLocation`) - every entry whose block id matches an `OreTypeDefinition` gets
     wrapped in a new `AptOresModel`.
  3. `ModelEvent.BakingCompleted` resolves every `StandaloneModelKey` against
     `event.getBakingResult().standaloneModels()` and caches the baked overlay `BlockStateModel`
     in `OverlayModelRegistry` for the render hot path.
- **`AptOresModel`** - extends NeoForge's `DelegateBlockStateModel` and implements
  `DynamicBlockStateModel`, whose position-aware
  `collectParts(BlockAndTintGetter, BlockPos, BlockState, RandomSource, List<BlockModelPart>)` is
  exactly the hook this mod needs (samples the backdrop, collects its parts first, then appends
  the overlay's parts rebuilt with `QuadHelper.offset` applied to each `SimpleModelWrapper`'s
  `QuadCollection`).

### `forge`
- Forge's `@Mod` annotation still has no `dist` parameter, so the mod is split into two classes:
  - **`AptOresForge`** - the required `@Mod(MOD_ID)` entry point. Loader-neutral, does nothing,
    exists only because Forge requires exactly one `@Mod`-annotated class per mod id.
  - **`AptOresForgeClient`** - everything else, gated with
    `@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)` so Forge
    never loads this class - and therefore never resolves its client-only imports - on a
    dedicated server. As of 1.21.11, Forge's own event bus was overhauled to typed
    `EventBus<T>`/`BUS`-field classes (`ModelEvent` is no longer an `IModBusEvent`); the
    `@SubscribeEvent` annotation still works exactly as before, just from a new package
    (`net.minecraftforge.eventbus.api.listener.SubscribeEvent`).
- **Regular Forge has no `RegisterAdditional`/`RegisterStandalone`-equivalent hook at all** - see
  "Forge has no way to pin an unreferenced model" below for how the overlay models still get
  baked without one.
- **`AptOresModel`** - implements `BlockStateModel` directly (Forge doesn't ship a
  `DelegateBlockStateModel` convenience class the way NeoForge does). Position-awareness goes
  through Forge's own `net.minecraftforge.client.model.data.ModelData`/`ModelProperty` system
  instead of a position-aware `collectParts` overload: `getModelData(level, pos, state, modelData)`
  samples the backdrop and stashes it in a `ModelProperty<BlockState>`, and the
  `IForgeBlockStateModel`-default `collectParts(RandomSource, List<BlockModelPart>, ModelData, ChunkSectionLayer)`
  reads it back out. `getRenderTypes` unions the backdrop's own render types with the overlay
  part's `layer()`.

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
  it and asks for the result via each loader's own extra/standalone-model mechanism, or - on
  Forge, which has neither - the item-model shadow trick described below).
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

### 2. Fabric: `instanceof FabricBakedModel` is always true - check `isVanillaAdapter()` instead (obsolete since 1.21.11)
This applied through the 1.21.4/26.2 ports, when Fabric's renderer API mixed `FabricBakedModel`
onto every `BakedModel` with an `isVanillaAdapter()` flag that defaulted to `true` and made a
naive `instanceof` check always pass, silently skipping real quad emission for plain vanilla
models (see prior git history for the "black background" bug this caused, and how it was fixed).
**As of the 1.21.11 model rework this class of bug no longer exists**: the Fabric equivalent
today, `FabricBlockStateModel#emitQuads`, is mixed onto every `BlockStateModel` with a *default
implementation that correctly delegates to `collectParts`* for plain vanilla models - there is no
"is this really Fabric-aware" flag to get wrong, and `AptOresBlockStateModel` in `fabric/` no
longer needs (or has) an `isVanillaAdapter`-style check before delegating to the backdrop model.

NeoForge's and Forge's models never had an equivalent gotcha either: their `collectParts` hooks
work directly off the vanilla `BlockStateModel`/`BlockModelPart` interfaces (no Fabric-style
multi-material emitter).

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

### 4. `ResourceLocation` was renamed to `Identifier` in 1.21.11
A straight, mechanical rename across every Mojang-mapped class in `common` (and everywhere else
that touched a resource id) - `net.minecraft.resources.ResourceLocation` is now
`net.minecraft.resources.Identifier`, same package, same API shape
(`Identifier.parse(...)`/`Identifier.fromNamespaceAndPath(...)`/`getNamespace()`/`getPath()` all
still exist under the new name). Confirmed via `javap` against the real mapped jar, not guessed -
see `docs/PORTING.md` §3. Grep for `ResourceLocation` in any future port; if it's gone, this is
why.

### 5. `BakedModel` → `BlockStateModel` rework, and how it changes every loader's hook
This is the single biggest breakage of the 1.21.11 port, and unlike the item-model rework in
1.21.4 (`docs/PORTING.md` §5, "`BakedModel` interface shrinking"), this one replaces the
per-block-state baked model type entirely: `net.minecraft.client.resources.model.BakedModel` (the
old `getQuads(state, side, random)`/`getRenderTypes(...)` interface every loader's composite
implemented) no longer exists for block rendering. In its place:
- **`net.minecraft.client.renderer.block.model.BlockStateModel`** - the new per-block-state
  vanilla type. Its only real contract is `collectParts(RandomSource, List<BlockModelPart>)` (plus
  `particleIcon()`); there is no `getQuads`/`getRenderTypes` on the base interface at all.
- **`net.minecraft.client.renderer.block.model.BlockModelPart`** - the new per-part geometry
  holder (`getQuads(Direction)`, `useAmbientOcclusion()`, `particleIcon()`). A plain baked cube
  model (like our overlay `cube_all` models) bakes down to a single
  **`SimpleModelWrapper`** record implementing `BlockModelPart`, whose `.quads()` accessor exposes
  the raw `net.minecraft.client.resources.model.QuadCollection` - this is exactly what
  `QuadHelper.offset(QuadCollection, float)` in `common` operates on, and why offsetting the
  overlay now means "extract the `SimpleModelWrapper`'s `QuadCollection`, offset it, build a new
  `SimpleModelWrapper`" rather than looping over individual `BakedQuad`s by hand as the pre-1.21.11
  code did.
- **Getting a block's model changed callsite, not just type**: the old
  `Minecraft.getInstance().getBlockRenderer().getBlockModel(state)` callsite is unchanged, it now
  just returns a `BlockStateModel` instead of a `BakedModel`.
- **Every loader needs a *different* hook for position-aware composition**, because plain vanilla
  `collectParts(RandomSource, List)` has no `BlockAndTintGetter`/`BlockPos` parameter (unlike the
  old `getQuads`, which at least took a `BlockState`). See the `fabric`/`neoforge`/`forge`
  Architecture subsections above for what each loader offers instead
  (`FabricBlockStateModel#emitQuads`, `DynamicBlockStateModel#collectParts`, and
  `ModelData`-mediated `collectParts` respectively) - **do not assume these three hooks are
  equivalent enough to write once and copy across loaders.**

### 6. Forge has no way to pin an unreferenced model, and its item-model shadow trick changed shape
Regular Forge still ships no `ModelEvent.RegisterAdditional` (confirmed again for 61.2.0 by
decompiling the real jar, same as the 1.21.4 port found), and as of the `BlockStateModel` rework
it *also* has no `standaloneModels()`-equivalent on `ModelBakery.BakingResult` the way NeoForge
does (`net.minecraftforge.client.event.ModelEvent.ModifyBakingResult` only exposes
`blockStateModels()`/`itemStackModels()`/`itemProperties()`). The 1.21.4-era workaround - shadow
each overlay model with a throwaway per-item client model JSON so vanilla's file-path-based item
model scanner picks it up regardless of whether a real item is registered - still works to get the
model *loaded and baked*, but **the resulting `BlockModelWrapper` (the `ItemModel` baked from a
`"minecraft:model"`-typed item definition) no longer exposes the wrapped model at all**: in
1.21.4 it had a public `model` field holding the real `BakedModel`; in 1.21.11 its fields
(including the flattened `quads: List<BakedQuad>`) are all private with no accessor. Forge's own
`IForgeBlockStateModel`/`IForgeBlockModelPart` extensions don't cover this case either.

`forge/.../client/AptOresForgeClient.extractQuads(...)` works around this with a small, narrowly-
scoped reflective read of `BlockModelWrapper`'s private `quads` field, then rebuilds an equivalent
`BlockModelPart` by bucketing the flattened quads back into a `QuadCollection` by
`BakedQuad.direction()` (safe for a plain `cube_all` bake, where every quad has a well-defined
face direction) and wrapping it in a `SimpleModelWrapper`. This is a genuine last-resort - if a
future Forge version closes off reflection too (or the field name changes), the deliberate design
choice from `docs/PORTING.md` §4 still applies: prefer a real hook (option 1) or a vanilla side
door (option 2) over a mixin, and reflection here is closer in spirit to a side door than a mixin,
but it is still worth re-checking whether Forge has grown a proper extension point before copying
this forward to the next port.

### 7. Forge's `SimpleModelWrapper` carries `layer`/`layerFast`, not NeoForge's/vanilla's single `renderType`
`SimpleModelWrapper` is a Mojang record, but Forge patches its own copy of it differently from
NeoForge's (and from plain vanilla/Fabric's): the constructor and record components are
`(QuadCollection, boolean useAmbientOcclusion, TextureAtlasSprite particleIcon, ChunkSectionLayer layer, ChunkSectionLayer layerFast)`
on Forge, versus `(..., ChunkSectionLayer renderType)` (a single trailing field) on NeoForge and
vanilla. Confirmed by `javap`-ing each loader's own merged jar independently - **do not assume the
NeoForge and Forge `AptOresModel`'s "rebuild a `SimpleModelWrapper` with an offset `QuadCollection`"
helper can share an implementation**; they call different constructors even though the surrounding
`collectParts` logic is otherwise nearly identical between the two.

### 8. Forge's event bus was overhauled to typed `EventBus<T>`/`BUS` fields
`net.minecraftforge.client.event.ModelEvent` (and apparently most other Forge-specific events) are
no longer `IModBusEvent`s posted through the mod's shared event bus; each event class is now a
`sealed interface`/record with its own `public static final EventBus<T> BUS` field, and the
javadoc on the old `getBus(BusGroup)` accessor is explicit: "no longer an `IModBusEvent`, use
`#BUS` directly." **The `@Mod.EventBusSubscriber` + `@SubscribeEvent` annotation pattern still
works exactly as before** - `AptOresForgeClient` didn't need to change its subscription style at
all - but `@SubscribeEvent` moved packages, from `net.minecraftforge.eventbus.api.SubscribeEvent`
to `net.minecraftforge.eventbus.api.listener.SubscribeEvent`. A stale import here fails with a
plain "package does not exist", not a hint that the whole event system changed shape underneath -
worth knowing before spending time on the wrong theory.

## Building

```
./gradlew build
```

Requires JDK 21. Targets Minecraft 1.21.11 (Fabric + NeoForge + Forge, via Architectury Loom).

```
./gradlew :fabric:runClient
./gradlew :neoforge:runClient
./gradlew :forge:runClient
```

each launches a dev client with the mod active. `./gradlew clean build` passes for all three
loaders as of this writing; in-game rendering has not been visually re-verified for the 1.21.11
port specifically (see `docs/PORTING.md` §6) - a clean compile confirms the new API calls resolve,
not that they produce the same visual result.

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

## Prior-version port learnings (carried forward)

### From 1.21.9 — item/inventory rendering of adapted ores is not intercepted (regression)
Since the 1.21.9 model rework, item models bake separately from block-state models, so the
composite model classes never see held/inventory ore items at all — they show the plain vanilla
item texture, not a backdrop+overlay composite. The "Item/inventory rendering always uses a stone
backdrop" limitation below is stale (a pre-1.21.9 description); the position-blind
`collectParts(RandomSource, ...)` fallback is only a context-free safety net, not the item path.
