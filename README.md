# Apt Ores

A Minecraft mod (Fabric + NeoForge + Forge, 1.21.1) that makes ore blocks visually blend into whatever
material is touching them. Break or place a neighboring block and the ore's appearance updates
on its own — no config, no right-clicking to set anything.

This is a purely client-side rendering effect: no blocks, items, or worldgen are added or
changed. Players and servers without the mod just see vanilla ore textures.

## Quickstart: adding your own ore

Apt Ores picks up new ore types from data files — no code changes or PR to this repo needed. To
make your mod or resource/data pack's ore blend into its surroundings, add a JSON file at
`assets/<your_namespace>/aptores/ore_types/<your_ore>.json`:

```json
{
  "blocks": [
    "yourmod:ruby_ore",
    { "block": "yourmod:deepslate_ruby_ore", "default_backdrop": "minecraft:deepslate" }
  ],
  "overlay_texture": "yourmod:block/overlay/ruby_ore_overlay"
}
```

- `blocks` — every block id this entry covers (normal + deepslate variant, etc). An entry is
  either a bare block id, or an object that also names that block's `default_backdrop` — the
  material it wears when *nothing* around it qualifies (it's exposed to air on all sides, say).
  Without one it falls back to plain stone, so give the deepslate variants
  `"minecraft:deepslate"`, nether ores `"minecraft:netherrack"`, and so on.
- `overlay_texture` — a cutout texture (transparent background, only the ore flecks opaque) drawn
  on top of the sampled backdrop.
- `overlay_model` (optional) — defaults to `<your_namespace>:block/overlay_<file name>`; only set
  this if you need a custom model instead of a plain `cube_all`.

<img src="common/src/main/resources/assets/aptores/textures/block/overlay/diamond_ore_overlay.png" width="128" height="128" alt="diamond ore cutout overlay" style="image-rendering: pixelated;">

One of the built-in cutout overlays  (diamond) shown as an example.

Drop the file (and the overlay texture) into your mod's resources or a resource pack, and as soon
as it's loaded Apt Ores will start compositing that ore's backdrop + overlay automatically.

## Development notes

See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for architecture details, extension points, and
known gotchas, and [`docs/PORTING.md`](docs/PORTING.md) for a general playbook when bumping this
project to a newer Minecraft version.
