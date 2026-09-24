package dev.bdswaterdogpe.worldcontrol;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoType;
import dev.waterdog.waterdogpe.plugin.Plugin;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Exposes /worlds so a world can be added, stopped or deleted at runtime,
 * without restarting the proxy:
 * - GET /worlds - lists worlds currently registered with Waterdog.
 * - POST /worlds - provisions a world's backend and registers it.
 * - POST /worlds/{name}/stop - stops the backend and unregisters it,
 *   leaving its data alone so a later POST /worlds with the same name
 *   resumes it.
 * - DELETE /worlds/{name} - stops the backend, unregisters it, and
 *   permanently erases its data.
 * - POST /players/{name}/transfer - transfers an already-connected
 *   player to a world registered with Waterdog (form field: world).
 *
 * This has no authentication of its own, so it must never be reachable
 * from the BDS worlds' network/security group or the public internet -
 * only from a dedicated "control" scope (a security group on ECS, see
 * infra/; a separate Docker network on Compose, see docker/).
 *
 * Backed by either EcsWorldProvisioner or DockerWorldProvisioner,
 * selected by the PROVISIONER env var ("ecs", the default, or "docker").
 */
public class WorldControlPlugin extends Plugin {

    private static final Set<String> WORLD_TYPES = Set.of("normal", "flat", "void");

    private HttpServer httpServer;
    private WorldProvisioner provisioner;

    @Override
    public void onEnable() {
        String provisionerName = System.getenv().getOrDefault("PROVISIONER", "ecs");
        try {
            this.provisioner = switch (provisionerName) {
                case "docker" -> new DockerWorldProvisioner(this.getLogger());
                case "ecs" -> new EcsWorldProvisioner(this.getLogger());
                default -> throw new IllegalStateException("Unknown PROVISIONER: " + provisionerName);
            };
        } catch (IllegalStateException e) {
            this.getLogger().error("WorldControl misconfigured, not starting HTTP API", e);
            return;
        }

        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", 8081), 0);
            this.httpServer.createContext("/worlds", this::handleWorlds);
            this.httpServer.createContext("/players", this::handlePlayers);
            this.httpServer.setExecutor(Executors.newCachedThreadPool());
            this.httpServer.start();
            this.getLogger().info("WorldControl HTTP API listening on :8081");
        } catch (IOException e) {
            this.getLogger().error("Failed to start WorldControl HTTP API", e);
        }

