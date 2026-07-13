package com.foldworks.client.model;

import com.foldworks.Foldworks;
import com.foldworks.block.AbstractFoldworksBlock;
import com.foldworks.blockentity.RelativeSide;
import com.foldworks.blockentity.ResourceKind;
import com.foldworks.blockentity.SideMode;
import com.foldworks.compat.create.CreateCompat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class FoldworksChestBakedModel extends BakedModelWrapper<BakedModel> {
    private static final float MODULE_MIN = 2.0f;
    private static final float MODULE_MAX = 14.0f;
    private static final float SURFACE_OFFSET = 0.02f;
    private static final FaceBakery FACE_BAKERY = new FaceBakery();

    private final Map<Direction, Map<ResourceKind, Map<SideMode, List<BakedQuad>>>> moduleQuads;

    public FoldworksChestBakedModel(BakedModel originalModel, Function<Material, TextureAtlasSprite> textureGetter) {
        super(originalModel);
        TextureAtlasSprite item = sprite(textureGetter, "chest_item_port");
        TextureAtlasSprite fluid = sprite(textureGetter, "chest_fluid_window");
        TextureAtlasSprite energy = sprite(textureGetter, "chest_energy_core");
        TextureAtlasSprite bearing = sprite(textureGetter, "chest_bearing_ring");

        EnumMap<Direction, Map<ResourceKind, Map<SideMode, List<BakedQuad>>>> byDirection = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            TextureAtlasSprite base = sprite(textureGetter, direction == Direction.UP || direction == Direction.DOWN ? "chest_face_cap" : "chest_face_side");
            EnumMap<ResourceKind, Map<SideMode, List<BakedQuad>>> byKind = new EnumMap<>(ResourceKind.class);
            byKind.put(ResourceKind.ITEM, Map.of(
                    SideMode.INPUT, bake(direction, item, false),
                    SideMode.OUTPUT, bake(direction, item, true),
                    SideMode.BOTH, bake(direction, item, false)
            ));
            byKind.put(ResourceKind.FLUID, allModes(bake(direction, fluid, false)));
            byKind.put(ResourceKind.ENERGY, allModes(bake(direction, energy, false)));
            byKind.put(ResourceKind.STRESS, allModes(bakeStress(direction, base, bearing)));
            byDirection.put(direction, Map.copyOf(byKind));
        }
        moduleQuads = Map.copyOf(byDirection);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData extraData, @Nullable RenderType renderType) {
        List<Direction> stressSides = stressSidesFor(state, extraData);

        // 按 Direction 查询（cullface pass）：返回基础几何，stress 面被移除
        if (side != null) {
            if (stressSides.contains(side)) return List.of();
            return originalModel.getQuads(state, side, rand, extraData, renderType);
        }

        // 通用查询（renderType == null，如物品栏渲染）：返回 base + modules
        if (renderType == null) {
            List<BakedQuad> base = filterStressFaces(originalModel.getQuads(state, null, rand, extraData, null), stressSides);
            List<BakedQuad> modules = moduleQuadsFor(state, extraData);
            if (modules.isEmpty()) return base;
            List<BakedQuad> combined = new ArrayList<>(base.size() + modules.size());
            combined.addAll(base);
            combined.addAll(modules);
            return combined;
        }

        // cutout pass：仅返回模块几何
        if (RenderType.cutout().equals(renderType)) {
            return moduleQuadsFor(state, extraData);
        }

        // 其他 render type pass（solid 等）：返回过滤掉 stress 面的基础几何
        return filterStressFaces(originalModel.getQuads(state, null, rand, extraData, renderType), stressSides);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return ChunkRenderTypeSet.union(originalModel.getRenderTypes(state, rand, data), ChunkRenderTypeSet.of(RenderType.cutout()));
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        return modelData == null ? ModelData.EMPTY : modelData;
    }

    private List<BakedQuad> moduleQuadsFor(@Nullable BlockState state, ModelData extraData) {
        if (state == null || extraData == null || !extraData.has(FoldworksChestModelData.FACE_CONFIG)) return List.of();
        FoldworksChestModelData.FaceConfig config = extraData.get(FoldworksChestModelData.FACE_CONFIG);
        if (config == null || config.faces().isEmpty()) return List.of();

        Direction front = state.hasProperty(AbstractFoldworksBlock.FACING)
                ? state.getValue(AbstractFoldworksBlock.FACING)
                : Direction.NORTH;
        List<BakedQuad> quads = new ArrayList<>();
        for (RelativeSide relativeSide : RelativeSide.values()) {
            FoldworksChestModelData.FaceState face = config.get(relativeSide);
            if (face == null || face.mode() == SideMode.DISABLED) continue;
            if (!CreateCompat.isCreateLoaded() && (face.kind() == ResourceKind.FLUID || face.kind() == ResourceKind.STRESS)) continue;
            Direction worldSide = relativeSide.toWorld(front);
            quads.addAll(moduleQuads.get(worldSide).get(face.kind()).getOrDefault(face.mode(), List.of()));
        }
        return quads;
    }

    private List<Direction> stressSidesFor(@Nullable BlockState state, ModelData extraData) {
        if (!CreateCompat.isCreateLoaded()) return List.of();
        if (state == null || extraData == null || !extraData.has(FoldworksChestModelData.FACE_CONFIG)) return List.of();
        FoldworksChestModelData.FaceConfig config = extraData.get(FoldworksChestModelData.FACE_CONFIG);
        if (config == null || config.faces().isEmpty()) return List.of();

        Direction front = state.hasProperty(AbstractFoldworksBlock.FACING)
                ? state.getValue(AbstractFoldworksBlock.FACING)
                : Direction.NORTH;
        List<Direction> sides = new ArrayList<>();
        for (RelativeSide relativeSide : RelativeSide.values()) {
            FoldworksChestModelData.FaceState face = config.get(relativeSide);
            if (face == null || face.mode() == SideMode.DISABLED || face.kind() != ResourceKind.STRESS) continue;
            sides.add(relativeSide.toWorld(front));
        }
        return sides;
    }

    private List<BakedQuad> filterStressFaces(List<BakedQuad> quads, List<Direction> stressSides) {
        if (stressSides.isEmpty() || quads.isEmpty()) return quads;
        List<BakedQuad> filtered = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (!stressSides.contains(quad.getDirection())) filtered.add(quad);
        }
        return filtered;
    }

    private static Map<SideMode, List<BakedQuad>> allModes(List<BakedQuad> quads) {
        return Map.of(
                SideMode.INPUT, quads,
                SideMode.OUTPUT, quads,
                SideMode.BOTH, quads
        );
    }

    private static TextureAtlasSprite sprite(Function<Material, TextureAtlasSprite> textureGetter, String texture) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "block/" + texture);
        return textureGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, location));
    }

    private static List<BakedQuad> bake(Direction direction, TextureAtlasSprite sprite, boolean flipV) {
        Vector3f from = moduleFrom(direction);
        Vector3f to = moduleTo(direction);
        float[] uv = flipV ? new float[]{0, 16, 16, 0} : new float[]{0, 0, 16, 16};
        BlockElementFace face = new BlockElementFace(null, BlockElementFace.NO_TINT, "", new BlockFaceUV(uv, 0));
        BakedQuad quad = FACE_BAKERY.bakeQuad(from, to, face, sprite, direction, BlockModelRotation.X0_Y0, null, true);
        return List.of(quad);
    }

    private static List<BakedQuad> bakeStress(Direction direction, TextureAtlasSprite base, TextureAtlasSprite bearing) {
        List<BakedQuad> quads = new ArrayList<>();
        addLocalFace(quads, direction, base, 0, 14, 0, 16, 16, 0, 0, 0, 1, new float[]{0, 0, 16, 2});
        addLocalFace(quads, direction, base, 0, 0, 0, 16, 2, 0, 0, 0, 1, new float[]{0, 14, 16, 16});
        addLocalFace(quads, direction, base, 0, 2, 0, 2, 14, 0, 0, 0, 1, new float[]{0, 2, 2, 14});
        addLocalFace(quads, direction, base, 14, 2, 0, 16, 14, 0, 0, 0, 1, new float[]{14, 2, 16, 14});

        addLocalFace(quads, direction, bearing, 2, 2, -1, 14, 14, -1, 0, 0, 1, new float[]{0, 0, 16, 16});
        addLocalFace(quads, direction, bearing, 2, 2, 0, 2, 14, -1, 1, 0, 0, new float[]{0, 2, 1, 14});
        addLocalFace(quads, direction, bearing, 14, 2, -1, 14, 14, 0, -1, 0, 0, new float[]{15, 2, 16, 14});
        addLocalFace(quads, direction, bearing, 2, 2, -1, 14, 2, 0, 0, 1, 0, new float[]{2, 15, 14, 16});
        addLocalFace(quads, direction, bearing, 2, 14, 0, 14, 14, -1, 0, -1, 0, new float[]{2, 0, 14, 1});
        return quads;
    }

    private static void addLocalFace(List<BakedQuad> quads, Direction direction, TextureAtlasSprite sprite,
                                     float x0, float y0, float z0, float x1, float y1, float z1,
                                     float nx, float ny, float nz, float[] uv) {
        Vector3f a = localToWorld(direction, x0, y0, z0);
        Vector3f b = localToWorld(direction, x1, y1, z1);
        Vector3f normal = localVectorToWorld(direction, nx, ny, nz);
        Direction faceDirection = directionFromVector(normal);
        Vector3f from = new Vector3f(
                Math.min(a.x(), b.x()),
                Math.min(a.y(), b.y()),
                Math.min(a.z(), b.z())
        );
        Vector3f to = new Vector3f(
                Math.max(a.x(), b.x()),
                Math.max(a.y(), b.y()),
                Math.max(a.z(), b.z())
        );
        BlockElementFace face = new BlockElementFace(null, BlockElementFace.NO_TINT, "", new BlockFaceUV(uv, 0));
        quads.add(FACE_BAKERY.bakeQuad(from, to, face, sprite, faceDirection, BlockModelRotation.X0_Y0, null, true));
    }

    private static Vector3f localToWorld(Direction direction, float x, float y, float z) {
        Vector3f origin = switch (direction) {
            case SOUTH -> new Vector3f(0, 0, 16);
            case NORTH -> new Vector3f(16, 0, 0);
            case EAST -> new Vector3f(16, 0, 16);
            case WEST -> new Vector3f(0, 0, 0);
            case UP -> new Vector3f(0, 16, 16);
            case DOWN -> new Vector3f(0, 0, 0);
        };
        Vector3f xb = localVectorToWorld(direction, 1, 0, 0);
        Vector3f yb = localVectorToWorld(direction, 0, 1, 0);
        Vector3f zb = localVectorToWorld(direction, 0, 0, 1);
        return origin
                .add(xb.mul(x, new Vector3f()))
                .add(yb.mul(y, new Vector3f()))
                .add(zb.mul(z, new Vector3f()));
    }

    private static Vector3f localVectorToWorld(Direction direction, float x, float y, float z) {
        Vector3f xb;
        Vector3f yb;
        Vector3f zb;
        switch (direction) {
            case SOUTH -> {
                xb = new Vector3f(1, 0, 0);
                yb = new Vector3f(0, 1, 0);
                zb = new Vector3f(0, 0, 1);
            }
            case NORTH -> {
                xb = new Vector3f(-1, 0, 0);
                yb = new Vector3f(0, 1, 0);
                zb = new Vector3f(0, 0, -1);
            }
            case EAST -> {
                xb = new Vector3f(0, 0, -1);
                yb = new Vector3f(0, 1, 0);
                zb = new Vector3f(1, 0, 0);
            }
            case WEST -> {
                xb = new Vector3f(0, 0, 1);
                yb = new Vector3f(0, 1, 0);
                zb = new Vector3f(-1, 0, 0);
            }
            case UP -> {
                xb = new Vector3f(1, 0, 0);
                yb = new Vector3f(0, 0, -1);
                zb = new Vector3f(0, 1, 0);
            }
            case DOWN -> {
                xb = new Vector3f(1, 0, 0);
                yb = new Vector3f(0, 0, 1);
                zb = new Vector3f(0, -1, 0);
            }
            default -> throw new IllegalStateException("Unexpected direction: " + direction);
        }
        return xb.mul(x, new Vector3f())
                .add(yb.mul(y, new Vector3f()))
                .add(zb.mul(z, new Vector3f()));
    }

    private static Direction directionFromVector(Vector3f vector) {
        return Direction.getNearest(vector.x(), vector.y(), vector.z());
    }

    private static Vector3f moduleFrom(Direction direction) {
        return switch (direction) {
            case NORTH -> new Vector3f(MODULE_MIN, MODULE_MIN, -SURFACE_OFFSET);
            case SOUTH -> new Vector3f(MODULE_MIN, MODULE_MIN, 16 + SURFACE_OFFSET);
            case WEST -> new Vector3f(-SURFACE_OFFSET, MODULE_MIN, MODULE_MIN);
            case EAST -> new Vector3f(16 + SURFACE_OFFSET, MODULE_MIN, MODULE_MIN);
            case DOWN -> new Vector3f(MODULE_MIN, -SURFACE_OFFSET, MODULE_MIN);
            case UP -> new Vector3f(MODULE_MIN, 16 + SURFACE_OFFSET, MODULE_MIN);
        };
    }

    private static Vector3f moduleTo(Direction direction) {
        return switch (direction) {
            case NORTH -> new Vector3f(MODULE_MAX, MODULE_MAX, -SURFACE_OFFSET);
            case SOUTH -> new Vector3f(MODULE_MAX, MODULE_MAX, 16 + SURFACE_OFFSET);
            case WEST -> new Vector3f(-SURFACE_OFFSET, MODULE_MAX, MODULE_MAX);
            case EAST -> new Vector3f(16 + SURFACE_OFFSET, MODULE_MAX, MODULE_MAX);
            case DOWN -> new Vector3f(MODULE_MAX, -SURFACE_OFFSET, MODULE_MAX);
            case UP -> new Vector3f(MODULE_MAX, 16 + SURFACE_OFFSET, MODULE_MAX);
        };
    }
}
