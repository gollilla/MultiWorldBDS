package dev.bdswaterdogpe.worldcontrol;

import java.net.InetSocketAddress;

/**
 * Provisions/tears down a world's backend on demand. Implementations:
 * {@link EcsWorldProvisioner} (AWS ECS Fargate RunTask) and
 * {@link DockerWorldProvisioner} (local/self-hosted docker run).
 */
public interface WorldProvisioner {

    /**
     * Starts a new world backend and blocks until it is reachable, returning
     * the address WaterdogPE should dial.
     */
    InetSocketAddress startWorld(String name, String gamemode) throws InterruptedException;

    /** Stops and removes the given world's backend. No-op if not known. */
    void stopWorld(String name);
}
