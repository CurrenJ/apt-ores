package grill24.aptores;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Vertex-manipulation helper for compositing baked quads from two independently-baked models.
 * Shared across all three loaders: {@link BakedQuad} and {@link QuadCollection} are plain
 * Mojang-mapped vanilla types with no loader-specific extensions needed for this.
 */
public final class QuadHelper {
    private QuadHelper() {
    }

    /**
     * Returns a copy of {@code quads} with every quad offset slightly outward along its face
     * normal, so an overlay layer built from it doesn't z-fight with whatever's directly beneath.
     */
    public static QuadCollection offset(QuadCollection quads, float offset) {
        QuadCollection.Builder builder = new QuadCollection.Builder();

        for (Direction direction : Direction.values()) {
            for (BakedQuad quad : quads.getQuads(direction)) {
                builder.addCulledFace(direction, offsetQuad(quad, direction, offset));
            }
        }
        for (BakedQuad quad : quads.getQuads(null)) {
            builder.addUnculledFace(offsetQuad(quad, quad.direction(), offset));
        }

        return builder.build();
    }

    private static BakedQuad offsetQuad(BakedQuad quad, @Nullable Direction side, float offset) {
        Direction direction = side != null ? side : quad.direction();

        Vector3f delta = new Vector3f();
        if (direction != null) {
            delta.set(direction.getStepX() * offset, direction.getStepY() * offset, direction.getStepZ() * offset);
        }

        return new BakedQuad(
            new Vector3f(quad.position0()).add(delta),
            new Vector3f(quad.position1()).add(delta),
            new Vector3f(quad.position2()).add(delta),
            new Vector3f(quad.position3()).add(delta),
            quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
            quad.tintIndex(), quad.direction(), quad.sprite(), quad.shade(), quad.lightEmission()
        );
    }
}
