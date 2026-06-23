package com.pockethomestead.blockentity;

public interface HomesteadStressEndpoint extends HomesteadChestAccess {
    boolean canSendGraphStress();

    float graphStressSpeed();

    float graphStressCapacity();

    void receiveGraphStressLease(String leaseId, float speed, float capacity, long gameTime);
}
