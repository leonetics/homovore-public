package dev.leonetic.mixin.entity;

public interface LivingEntityServerDeadAccessor {
    void homovore$setServerSideDead(boolean value);

    boolean homovore$isServerSideDead();
}
