package dev.bdswaterdogpe.worldcontrol;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoType;
import dev.waterdog.waterdogpe.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class WorldControlPlugin extends Plugin {

    private HttpServer httpServer;

    @Override
    public void onEnable() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", 8081), 0);
            this.httpServer.createContext("/worlds", this::handleWorlds);
            this.httpServer.setExecutor(null);
            this.httpServer.start();
            this.getLogger().info("WorldControl HTTP API listening on :8081");
        } catch (IOException e) {
            this.getLogger().error("Failed to start WorldControl HTTP API", e);
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
        String worldName = path.length() > "/worlds/".length() ? path.substring("/worlds/".length()) : null;

        try {
            switch (exchange.getRequestMethod()) {
                case "GET" -> this.listWorlds(exchange);
                case "POST" -> this.addWorld(exchange);
                case "DELETE" -> this.removeWorld(exchange, worldName);
                default -> this.respond(exchange, 405, "method not allowed");
            }
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
        String address = form.get("address");
        if (name == null || name.isBlank() || address == null || !address.contains(":")) {
            this.respond(exchange, 400, "name and address (host:port) are required");
            return;
        }

        String host = address.substring(0, address.lastIndexOf(':'));
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        ServerInfo serverInfo = ServerInfoType.RAKNET.getServerInfoFactory()
                .createServerInfo(name, new InetSocketAddress(host, port), null);

        boolean registered = this.getProxy().registerServerInfo(serverInfo);
        if (registered) {
            this.getLogger().info("Registered world '" + name + "' at " + address);
            this.respond(exchange, 200, "registered");
        } else {
            this.respond(exchange, 409, "a world with that name is already registered");
        }
    }

    private void removeWorld(HttpExchange exchange, String name) throws IOException {
        if (name == null || name.isBlank()) {
            this.respond(exchange, 400, "world name is required in the path");
            return;
        }

        ServerInfo removed = this.getProxy().removeServerInfo(name);
        if (removed != null) {
            this.getLogger().info("Removed world '" + name + "'");
            this.respond(exchange, 200, "removed");
        } else {
            this.respond(exchange, 404, "no such world");
        }
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
