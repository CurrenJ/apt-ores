package grill24.aptores;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Picks the "backdrop" material an ore should render as, purely by looking at what's currently
 * touching it. No state is stored or synced anywhere - this is a stateless function of the
 * live world, called fresh every time the containing chunk section is (re)meshed. This mirrors
 * how connected-texture mods (e.g. Continuity) pick a texture variant from neighbor state at
 * mesh time, and it means the visual result is always in sync: break/place a neighbor block and
 * the ore's appearance updates the same tick, the same way glass-pane connections do.
 *
 * <p>When nothing around the ore qualifies (it's floating in air, or every neighbor is itself an
 * adapted ore), the ore falls back to the host material its ore-type JSON declares for that block
 * id - so a deepslate variant reads as deepslate instead of taking the plain-stone default.
 */
public final class BackdropSampler {
    public static final BlockState DEFAULT_BACKDROP = Blocks.STONE.defaultBlockState();

    private BackdropSampler() {
    }

    public static BlockState sample(BlockGetter level, BlockPos origin, BlockState oreState) {
        Map<BlockState, Integer> counts = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(origin, direction);
            BlockState neighbor = level.getBlockState(cursor);
            if (isValidBackdrop(level, cursor, neighbor)) {
                // Weight non-stone/deepslate backdrops higher so a single deliberately-placed
                // block (e.g. blackstone) wins over the plain stone the ore usually sits in.
                int weight = (neighbor.is(Blocks.STONE) || neighbor.is(Blocks.DEEPSLATE)) ? 1 : 4;
                counts.merge(neighbor, weight, Integer::sum);
            }
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseGet(() -> defaultBackdrop(oreState));
    }

    /**
     * The backdrop an ore wears when no neighbor qualifies: whatever its ore-type JSON declares
     * for that specific block id, else plain stone.
     */
    public static BlockState defaultBackdrop(BlockState oreState) {
        BlockState declared = OreTypeRegistry.defaultBackdropFor(oreState);
        return declared != null ? declared : DEFAULT_BACKDROP;
    }

    private static boolean isValidBackdrop(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.canOcclude()) {
            return false;
        }
        // canOcclude() is a static per-block flag, not a check of the actual shape - blocks like
        // snow layers don't disable it even though they usually don't fill the block space. Only
        // treat a neighbor as a backdrop if it actually occupies the full cube, otherwise partial
        // blocks (snow layers, slabs, carpets, stairs, etc.) get sampled as if they were a solid
        // wall and the ore renders wearing that block's texture, which looks wrong.
        if (!state.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // Don't let one ore's backdrop be "another ore" - that would render nonsensically since
        // the neighbor is itself being intercepted and rendered as a composite.
        if (OreTypeRegistry.isAdaptedOreBlockId(blockId)) {
            return false;
        }
        // Exclude grass and tinted blocks that shouldn't be used as backdrops
        if (isBlacklistedBlock(state)) {
            return false;
        }
        // Respect the optional client config whitelist (config/aptores.json) - if set, only
        // listed blocks/tags are eligible neighbors.
        return AptOresConfig.isWhitelisted(state);
    }

    private static boolean isBlacklistedBlock(BlockState state) {
        // Blocks with biome-dependent coloring or visual effects that shouldn't be used as ore backdrops
        return state.is(Blocks.GRASS_BLOCK) ||
               state.is(Blocks.MYCELIUM) ||
               state.is(Blocks.PODZOL) ||
               state.is(Blocks.CRIMSON_NYLIUM) ||
               state.is(Blocks.WARPED_NYLIUM);
    }
}
