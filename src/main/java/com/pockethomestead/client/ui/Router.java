package com.pockethomestead.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 页面路由器：持有有序页面列表与当前页索引。新增页面只需 {@link #register(Page)} 一行。
 */
public final class Router {
    private final List<Page> pages = new ArrayList<>();
    private int current = 0;

    public Router register(Page page) {
        page.attach(this);
        pages.add(page);
        return this;
    }

    public List<Page> pages() { return Collections.unmodifiableList(pages); }

    public Page current() {
        return pages.isEmpty() ? null : pages.get(current);
    }

    public int currentIndex() { return current; }

    public boolean isCurrent(Page page) {
        return current() == page;
    }

    /** 按索引切换。 */
    public void setActive(int index) {
        if (index < 0 || index >= pages.size() || index == current) return;
        Page prev = current();
        if (prev != null) prev.onExit();
        current = index;
        Page next = current();
        if (next != null) next.onEnter();
    }

    /** 按 id 切换；找不到则忽略。 */
    public void setActive(String id) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id().equals(id)) { setActive(i); return; }
        }
    }

    /** 直接定位到 id（不触发 onExit/onEnter 差异判断之外的逻辑），用于初始化。 */
    public void selectInitial(String id) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id().equals(id)) { current = i; return; }
        }
        current = 0;
    }
}
