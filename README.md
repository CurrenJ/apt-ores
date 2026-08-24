# Apt Ores

A Minecraft mod (Fabric + NeoForge, 1.21.1) that makes ore blocks visually blend into whatever
material is touching them. Break or place a neighboring block and the ore's appearance updates
on its own — no config, no right-clicking to set anything.

This is a purely client-side rendering effect: no blocks, items, or worldgen are added or
changed. Players and servers without the mod just see vanilla ore textures.

## Building

Requires JDK 21.

```
./gradlew build
./gradlew :fabric:runClient
./gradlew :neoforge:runClient
```

## Development notes

See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for architecture details, extension points, and
known gotchas.
