package com.foldworks.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 一个 GUI 页面。由 {@link Router} 管理，渲染进 {@link com.foldworks.client.FoldworksScreen}
 * 分配的内容矩形内。新增页面只需继承本类并向 Router 注册。
 */
public abstract class Page {
    protected final Minecraft mc = Minecraft.getInstance();
    protected final Font font = mc.font;

    /** 内容区矩形（由根 Screen 在布局时赋值）。 */
    protected int x, y, w, h;

    /** 路由器引用，便于页面间跳转（如创建后跳到管理页）。 */
    protected Router router;

    void attach(Router router) { this.router = router; }

    /** 唯一标识，用于路由切换与记忆。 */
    public abstract String id();

    /** 导航栏显示名（已解析的字符串）。 */
    public abstract String navTitle();

    /** 导航栏图标字符（简洁起见用字形/符号，避免额外贴图）。 */
    public String navIcon() { return "●"; }

    /** 内容区尺寸变化时调用（窗口缩放 / 切页）。 */
    public void onLayout(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    /** 切入本页时调用（如请求最新数据）。 */
    public void onEnter() {}

    /** 切出本页时调用（如关闭打开的下拉）。 */
    public void onExit() {}

    public void tick() {}

    /** 主体渲染。 */
    public abstract void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);

    /** 叠层渲染（下拉弹窗等需盖在所有内容之上、可溢出内容区）。默认无。 */
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    // ===== 输入事件（返回 true 表示已消费）=====

    /** 叠层优先吃点击（如下拉展开时）。返回 true 表示已消费。 */
    public boolean overlayMouseClicked(double mx, double my, int button) { return false; }

    public boolean mouseClicked(double mx, double my, int button) { return false; }
    public boolean mouseReleased(double mx, double my, int button) { return false; }
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return false; }
    public boolean mouseScrolled(double mx, double my, double sx, double sy) { return false; }
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    public boolean charTyped(char codePoint, int modifiers) { return false; }
}
