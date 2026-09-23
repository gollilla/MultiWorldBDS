package dev.bdswaterdogpe.worldcontrol;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Provisions/tears down a world's backend on demand. Implementations:
 * {@link EcsWorldProvisioner} (AWS ECS Fargate RunTask) and
 * {@link DockerWorldProvisioner} (local/self-hosted docker run).
 */
public interface WorldProvisioner {

    /**
     * Starts a new world backend and blocks until it is reachable, returning
     * the address WaterdogPE should dial. Implementations should be
     * idempotent - calling this for a world whose backend is already
     * running (e.g. during {@link WorldControlPlugin#onEnable()} reconcile)
     * should reuse it rather than provisioning a duplicate.
     */
    InetSocketAddress startWorld(String name, String gamemode) throws InterruptedException;

    /** Stops and removes the given world's backend. No-op if not known. */
    void stopWorld(String name);

    /**
     * Worlds this provisioner persisted from a previous run, keyed by name
     * with their gamemode - WorldControlPlugin re-registers each of these
     * with Waterdog on startup, so worlds added via POST /worlds survive a
     * Waterdog restart. Providers with no persisted state (e.g.
     * EcsWorldProvisioner - see infra/'s known gap around ECS task
     * persistence) return an empty map.
     */
    default Map<String, String> knownWorlds() {
        return Map.of();
    }
}
