# Porting Apt Ores to a new Minecraft version

A general-purpose playbook for bumping this project (or any small Architectury-based,
client-rendering-only mod) to a newer Minecraft release. Written after the 1.21.1 → 1.21.4 port;
update this file with whatever the *next* port teaches you.

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
| `forge_version` | `https://files.minecraftforge.net/net/minecraftforge/forge/index_<version>.html` - use the "Recommended" build if one is marked, else "Latest". **Don't trust "Latest" blindly**: on the 1.21.6 port, the newest `56.0.9` build produced a corrupted merged Minecraft+Forge jar (`:forge:compileJava` failing with ~50 nonsensical `cannot find symbol` errors) while the second-newest `56.0.8` build compiled cleanly - see §8.2 |

Also check whether `dev.architectury.loom` / `architectury-plugin` (root `build.gradle`) need a
version bump for the target MC version - Loom versions aren't always forward-compatible with
new MC releases, and old Loom versions sometimes have dev-launch bugs on newer MC that only get
fixed on a newer Loom line (see `docs/DEVELOPMENT.md` §3 for a concrete precedent). Check
`architectury-loom`'s GitHub releases/issues if `runClient`/build behaves strangely after
bumping everything else.

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

## 5. Recurring categories of breakage to expect (grounded in the 1.21.1→1.21.4 diff)

These are the specific things that broke last time, listed as *categories* so you know what
class of change to look for even if the exact API differs again next time:

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

## 8. Findings from the 1.21.4 → 1.21.6 port

Fabric and NeoForge ported cleanly (real API rewrite, but no new *category* of problem beyond
what §5 already predicted). Regular Forge did not - see "Known blocker" below.

### 8.1 The `BlockStateModel`/`DynamicBlockStateModel` rewrite (all three loaders)

1.21.6 replaced `BakedModel` with `BlockStateModel` for block rendering (`BakedModel` still
exists for items only, via `ItemModel`/`BlockModelWrapper`). Concretely, per loader:

- **Fabric**: `AptOresBakedModel` now implements `BlockStateModel` + `FabricBlockStateModel`
  (not `BakedModel`/`FabricBakedModel`). `emitBlockQuads`/`emitItemQuads` collapsed into one
  `emitQuads(emitter, blockView, pos, state, random, cullTest)` - items no longer render through
  this class at all (see below). Pinning a standalone/extra model changed from
  `context.addModels(List<ResourceLocation>)` + `context.modifyModelAfterBake()` to
  `context.addModel(ExtraModelKey, SimpleUnbakedExtraModel.blockStateModel(id))` +
  `context.modifyBlockModelAfterBake()` (now keyed by `BlockState`, not `ResourceLocation`) - and
  the baked extra model is retrieved later via `((FabricBakedModelManager)
  Minecraft.getInstance().getModelManager()).getModel(key)`, not stashed by the bake callback.
  Compositing quads from another model no longer needs manual `BakedQuad` vertex-offset surgery -
  FRAPI gained `emitter.pushTransform(QuadTransform)`/`popTransform()`, which is strictly better
  (works for both vanilla-fallback and FRAPI-native source models uniformly), so
  `fabric/.../model/QuadHelper.java` was deleted outright, unlike the 1.21.4 port where it was
  rewritten rather than deleted - **this is now the second port in a row where Fabric's own
  compositing primitive changed shape; don't assume it'll still be `QuadHelper.offsetQuad` next
  time either.**
- **NeoForge**: gained a purpose-built `DynamicBlockStateModel` interface whose `collectParts`
  takes `BlockAndTintGetter`/`BlockPos` directly - the old `ModelProperty<BlockState>`/
  `ModelData` indirection (sample backdrop in `getModelData`, read it back in `getQuads`) is gone
  entirely on this loader. Pinning a standalone model changed from
  `event.register(ResourceLocation)` (via `ModelEvent.RegisterAdditional`) to a typed
  `StandaloneModelKey<BlockStateModel>` registered via the renamed `ModelEvent.RegisterStandalone`
  event, and retrieved from the new `bakingResult.standaloneModels()` map keyed by that key object
  (not by `ResourceLocation` - a real behavior change, not just a rename).
