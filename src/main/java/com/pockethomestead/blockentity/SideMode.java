package com.pockethomestead.blockentity;

public enum SideMode {
    DISABLED,
    INPUT,
    OUTPUT,
    BOTH;

    public boolean canInput() {
        return this == INPUT || this == BOTH;
    }

    public boolean canOutput() {
        return this == OUTPUT || this == BOTH;
    }
}
