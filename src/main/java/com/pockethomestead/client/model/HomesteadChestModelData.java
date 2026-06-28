package com.pockethomestead.client.model;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.RelativeSide;
import com.pockethomestead.blockentity.ResourceKind;
import com.pockethomestead.blockentity.SideMode;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.util.EnumMap;
import java.util.Map;

public final class HomesteadChestModelData {
    public static final ModelProperty<FaceConfig> FACE_CONFIG = new ModelProperty<>(config -> config != null);

    private HomesteadChestModelData() {
    }

    public static ModelData from(BaseChestBlockEntity chest) {
        FaceConfig config = FaceConfig.from(chest);
        return config.faces().isEmpty() ? ModelData.EMPTY : ModelData.of(FACE_CONFIG, config);
    }

    public record FaceState(ResourceKind kind, SideMode mode) {
    }

    public record FaceConfig(Map<RelativeSide, FaceState> faces) {
        public FaceState get(RelativeSide side) {
            return faces.get(side);
        }

        public static FaceConfig from(BaseChestBlockEntity chest) {
            EnumMap<RelativeSide, FaceState> faces = new EnumMap<>(RelativeSide.class);
            for (RelativeSide side : RelativeSide.values()) {
                for (ResourceKind kind : ResourceKind.values()) {
                    SideMode mode = chest.getSideMode(kind, side);
                    if (mode != SideMode.DISABLED) {
                        faces.put(side, new FaceState(kind, mode));
                        break;
                    }
                }
            }
            return new FaceConfig(Map.copyOf(faces));
        }
    }
}