- **Regular Forge**: kept the older `ModelData`/`ModelProperty` + `IForgeBlockStateModel` (a
  mixed-in extension interface on `BlockStateModel`) approach rather than gaining NeoForge's
  `DynamicBlockStateModel` - **the two forks' `BlockStateModel` extension points have now
  genuinely diverged**, not just been renamed in parallel. See §8.2 for the bigger Forge-only
  problem this port surfaced.

Across all three loaders, `BakedQuad` gained a 7th constructor param (`ambientOcclusion`, with
the old 6-arg constructor kept as an overload) and its accessors are now record-style
(`quad.direction()`/`quad.vertices()`/`quad.tintIndex()`/`quad.sprite()`/`quad.shade()`/
`quad.lightEmission()`, not `getDirection()`/`getVertices()`/etc.) - mechanical but easy to miss
since the old getter names simply don't exist anymore (not deprecated first).

Item/inventory rendering for a swapped block is now **entirely unaffected** by these composite
model classes on every loader - as of 1.21.6, item models are baked completely separately from
block-state models (`BlockModelWrapper`/`ItemModel`), so there's no `emitItemQuads`/item-context
fallback path to maintain at all anymore. This simplified all three loaders' composite classes
noticeably versus the 1.21.4 versions.

### 8.2 Known blocker: regular Forge 1.21.6 needs a new pin-a-standalone-model technique, AND its compiled jar is corrupted by the current Loom snapshot

**Part A - solved.** Regular (Minecraft)Forge 1.21.6 still has no `RegisterAdditional`/
`RegisterStandalone`-equivalent event (confirmed by decompiling the real
`forge-1.21.6-56.0.9-userdev`/sources jar - nothing named `standalone` or `RegisterAdditional`
anywhere in `net.minecraftforge.client.event.ModelEvent`). Worse, the 1.21.4-era workaround (shadow
each overlay with a throwaway per-item model JSON, then unwrap `BlockModelWrapper`'s public
`model` field from `BakingResult.itemStackModels()`) **no longer works either** - decompiling
`BlockModelWrapper` for 1.21.6 shows its baked quads are now stored in a `private final
List<BakedQuad> quads` field with no public accessor at all.

The fix used in this port: `ModelEvent.RegisterModelStateDefinitions` - a genuinely new 1.21.6
event, explicitly documented as being "designed to allow for extra models to be loaded in
connection with a blockstates json file" for a `StateDefinition` that isn't backed by a real
registered `Block`. A plain `new Block(BlockBehaviour.Properties.of())` (never registered, never
placed) is enough to obtain a `StateDefinition`/`BlockState` pair; register it under a synthetic
id, ship a matching `assets/aptores/blockstates/overlay_*.json` (`{"variants": {"": {"model":
"aptores:block/overlay_*"}}}`), and the overlay ends up in the normal
`BakingResult.blockStateModels()` map exactly like a real block, keyed by that synthetic
`BlockState`. **Caveat**: this only works for overlay models this repo ships a blockstate JSON
for - a third-party ore type added purely via an `ore_types` JSON (no Java/PR needed on Fabric/
NeoForge) needs to *also* ship a blockstate JSON to get its overlay baked on regular Forge. This
is a real, Forge-only gap in the plug-in contract - same class of problem as the 1.21.4-era
item-JSON workaround had, just moved to a different asset type.

**Part B - unresolved, a genuine tooling bug, not an API-porting problem.** Once the above was
implemented, `:forge:compileJava` produced ~50 unrelated-looking errors (`cannot find symbol` for
methods that definitely exist, `package X does not exist` for packages that definitely exist,
`class X is not public` for classes that are definitely `public`). Decompiling the exact resolved
dependency jar (per §3's technique) showed why: the actual jar Gradle resolves onto
`:forge:compileJava`'s classpath (`net.minecraft:forge-1.21.6-56.0.9-minecraft-merged:...`, found
via `./gradlew :forge:dependencies --configuration compileClasspath`) has **corrupted class
bytecode** - e.g. `net/minecraft/resources/ResourceLocation.class` in that jar decompiles (via
`javap`) as `public class ResourceLocation extends net.minecraft.server.Bootstrap` with a field
named `b` of type `Logger`, and `BakedQuad.class` decompiles as extending
`ItemPickupParticleGroup<CamelAi>` - nonsense superclass/field assignments, consistent with a
class-name collision/corruption bug in whatever Architectury Loom's final Forge-jar-merge step
does for this MC version (an *intermediate* jar in the same Gradle cache,
`forge-1.21.6-56.0.9-minecraft-merged-mojang`, has structurally sane classes with the right
fields/superclasses but not-yet-friendly SRG-style method names - so the corruption is
specifically introduced by the last merge/rename step, not earlier in the pipeline).

