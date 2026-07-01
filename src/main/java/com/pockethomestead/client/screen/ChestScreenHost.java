package com.pockethomestead.client.screen;

import com.pockethomestead.blockentity.RelativeSide;
import com.pockethomestead.blockentity.ResourceKind;
import com.pockethomestead.blockentity.SideMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.EnumMap;

/** BaseChestScreen 向面配置面板暴露的共享状态接口。 */
interface ChestScreenHost {
    Font font();
    Minecraft minecraft();
    int leftPos();
    int topPos();
    int panelWidth();
    int panelHeight();
    void sendConfig(int action, String value);
    EnumMap<ResourceKind, EnumMap<RelativeSide, SideMode>> sideConfigMap();
    int stressOutputSpeedRpm();
    void stressOutputSpeedRpm(int rpm);
    boolean stressOutputReversed();
    void stressOutputReversed(boolean reversed);
    boolean createLoaded();
    boolean hasFluidPage();
}
