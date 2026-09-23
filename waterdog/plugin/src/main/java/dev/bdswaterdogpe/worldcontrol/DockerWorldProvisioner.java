package dev.bdswaterdogpe.worldcontrol;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs BDS worlds as local Docker containers (docker run), one per world.
 * Requires the host's Docker socket to be mounted into this container
 * (/var/run/docker.sock) and the docker CLI to be present in the image -
 * see docker/docker-compose.yml and waterdog/Dockerfile.
 */
public class DockerWorldProvisioner implements WorldProvisioner {

    private static final Duration HEALTHY_TIMEOUT = Duration.ofMinutes(3);

    // This container's own filesystem, not the DOCKER_DATA_DIR host path
    // (that one is only ever resolved by the host daemon for world
    // containers' /data - see the docker run call below). Needs its own
    // volume mount to survive this container being recreated; see
    // docker/docker-compose.yml.
    private static final Path STATE_FILE = Path.of("/waterdog/state/worlds.txt");

    // A pre-built world (see docker/world-templates/void) mounted read-only
    // into this container - BDS's LEVEL_TYPE only controls generation of a
    // brand new world and there's no "void" value for it, so a void world is
    // seeded by copying an already-generated one in before first start.
    private static final String VOID_TEMPLATE_DIR = "/waterdog/world-templates/void";

    // Read-only view of docker/shared/ inside this container, used only to
    // enumerate behavior pack names (see sharedMountArgs) - the actual host
    // path handed to per-world `docker run`/`docker create` calls is
    // this.sharedDir, resolved by the host daemon instead.
    private static final Path SHARED_LOCAL_DIR = Path.of("/waterdog/shared");

    private final Logger logger;
    private final String network;
    private final String dataDir;
    // Optional: a host directory (behavior_packs/<pack>/, config/,
    // world_behavior_packs.json - see docker/shared/) bind-mounted into
    // every world this provisioner starts, lobby included (it's provisioned
    // the same way as any other world - see WorldControlPlugin's startup
    // bootstrap). Null skips these mounts entirely.
    private final String sharedDir;

