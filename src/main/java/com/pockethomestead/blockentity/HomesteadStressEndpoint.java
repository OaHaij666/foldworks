package com.pockethomestead.blockentity;

public interface HomesteadStressEndpoint extends HomesteadChestAccess {
    boolean canSendGraphStress();

    boolean canReceiveGraphStress();

    float graphStressSpeed();

    float graphStressCapacity();

    float receiveGraphStressLease(String leaseId, float speed, float capacity, long gameTime);

    void recordGraphStressLease(String leaseId, float speed, float capacity, long gameTime);
}
