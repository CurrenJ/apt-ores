package grill24.aptores;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code assets/<namespace>/aptores/ore_types/*.json} resources and turns them into
 * {@link OreTypeDefinition}s. This is the extension point third-party mods/packs use to add ore
 * support: a JSON file naming the vanilla-or-modded block ids to intercept plus the cutout
 * overlay texture, no Java code required.
 *
 * <p>Deliberately reads straight off {@link ResourceManager} rather than registering as a
 * {@code SimpleJsonResourceReloadListener}: this mod is purely a client rendering effect, so
 * these definitions only ever need to be fresh by the time model baking runs, and both loaders'
 * model-baking hooks already fire on every client resource reload with an up-to-date resource
 * manager available.
 */
public final class OreTypeLoader {
    private static final String DIRECTORY = "aptores/ore_types";
    private static final String DIRECTORY_PREFIX = DIRECTORY + "/";
    private static final String JSON_SUFFIX = ".json";

    private OreTypeLoader() {
    }

    public static List<OreTypeDefinition> load(ResourceManager resourceManager) {
        List<OreTypeDefinition> definitions = new ArrayList<>();

        for (Map.Entry<ResourceLocation, Resource> entry :
                resourceManager.listResources(DIRECTORY, id -> id.getPath().endsWith(JSON_SUFFIX)).entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = GsonHelper.parse(reader);
                definitions.add(parse(fileId, json));
            } catch (IOException | RuntimeException e) {
                AptOres.LOGGER.error("Apt Ores: failed to load ore type {}", fileId, e);
            }
        }

        return definitions;
    }

    private static OreTypeDefinition parse(ResourceLocation fileId, JsonObject json) {
        String namespace = fileId.getNamespace();
        String name = fileId.getPath().substring(DIRECTORY_PREFIX.length(), fileId.getPath().length() - JSON_SUFFIX.length());

        List<ResourceLocation> blockIds = new ArrayList<>();
        JsonArray blocks = GsonHelper.getAsJsonArray(json, "blocks");
        for (int i = 0; i < blocks.size(); i++) {
            blockIds.add(ResourceLocation.parse(blocks.get(i).getAsString()));
        }
        if (blockIds.isEmpty()) {
            throw new IllegalArgumentException("\"blocks\" must list at least one block id");
        }

        List<ResourceLocation> blockModelIds = blockIds.stream()
            .map(id -> ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "block/" + id.getPath()))
            .toList();

        ResourceLocation overlayTexture = ResourceLocation.parse(GsonHelper.getAsString(json, "overlay_texture"));

        ResourceLocation overlayModelId = json.has("overlay_model")
            ? ResourceLocation.parse(GsonHelper.getAsString(json, "overlay_model"))
            : ResourceLocation.fromNamespaceAndPath(namespace, "block/overlay_" + name);

        return new OreTypeDefinition(namespace + ":" + name, blockIds, blockModelIds, overlayTexture, overlayModelId);
    }
}
