package grill24.aptores.forge.client.model;

import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Vertex-manipulation helper for compositing baked quads from two independently-baked models. */
public final class QuadHelper {
    private QuadHelper() {
    }

    /**
     * Offsets a quad slightly outward along its face normal so the overlay layer doesn't
     * z-fight with the backdrop layer directly beneath it. Rebuilds the quad with translated
     * vertex positions, preserving the packed UVs and material info verbatim.
     */
    public static BakedQuad offsetQuad(BakedQuad quad, @Nullable Direction side, float offset) {
        Direction direction = side != null ? side : quad.direction();

        float offsetX = direction.getStepX() * offset;
        float offsetY = direction.getStepY() * offset;
        float offsetZ = direction.getStepZ() * offset;

        Vector3f[] positions = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            positions[i] = new Vector3f(p.x() + offsetX, p.y() + offsetY, p.z() + offsetZ);
        }

        return new BakedQuad(
            positions[0], positions[1], positions[2], positions[3],
            quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
            direction, quad.materialInfo());
    }
}
