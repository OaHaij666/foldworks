package com.foldworks.blockentity;

public interface FoldworksStressEndpoint extends FoldworksChestAccess {
    boolean canSendGraphStress();

    boolean canReceiveGraphStress();

    float graphStressSpeed();

    float graphStressCapacity();

    float receiveGraphStressLease(String leaseId, float speed, float capacity, long gameTime);

    void recordGraphStressLease(String leaseId, float speed, float capacity, long gameTime);
}
