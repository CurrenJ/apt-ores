package grill24.aptores;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live, reloadable table of every {@link OreTypeDefinition} currently known, keyed for the fast
 * lookups the rendering hot path needs. Call {@link #reload(List)} once per client resource
 * reload (see {@link OreTypeLoader}) before consulting any of the lookups below.
 */
public final class OreTypeRegistry {
    private static List<OreTypeDefinition> all = List.of();
    private static Map<Identifier, OreTypeDefinition> byBlockId = Map.of();
    private static Map<Identifier, OreTypeDefinition> byBlockModelId = Map.of();
    /** Resolved once per reload so the render fallback path stays a plain map lookup. */
    private static Map<Block, BlockState> defaultBackdropByOreBlock = Map.of();

    private OreTypeRegistry() {
    }

    public static void reload(List<OreTypeDefinition> definitions) {
        Map<Identifier, OreTypeDefinition> newByBlockId = new HashMap<>();
        Map<Identifier, OreTypeDefinition> newByBlockModelId = new HashMap<>();

        for (OreTypeDefinition definition : definitions) {
            for (Identifier blockId : definition.blockIds()) {
                OreTypeDefinition existing = newByBlockId.putIfAbsent(blockId, definition);
                if (existing != null) {
                    AptOres.LOGGER.warn("Apt Ores: ore type {} claims block {} already claimed by {}; keeping {}",
                        definition.name(), blockId, existing.name(), existing.name());
                }
            }
            for (Identifier blockModelId : definition.blockModelIds()) {
                newByBlockModelId.putIfAbsent(blockModelId, definition);
            }
        }

        all = List.copyOf(definitions);
        byBlockId = Map.copyOf(newByBlockId);
        byBlockModelId = Map.copyOf(newByBlockModelId);
        defaultBackdropByOreBlock = resolveDefaultBackdrops(definitions);

        AptOres.LOGGER.info("Apt Ores: loaded {} ore type(s)", all.size());
    }

    public static Collection<OreTypeDefinition> all() {
        return all;
    }

    public static OreTypeDefinition byBlockId(Identifier blockId) {
        return blockId == null ? null : byBlockId.get(blockId);
    }

    public static OreTypeDefinition byBlockModelId(Identifier modelId) {
        return modelId == null ? null : byBlockModelId.get(modelId);
    }

    public static boolean isAdaptedOreBlockId(Identifier blockId) {
        return blockId != null && byBlockId.containsKey(blockId);
    }

    /**
     * The backdrop this ore block falls back to when none of its neighbors qualifies, or
     * {@code null} if it declares none (callers then use {@link BackdropSampler#DEFAULT_BACKDROP}).
     */
    public static BlockState defaultBackdropFor(BlockState oreState) {
        return defaultBackdropByOreBlock.get(oreState.getBlock());
    }

    private static Map<Block, BlockState> resolveDefaultBackdrops(List<OreTypeDefinition> definitions) {
        // Reload runs during model baking, long after the block registry is frozen, so the ids can
        // be resolved to real blocks once here instead of on every mesh rebuild.
        Map<Block, BlockState> resolved = new HashMap<>();

        for (OreTypeDefinition definition : definitions) {
            for (Map.Entry<Identifier, Identifier> entry : definition.defaultBackdrops().entrySet()) {
                Block oreBlock = BuiltInRegistries.BLOCK.getOptional(entry.getKey()).orElse(null);
                Block backdropBlock = BuiltInRegistries.BLOCK.getOptional(entry.getValue()).orElse(null);
                if (oreBlock == null || backdropBlock == null) {
                    // Not an error: ore types can ship for mods that aren't installed.
                    AptOres.LOGGER.debug("Apt Ores: ore type {} declares default backdrop {} -> {}; not present, ignoring",
                        definition.name(), entry.getKey(), entry.getValue());
                    continue;
                }
                resolved.put(oreBlock, backdropBlock.defaultBlockState());
            }
        }

        return Map.copyOf(resolved);
    }
}
