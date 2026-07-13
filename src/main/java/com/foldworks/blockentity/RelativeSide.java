package com.foldworks.blockentity;

import net.minecraft.core.Direction;

public enum RelativeSide {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    UP,
    DOWN;

    public static RelativeSide fromWorld(Direction side, Direction front) {
        if (side == null) return FRONT;
        if (side == Direction.UP) return UP;
        if (side == Direction.DOWN) return DOWN;
        if (front == null || front.getAxis().isVertical()) front = Direction.NORTH;
        if (side == front) return FRONT;
        if (side == front.getOpposite()) return BACK;
        if (side == front.getClockWise()) return LEFT;
        if (side == front.getCounterClockWise()) return RIGHT;
        return FRONT;
    }

    public Direction toWorld(Direction front) {
        if (front == null || front.getAxis().isVertical()) front = Direction.NORTH;
        return switch (this) {
            case FRONT -> front;
            case BACK -> front.getOpposite();
            case LEFT -> front.getClockWise();
            case RIGHT -> front.getCounterClockWise();
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
        };
    }
}
