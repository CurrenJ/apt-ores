package grill24.aptores;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * One ore family Apt Ores knows how to intercept, loaded from a
 * {@code assets/<namespace>/aptores/ore_types/*.json} resource (see {@link OreTypeLoader}). This
 * mod never registers a block or item of its own - these ids exist purely so the model-loading
 * hooks (in the fabric/neoforge modules) know which vanilla or modded models to wrap.
 */
public record OreTypeDefinition(
    String name,
    List<Identifier> blockIds,
    List<Identifier> blockModelIds,
    Identifier overlayTexture,
    Identifier overlayModelId
) {
    public boolean isOreBlockId(Identifier blockId) {
        return blockIds.contains(blockId);
    }
}
