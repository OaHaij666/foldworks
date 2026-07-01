package com.pockethomestead.space;

/** 单玩家空间数量达到上限时抛出，由调用方捕获并反馈给玩家。 */
public class SpaceLimitExceededException extends RuntimeException {
    private final int max;

    public SpaceLimitExceededException(int max) {
        super("已达到单玩家最大空间数量上限: " + max);
        this.max = max;
    }

    public int max() { return max; }
}
