package grill24.aptores;

import net.minecraft.resources.ResourceLocation;

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
    private static Map<ResourceLocation, OreTypeDefinition> byBlockId = Map.of();
    private static Map<ResourceLocation, OreTypeDefinition> byBlockModelId = Map.of();

    private OreTypeRegistry() {
    }

    public static void reload(List<OreTypeDefinition> definitions) {
        Map<ResourceLocation, OreTypeDefinition> newByBlockId = new HashMap<>();
        Map<ResourceLocation, OreTypeDefinition> newByBlockModelId = new HashMap<>();

        for (OreTypeDefinition definition : definitions) {
            for (ResourceLocation blockId : definition.blockIds()) {
                OreTypeDefinition existing = newByBlockId.putIfAbsent(blockId, definition);
                if (existing != null) {
                    AptOres.LOGGER.warn("Apt Ores: ore type {} claims block {} already claimed by {}; keeping {}",
                        definition.name(), blockId, existing.name(), existing.name());
                }
            }
            for (ResourceLocation blockModelId : definition.blockModelIds()) {
                newByBlockModelId.putIfAbsent(blockModelId, definition);
            }
        }

        all = List.copyOf(definitions);
        byBlockId = Map.copyOf(newByBlockId);
        byBlockModelId = Map.copyOf(newByBlockModelId);

        AptOres.LOGGER.info("Apt Ores: loaded {} ore type(s)", all.size());
    }

    public static Collection<OreTypeDefinition> all() {
        return all;
    }

    public static OreTypeDefinition byBlockId(ResourceLocation blockId) {
        return blockId == null ? null : byBlockId.get(blockId);
    }

    public static OreTypeDefinition byBlockModelId(ResourceLocation modelId) {
        return modelId == null ? null : byBlockModelId.get(modelId);
    }

    public static boolean isAdaptedOreBlockId(ResourceLocation blockId) {
        return blockId != null && byBlockId.containsKey(blockId);
    }
}