    public DockerWorldProvisioner(Logger logger) {
        this.logger = logger;
        this.network = requireEnv("DOCKER_NETWORK");
        this.dataDir = requireEnv("DOCKER_DATA_DIR");
        this.sharedDir = System.getenv("DOCKER_SHARED_DIR");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    @Override
    public InetSocketAddress startWorld(String name, String gamemode, String worldType) throws InterruptedException {
        String container = "bds-" + name;
        // Only a genuinely new world's data should be seeded from the void
        // template - a world recreated after its container was lost (e.g. a
        // host reboot) already has real data sitting in the bind-mounted
        // host directory, which must not be overwritten with a fresh copy.
        boolean isNewWorld = !this.knownWorlds().containsKey(name);
        String status = this.inspectStatus(container);

        if (status == null) {
            if (isNewWorld && "void".equalsIgnoreCase(worldType)) {
                this.createVoidWorld(container, name, gamemode);
            } else {
                this.runFreshContainer(container, name, gamemode, worldType);
            }
        } else if (!"running".equals(status)) {
            run("docker", "start", container);
        }
        // else: already running - reused as-is (this branch is what makes
        // reconcile-on-startup idempotent instead of provisioning a duplicate).

        this.waitUntilHealthy(container);
        String ip = run("docker", "inspect", "--format",
                "{{.NetworkSettings.Networks." + this.network + ".IPAddress}}", container).trim();
        if (ip.isEmpty()) {
            throw new IllegalStateException("Container " + container + " has no IP on network " + this.network);
        }
        this.persist(name, gamemode, worldType);
        return new InetSocketAddress(ip, 19132);
    }

    private void runFreshContainer(String container, String name, String gamemode, String worldType)
            throws InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker", "run", "-d", "--name", container,
                "--network", this.network,
                // Survives the Docker daemon restarting (e.g. a host reboot)
                // on its own, without waiting on this plugin's reconcile.
                "--restart", "unless-stopped",
                "-v", this.dataDir + "/" + name + ":/data",
                "-e", "EULA=TRUE",
                "-e", "SERVER_NAME=" + name,
                "-e", "LEVEL_NAME=" + name,
                "-e", "GAMEMODE=" + gamemode,
                "-e", "LEVEL_TYPE=" + levelTypeFor(worldType),
                "-e", "ONLINE_MODE=false",
                "-e", "ALLOW_LIST=false",
                "-e", "ALLOW_CHEATS=true",
                // See EcsWorldProvisioner: itzg's image maps TRANSPORT into
                // server.properties as of PR #675, avoiding BDS defaulting to
                // NetherNet, which WaterdogPE's RakNet connection can't reach.
                "-e", "TRANSPORT=raknet",
                "-e", "SERVER_PORT=19132"));
        command.addAll(this.sharedMountArgs(name));
        command.add("itzg/minecraft-bedrock-server");
        run(command.toArray(new String[0]));
    }

    private void createVoidWorld(String container, String name, String gamemode) throws InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker", "create", "--name", container,
                "--network", this.network,
                "--restart", "unless-stopped",
                "-v", this.dataDir + "/" + name + ":/data",
                "-e", "EULA=TRUE",
                "-e", "SERVER_NAME=" + name,
                "-e", "LEVEL_NAME=" + name,
                "-e", "GAMEMODE=" + gamemode,
                "-e", "ONLINE_MODE=false",
                "-e", "ALLOW_LIST=false",
                "-e", "ALLOW_CHEATS=true",
                "-e", "TRANSPORT=raknet",
                "-e", "SERVER_PORT=19132"));
        command.addAll(this.sharedMountArgs(name));
        command.add("itzg/minecraft-bedrock-server");
        run(command.toArray(new String[0]));

        // docker cp won't create missing intermediate directories on the
        // destination side (a freshly created container has no /data/worlds
        // yet), so the template is staged here first under worlds/<name>,
        // then that whole "worlds" directory is copied in as one unit -
        // /data itself (the bind mount point) already exists even before
        // the container starts, so copying *into* it works.
        String stagingRoot = "/tmp/void-" + name;
        String stagingWorldDir = stagingRoot + "/worlds/" + name;
        try {
            run("mkdir", "-p", stagingWorldDir);
            run("cp", "-r", VOID_TEMPLATE_DIR + "/.", stagingWorldDir);
            if (this.sharedDir != null && !this.sharedDir.isBlank()) {
                // The shared world_behavior_packs.json is already bind-mounted
                // at this same destination path (from docker create above) -
                // docker cp can't overwrite a file a mount is holding onto, so
                // drop the template's own copy rather than conflict with it.
                run("rm", "-f", stagingWorldDir + "/world_behavior_packs.json");
            }
            run("docker", "cp", stagingRoot + "/worlds", container + ":/data");
        } finally {
            run("rm", "-rf", stagingRoot);
        }

        run("docker", "start", container);
    }

    /**
     * Maps a requested world type to BDS's LEVEL_TYPE values (DEFAULT/FLAT -
     * see server.properties' level-type; there's no VOID value). Only
     * relevant when generating a brand new world, so an unrecognized value
     * (e.g. "void" when recreating an already-existing world after its
     * container was lost) safely falls back to DEFAULT rather than failing -
     * real data already sitting on the host takes precedence over it anyway.
     */
    private static String levelTypeFor(String worldType) {
        return "flat".equalsIgnoreCase(worldType) ? "FLAT" : "DEFAULT";
    }

    /**
     * Bind mounts for the shared behavior packs/config directory (see
     * docker/shared/ and the sharedDir field), skipped entirely when
     * DOCKER_SHARED_DIR isn't set. world_behavior_packs.json is mounted as a
     * single file rather than derived from each pack's manifest.json -
     * there's no JSON parsing anywhere in this plugin, so it's maintained by
     * hand alongside the packs it registers.
     *
     * behavior_packs is mounted one pack at a time, at
     * /data/behavior_packs/<pack-name> - mounting /data/behavior_packs
     * itself crashes BDS outright (its own first-boot extraction of the
     * vanilla pack there appears to rename() into place, which fails across
     * a mount boundary). Leaving the parent directory unmounted keeps that
     * extraction on the same filesystem as /data. config/ doesn't have this
     * problem and is mounted whole, read-write for the same
     * itzg-writes-into-it-on-first-boot reason (a default permissions.json).
     */
    private List<String> sharedMountArgs(String name) {
        if (this.sharedDir == null || this.sharedDir.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String pack : this.sharedBehaviorPackNames()) {
            args.add("-v");
            args.add(this.sharedDir + "/behavior_packs/" + pack + ":/data/behavior_packs/" + pack + ":ro");
        }
        args.add("-v");
        args.add(this.sharedDir + "/config:/data/config");
        args.add("-v");
        args.add(this.sharedDir + "/world_behavior_packs.json:/data/worlds/" + name
                + "/world_behavior_packs.json:ro");
        return args;
    }

    private List<String> sharedBehaviorPackNames() {
        Path packsDir = SHARED_LOCAL_DIR.resolve("behavior_packs");
        if (!Files.isDirectory(packsDir)) {
            return List.of();
        }
        try (var entries = Files.list(packsDir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            this.logger.warn("Failed to list shared behavior packs in " + packsDir, e);
            return List.of();
        }
    }

    @Override
    public void stopWorld(String name) {
        try {
            run("docker", "rm", "-f", "bds-" + name);
        } catch (RuntimeException | InterruptedException e) {
            this.logger.warn("Failed to remove container for world '" + name + "'", e);
        }
        // Deliberately not forgotten from state: startWorld sees this name
        // still known, so a later call resumes it via a plain `docker run`
        // against the untouched host data instead of re-seeding it as new.
    }

    @Override
    public void destroyWorld(String name) {
        this.stopWorld(name);
        try {
            this.purgeData(name);
        } catch (RuntimeException | InterruptedException e) {
            this.logger.warn("Failed to purge data for world '" + name + "'", e);
        }
        this.forget(name);
    }

    /**
     * Empties the world's host data directory via a throwaway container
     * bind-mounted to it - this container has no filesystem access to
     * DOCKER_DATA_DIR itself (it's a host path only ever resolved by the
     * host daemon, same reason startWorld stages the void template rather
     * than writing to it directly).
     */
    private void purgeData(String name) throws InterruptedException {
        run("docker", "run", "--rm", "--entrypoint", "sh",
                "-v", this.dataDir + "/" + name + ":/target",
                "itzg/minecraft-bedrock-server", "-c", "rm -rf /target/*");
    }

    @Override
    public Map<String, WorldRecord> knownWorlds() {
        Map<String, WorldRecord> worlds = new LinkedHashMap<>();
        if (!Files.exists(STATE_FILE)) {
            return worlds;
        }
        try {
            for (String line : Files.readAllLines(STATE_FILE, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] nameAndRest = line.split("=", 2);
                if (nameAndRest.length != 2) {
                    continue;
                }
                String[] gamemodeAndType = nameAndRest[1].split(":", 2);
                String gamemode = gamemodeAndType[0];
                String worldType = gamemodeAndType.length > 1 ? gamemodeAndType[1] : "normal";
                worlds.put(nameAndRest[0], new WorldRecord(gamemode, worldType));
            }
        } catch (IOException e) {
            this.logger.warn("Failed to read WorldControl state file " + STATE_FILE, e);
        }
        return worlds;
    }

    private void persist(String name, String gamemode, String worldType) {
        Map<String, WorldRecord> worlds = this.knownWorlds();
        worlds.put(name, new WorldRecord(gamemode, worldType));
        this.writeState(worlds);
    }

    private void forget(String name) {
        Map<String, WorldRecord> worlds = this.knownWorlds();
        worlds.remove(name);
        this.writeState(worlds);
    }

    private void writeState(Map<String, WorldRecord> worlds) {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            StringBuilder content = new StringBuilder();
            worlds.forEach((name, record) -> content.append(name).append('=')
                    .append(record.gamemode()).append(':').append(record.worldType()).append('\n'));
            Files.writeString(STATE_FILE, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            this.logger.warn("Failed to write WorldControl state file " + STATE_FILE, e);
        }
    }

    /** Returns the container's status (e.g. "running", "exited"), or null if it doesn't exist. */
    private String inspectStatus(String container) throws InterruptedException {
        try {
            return run("docker", "inspect", "--format", "{{.State.Status}}", container).trim();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private void waitUntilHealthy(String container) throws InterruptedException {
        Instant deadline = Instant.now().plus(HEALTHY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String status = run("docker", "inspect", "--format", "{{.State.Health.Status}}", container).trim();
            this.logger.debug("Container " + container + " health=" + status);

            if ("healthy".equals(status)) {
                return;
            }
            if ("unhealthy".equals(status)) {
                throw new IllegalStateException("Container " + container + " is unhealthy");
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("Container " + container + " did not become healthy within " + HEALTHY_TIMEOUT);
    }

    private String run(String... command) throws InterruptedException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Command failed (" + process.exitValue() + "): "
                        + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run command: " + String.join(" ", command), e);
        }
    }
}
