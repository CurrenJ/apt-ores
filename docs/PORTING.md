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
| `forge_version` | `https://files.minecraftforge.net/net/minecraftforge/forge/index_<version>.html` - use the "Recommended" build if one is marked, else "Latest" |

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