        this.bootstrapWorlds();
    }

    /**
     * Ensures every world that should be up when Waterdog starts actually
     * is: worlds the provisioner persisted from a previous run (see
     * WorldProvisioner#knownWorlds - without this, a world added via
     * POST /worlds would only stay reachable until the next Waterdog
     * restart), plus, under the Docker target specifically, "lobby" -
     * provisioned like any other world there instead of being a static
     * config.yml entry, so it also picks up shared behavior packs (see
     * DockerWorldProvisioner). Under ECS, lobby is a container co-located in
     * Waterdog's own task (see infra/) and is already running by this point.
     * Runs off the startup thread since provisioning a world can block for
     * up to a few minutes (container start + health check). Set
     * RECONCILE=false to skip restoring previously added worlds - lobby
     * still starts.
     */
    private void bootstrapWorlds() {
        Map<String, WorldProvisioner.WorldRecord> worlds = new LinkedHashMap<>();
        if (this.provisioner instanceof DockerWorldProvisioner) {
            worlds.put("lobby", new WorldProvisioner.WorldRecord("survival", "normal"));
        }
        if (Boolean.parseBoolean(System.getenv().getOrDefault("RECONCILE", "true"))) {
            worlds.putAll(this.provisioner.knownWorlds());
        } else {
            this.getLogger().info("RECONCILE=false, not restoring previously added worlds");
        }
        if (worlds.isEmpty()) {
            return;
        }
        Thread bootstrapThread = new Thread(
                () -> worlds.forEach(this::reconcileWorld), "worldcontrol-reconcile");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();
    }

    private void reconcileWorld(String name, WorldProvisioner.WorldRecord record) {
        try {
            InetSocketAddress address = this.provisioner.startWorld(name, record.gamemode(), record.worldType());
            ServerInfo serverInfo = ServerInfoType.RAKNET.getServerInfoFactory()
                    .createServerInfo(name, address, null);
            if (this.getProxy().registerServerInfo(serverInfo)) {
                this.getLogger().info("Reconciled world '" + name + "' at " + address);
            } else {
                this.getLogger().warn("Could not reconcile world '" + name + "': already registered");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            this.getLogger().error("Failed to reconcile world '" + name + "'", e);
        }
    }

    @Override
    public void onDisable() {
        if (this.httpServer != null) {
            this.httpServer.stop(0);
        }
    }

    private void handleWorlds(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if (path.equals("/worlds")) {
                switch (method) {
                    case "GET" -> this.listWorlds(exchange);
                    case "POST" -> this.addWorld(exchange);
                    default -> this.respond(exchange, 405, "method not allowed");
                }
                return;
            }
            if (path.endsWith("/stop") && "POST".equals(method)) {
                String name = path.substring("/worlds/".length(), path.length() - "/stop".length());
                this.stopWorld(exchange, name);
                return;
            }
            if ("DELETE".equals(method)) {
                String name = path.substring("/worlds/".length());
                this.removeWorld(exchange, name);
                return;
            }
            this.respond(exchange, 405, "method not allowed");
        } catch (Exception e) {
            this.getLogger().error("Error handling WorldControl request", e);
            this.respond(exchange, 500, "internal error: " + e.getMessage());
        }
    }

    private void listWorlds(HttpExchange exchange) throws IOException {
        ProxyServer proxy = this.getProxy();
        String body = proxy.getServers().stream()
                .map(server -> "{\"name\":\"" + server.getServerName() + "\",\"address\":\"" +
                        server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
        this.respond(exchange, 200, body);
    }

    private void addWorld(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(exchange.getRequestBody().readAllBytes());
        String name = form.get("name");
        String gamemode = form.getOrDefault("gamemode", "survival");
        String worldType = form.getOrDefault("worldType", "normal").toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) {
            this.respond(exchange, 400, "name is required");
            return;
        }
        if (!WORLD_TYPES.contains(worldType)) {
            this.respond(exchange, 400, "worldType must be one of: " + String.join(", ", WORLD_TYPES));
            return;
        }
        if (this.getProxy().getServerInfo(name) != null) {
            this.respond(exchange, 409, "a world with that name is already registered");
            return;
        }

        InetSocketAddress address;
        try {
            address = this.provisioner.startWorld(name, gamemode, worldType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.respond(exchange, 500, "interrupted while provisioning world");
            return;
        } catch (RuntimeException e) {
            this.getLogger().error("Failed to provision world '" + name + "'", e);
            this.provisioner.destroyWorld(name);
            this.respond(exchange, 502, "failed to provision world: " + e.getMessage());
            return;
        }

        ServerInfo serverInfo = ServerInfoType.RAKNET.getServerInfoFactory()
                .createServerInfo(name, address, null);
        boolean registered = this.getProxy().registerServerInfo(serverInfo);
        if (registered) {
            this.getLogger().info("Registered world '" + name + "' at " + address);
            this.respond(exchange, 200, "registered");
        } else {
            // Lost a race with another registration of the same name; tear back down completely.
            this.provisioner.destroyWorld(name);
            this.respond(exchange, 409, "a world with that name is already registered");
        }
    }

    private void stopWorld(HttpExchange exchange, String name) throws IOException {
        if (name == null || name.isBlank()) {
            this.respond(exchange, 400, "world name is required in the path");
            return;
        }

        ServerInfo removed = this.getProxy().removeServerInfo(name);
        this.provisioner.stopWorld(name);
        if (removed != null) {
            this.getLogger().info("Stopped world '" + name + "'");
            this.respond(exchange, 200, "stopped");
        } else {
            this.respond(exchange, 404, "no such world");
        }
    }

    private void removeWorld(HttpExchange exchange, String name) throws IOException {
        if (name == null || name.isBlank()) {
            this.respond(exchange, 400, "world name is required in the path");
            return;
        }

        ServerInfo removed = this.getProxy().removeServerInfo(name);
        this.provisioner.destroyWorld(name);
        if (removed != null) {
            this.getLogger().info("Deleted world '" + name + "'");
            this.respond(exchange, 200, "deleted");
        } else {
            this.respond(exchange, 404, "no such world");
        }
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if (path.endsWith("/transfer") && "POST".equals(method)) {
                // Player names can contain spaces/other characters a URL path can't hold
                // literally, unlike world names - the client must percent-encode this
                // segment (hakomc-world's transferPlayer() does), so it's decoded back here.
                String name = URLDecoder.decode(
                        path.substring("/players/".length(), path.length() - "/transfer".length()),
                        StandardCharsets.UTF_8);
                this.transferPlayer(exchange, name);
                return;
            }
            this.respond(exchange, 405, "method not allowed");
        } catch (Exception e) {
            this.getLogger().error("Error handling WorldControl request", e);
            this.respond(exchange, 500, "internal error: " + e.getMessage());
        }
    }

    private void transferPlayer(HttpExchange exchange, String playerName) throws IOException {
        if (playerName == null || playerName.isBlank()) {
            this.respond(exchange, 400, "player name is required in the path");
            return;
        }
        Map<String, String> form = parseForm(exchange.getRequestBody().readAllBytes());
        String worldName = form.get("world");
        if (worldName == null || worldName.isBlank()) {
            this.respond(exchange, 400, "world is required");
            return;
        }

        ProxiedPlayer player = this.getProxy().getPlayer(playerName);
        if (player == null) {
            this.respond(exchange, 404, "no such player");
            return;
        }
        ServerInfo target = this.getProxy().getServerInfo(worldName);
        if (target == null) {
            this.respond(exchange, 404, "no such world");
            return;
        }

        player.connect(target);
        this.getLogger().info("Transferring player '" + playerName + "' to world '" + worldName + "'");
        this.respond(exchange, 200, "transferring");
    }

    private static Map<String, String> parseForm(byte[] body) {
        Map<String, String> result = new HashMap<>();
        String raw = new String(body, StandardCharsets.UTF_8);
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
