package dev.bdswaterdogpe.worldcontrol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Copies a stopped world's data to a new name (gamemode/worldType for
     * the copy, since the caller may want to override what src was created
     * with) and records the new name in {@link #knownWorlds()} so a
     * subsequent {@link #startWorld} resumes the copied data instead of
     * treating it as brand new. Does not start dst's backend. The caller is
     * responsible for checking that neither src nor dst is currently
     * registered/running - this method doesn't re-check that itself.
     * Providers with no durable per-world storage to copy (e.g.
     * EcsWorldProvisioner) throw UnsupportedOperationException.
     */
    default void copyWorld(String src, String dst, String gamemode, String worldType) {
        throw new UnsupportedOperationException("copyWorld is not supported by this provisioner");
    }

    /**
     * Best-effort reverse lookup: the name of the world backend whose
     * network address is this one, including a backend that's still
     * starting up and not yet registered with Waterdog (a freshly created
     * container has an IP before it's healthy) - see WorldControlPlugin's
     * GET /worlds/self, which a world's own script can call to learn its
     * own name (SERVER_NAME/LEVEL_NAME are only visible as env vars, not
     * from Script API). Providers with no cheap way to do this (e.g.
     * EcsWorldProvisioner, which would need a DescribeTasks call per
     * lookup) return empty; WorldControlPlugin falls back to matching
     * already-registered servers regardless of provisioner.
     */
    default Optional<String> resolveWorldName(InetAddress address) {
        return Optional.empty();
    }
}
