package grill24.aptores;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * One ore family Apt Ores knows how to intercept, loaded from a
 * {@code assets/<namespace>/aptores/ore_types/*.json} resource (see {@link OreTypeLoader}). This
 * mod never registers a block or item of its own - these ids exist purely so the model-loading
 * hooks (in the fabric/neoforge modules) know which vanilla or modded models to wrap.
 *
 * <p>{@code defaultBackdrops} maps an ore block id to the block its backdrop falls back to when
 * none of its six neighbors qualifies (see {@link BackdropSampler}). It only needs an entry for
 * ore variants whose natural host isn't plain stone - the deepslate variants are the common case,
 * but the same mechanism covers nether/end ores. Ore block ids with no entry fall back to
 * {@link BackdropSampler#DEFAULT_BACKDROP}.
 */
public record OreTypeDefinition(
    String name,
    List<Identifier> blockIds,
    List<Identifier> blockModelIds,
    Map<Identifier, Identifier> defaultBackdrops,
    Identifier overlayTexture,
    Identifier overlayModelId
) {
    public boolean isOreBlockId(Identifier blockId) {
        return blockIds.contains(blockId);
    }
}
