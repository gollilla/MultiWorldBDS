package dev.bdswaterdogpe.worldcontrol;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;

/**
 * Runs BDS worlds as local Docker containers (docker run), one per world.
 * Requires the host's Docker socket to be mounted into this container
 * (/var/run/docker.sock) and the docker CLI to be present in the image -
 * see docker/docker-compose.yml and waterdog/Dockerfile.
 */
public class DockerWorldProvisioner implements WorldProvisioner {

    private static final Duration HEALTHY_TIMEOUT = Duration.ofMinutes(3);

    private final Logger logger;
    private final String network;
    private final String dataDir;

    public DockerWorldProvisioner(Logger logger) {
        this.logger = logger;
        this.network = requireEnv("DOCKER_NETWORK");
        this.dataDir = requireEnv("DOCKER_DATA_DIR");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    @Override
    public InetSocketAddress startWorld(String name, String gamemode) throws InterruptedException {
        String container = "bds-" + name;
        run("docker", "run", "-d", "--name", container,
                "--network", this.network,
                "-v", this.dataDir + "/" + name + ":/data",
                "-e", "EULA=TRUE",
                "-e", "SERVER_NAME=" + name,
                "-e", "LEVEL_NAME=" + name,
                "-e", "GAMEMODE=" + gamemode,
                "-e", "ONLINE_MODE=false",
                "-e", "ALLOW_LIST=false",
                "-e", "ALLOW_CHEATS=true",
                // See EcsWorldProvisioner: itzg's image maps TRANSPORT into
                // server.properties as of PR #675, avoiding BDS defaulting to
                // NetherNet, which WaterdogPE's RakNet connection can't reach.
                "-e", "TRANSPORT=raknet",
                "-e", "SERVER_PORT=19132",
                "itzg/minecraft-bedrock-server");

        this.waitUntilHealthy(container);
        String ip = run("docker", "inspect", "--format",
                "{{.NetworkSettings.Networks." + this.network + ".IPAddress}}", container).trim();
        if (ip.isEmpty()) {
            throw new IllegalStateException("Container " + container + " has no IP on network " + this.network);
        }
        return new InetSocketAddress(ip, 19132);
    }

    @Override
    public void stopWorld(String name) {
        try {
            run("docker", "rm", "-f", "bds-" + name);
        } catch (RuntimeException | InterruptedException e) {
            this.logger.warn("Failed to remove container for world '" + name + "'", e);
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
