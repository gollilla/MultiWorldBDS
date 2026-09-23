package dev.bdswaterdogpe.worldcontrol;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Provisions/tears down a world's backend on demand. Implementations:
 * {@link EcsWorldProvisioner} (AWS ECS Fargate RunTask) and
 * {@link DockerWorldProvisioner} (local/self-hosted docker run).
 */
public interface WorldProvisioner {

    /** A world's persisted settings: gamemode ("survival"/"creative"/"adventure") and world type ("normal"/"flat"/"void"). */
    record WorldRecord(String gamemode, String worldType) {}

    /**
     * Starts a new world backend and blocks until it is reachable, returning
     * the address WaterdogPE should dial. Implementations should be
     * idempotent - calling this for a world whose backend is already
     * running (e.g. during {@link WorldControlPlugin#onEnable()} reconcile)
     * should reuse it rather than provisioning a duplicate, and must not
     * re-seed a world's starting data once it already exists (worldType only
     * matters for genuinely new worlds).
     */
    InetSocketAddress startWorld(String name, String gamemode, String worldType) throws InterruptedException;

    /**
     * Stops and removes the given world's backend, leaving its data alone
     * so it can be resumed later by calling {@link #startWorld} again with
     * the same name. No-op if not known.
     */
    void stopWorld(String name);

    /**
     * Stops the world's backend and permanently erases its data - unlike
     * {@link #stopWorld}, this world cannot be resumed afterwards. Providers
     * with nothing durable to erase beyond the backend itself (e.g.
     * EcsWorldProvisioner - ECS ad-hoc worlds aren't currently persisted at
     * all, a known gap in infra/) can leave this as an alias for stopWorld.
     */
    default void destroyWorld(String name) {
        this.stopWorld(name);
    }

    /**
     * Worlds this provisioner persisted from a previous run, keyed by name -
     * WorldControlPlugin re-registers each of these with Waterdog on
     * startup, so worlds added via POST /worlds survive a Waterdog restart.
     * Providers with no persisted state (e.g. EcsWorldProvisioner - see
     * infra/'s known gap around ECS task persistence) return an empty map.
     */
    default Map<String, WorldRecord> knownWorlds() {
        return Map.of();
    }
}