This reproduced identically after: fully deleting every `forge-1.21.6-56.0.9-minecraft-merged*`
cache directory and the `fabric-loom/1.21.6/forge/` working directory and letting Gradle
regenerate them from scratch (~53s), and after `./gradlew --stop` + a from-cold `--no-daemon`
run. Both rule out stale-daemon-memory or on-disk-cache corruption as the cause - **this is a
deterministic bug in Architectury Loom `1.17-SNAPSHOT` (build `1.17.491` at the time of this
port) merging regular Forge `56.0.9` for MC `1.21.6` specifically**, not something fixable from
this repo's side. (Both `forge_version` and `neoforge_version` for 1.21.6 are pinned as `-beta`
releases at the time of this port, and a GitHub code search of `architectury/architectury-loom`
found zero existing issues/PRs mentioning `1.21.6` at all - consistent with this being a very
recently exposed, not-yet-reported edge case rather than something with a known workaround.)

**If you hit this again**: don't re-derive it from scratch - check
`./gradlew :forge:dependencies --configuration compileClasspath` for the exact resolved
`net.minecraft:...-minecraft-merged:...` artifact, decompile a well-known class from it (anything
in `net.minecraft.resources` or `net.minecraft.world.level.block` is a fast smoke test), and
compare against the `-mojang`-suffixed sibling jar in the same
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` directory. If the plain
`-minecraft-merged` one is structurally garbled and the `-mojang` one isn't, this is the same bug
- check whether a newer Architectury Loom build has fixed it before spending more time on it.
`fabric` and `neoforge` were not affected (their equivalent merged jars decompile cleanly), so
this is Forge-patch-merge-specific, not a general Loom/MC-1.21.6 problem.

**Part B - resolved (follow-up session).** The corruption is **not** an Architectury Loom bug at
all, and pinning the plugin to the (by-then-released) stable `1.17.491` instead of the floating
`1.17-SNAPSHOT` made no difference - the merged jar was byte-for-byte the same kind of garbage
(`ResourceLocation.class` still decompiled as `extends net.minecraft.server.Bootstrap`). The
actual cause is specific to the **Forge `56.0.9` patch/rename build for MC 1.21.6**: bisecting
`forge_version` across every available `1.21.6-56.0.x` build on
`https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml` (`56.0.0` through
`56.0.9`) showed `56.0.0`, `56.0.5`, and `56.0.8` all merge and compile cleanly, while `56.0.9`
alone reproduces the corrupted-class symptom every time (confirmed by toggling back and forth
between `56.0.8`/`56.0.9` twice, deleting the relevant
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/forge-1.21.6-56.0.9-minecraft-merged*`
cache dirs between attempts). A telling clue surfaced during the `56.0.0` build's TinyRemapper
step, absent from later builds' output: several "Mapping source name conflicts detected" warnings
where two or more obfuscated classes (`akm`/`akn`/`ako`/`akp`, plus
`net/minecraftforge/network/ForgePayload`) all map a method literally named `a` with the same
descriptor to colliding target names (`method_52295`/`method_52296` etc.), auto-"fixed" by
TinyRemapper picking one arbitrarily. This is consistent with Forge's binary patches for `56.0.9`
being built against a slightly different anonymous-inner-class enumeration than the vanilla jar
Loom is patching, so a patch meant for one anonymous class (e.g. some `Bootstrap` inner class)
gets applied to - and renamed as - a different, wrong target class (here, `ResourceLocation`).
`56.0.9` was Forge's newest 1.21.6 build at port time but was evidently never actually clean;
`56.0.8` (the second-newest) has no such issue and is the pin this port shipped with.
`neoforge_version` was untouched - only regular Forge showed this.

**Takeaway for future ports**: when `forge_version`'s "Latest"/"Recommended" build produces this
exact corrupted-merged-jar symptom, don't assume it's a Loom/tooling dead end - **bisect
`forge_version` across nearby builds for the same MC version first**, it's cheap (a `sed` +
`./gradlew :forge:compileJava` per candidate, no full clean needed) and was the actual fix here.

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
