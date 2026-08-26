package webserver.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import webserver.http.HttpMethods;

/** Loads and validates the JSON configuration file. */
public final class ConfigLoader {
    private static final Set<Integer> ERROR_CODES = Set.of(400, 403, 404, 405, 413, 500);
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 307, 308);

    private ConfigLoader() {}

    public record Config(
            Path root, Path uploads, long maxBodySize, int requestTimeoutSeconds,
            Map<Integer, Path> errorPages, List<Route> routes,
            List<VirtualServer> servers, Cgi cgi
    ) {
        public Config {
            errorPages = Map.copyOf(errorPages);
            routes = List.copyOf(routes);
            servers = List.copyOf(servers);
        }

        public List<InetSocketAddress> listenAddresses() {
            return servers.stream()
                    .flatMap(s -> s.ports().stream().map(p -> new InetSocketAddress(s.address(), p)))
                    .distinct().toList();
        }

        public List<VirtualServer> serversFor(String address, int port) {
            return servers.stream()
                    .filter(s -> s.address().equals(address) && s.ports().contains(port))
                    .toList();
        }

        public VirtualServer selectServer(String hostHeader, List<VirtualServer> candidates) {
            if (hostHeader != null && !hostHeader.isBlank()) {
                String host = hostHeader.trim().toLowerCase(Locale.ROOT);
                int colon = host.indexOf(':');
                if (colon >= 0) host = host.substring(0, colon);
                for (VirtualServer server : candidates) {
                    for (String name : server.serverNames()) {
                        if (name.equalsIgnoreCase(host)) return server;
                    }
                }
            }
            return candidates.get(0);
        }
    }

    public record Route(
            String path, List<String> methods, String root, String defaultFile,
            String redirect, int redirectStatus, boolean directoryListing, boolean cgi
    ) {
        public Route {
            methods = List.copyOf(methods);
        }
    }

    public record VirtualServer(String address, List<Integer> ports, List<String> serverNames) {
        public VirtualServer {
            ports = List.copyOf(ports);
            serverNames = List.copyOf(serverNames);
        }

        public String name() {
            return serverNames.isEmpty() ? address : serverNames.get(0);
        }
    }

    public record Cgi(String extension, String command) {}

    public static Config load(Path configFile) throws IOException {
        Path file = configFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Config not found: " + file);

        Map<String, Object> json = object(new JsonParser(Files.readString(file, StandardCharsets.UTF_8)).parse(), "root");
        checkKeys(json, Set.of("root", "uploads", "max_body_size", "request_timeout_seconds",
                "error_pages", "cgi", "routes", "servers"), "root");

        Path base = file.getParent().toRealPath();
        Path root = checkDir(base, string(required(json, "root", "root"), "root"));
        Path uploads = root.resolve(string(required(json, "uploads", "root"), "uploads")).normalize();
        if (!uploads.startsWith(root)) throw new IllegalArgumentException("Uploads outside root");
        Files.createDirectories(uploads);
        uploads = uploads.toRealPath();
        if (!uploads.startsWith(root)) throw new IllegalArgumentException("Uploads realpath outside root");

        long maxBodySize = number(required(json, "max_body_size", "root"), "max_body_size");
        if (maxBodySize < 0) throw new IllegalArgumentException("Negative max_body_size");
        int timeout = Math.toIntExact(number(json.getOrDefault("request_timeout_seconds", 15L), "request_timeout_seconds"));
        if (timeout < 1 || timeout > 3600) throw new IllegalArgumentException("Timeout out of range: " + timeout);

        Map<String, Object> errorMap = object(required(json, "error_pages", "root"), "error_pages");
        Map<Integer, Path> errorPages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : errorMap.entrySet()) {
            int code = Integer.parseInt(entry.getKey());
            if (!ERROR_CODES.contains(code)) throw new IllegalArgumentException("Invalid error code: " + code);
            errorPages.put(code, checkFile(base, string(entry.getValue(), "error_pages." + code)));
        }
        if (!errorPages.keySet().containsAll(ERROR_CODES)) {
            throw new IllegalArgumentException("Missing required error pages");
        }

        Map<String, Object> cgiMap = object(required(json, "cgi", "root"), "cgi");
        checkKeys(cgiMap, Set.of("extension", "command"), "cgi");
        String cgiExt = string(required(cgiMap, "extension", "cgi"), "cgi.extension").toLowerCase(Locale.ROOT);
        if (!cgiExt.matches("^[a-z0-9]+$")) throw new IllegalArgumentException("Bad CGI ext");
        String cgiCmd = string(required(cgiMap, "command", "cgi"), "cgi.command");
        if (cgiCmd.isBlank()) throw new IllegalArgumentException("Empty CGI command");
        Cgi cgi = new Cgi(cgiExt, cgiCmd);

        List<Route> routes = new ArrayList<>();
        for (Object routeObj : list(required(json, "routes", "root"), "routes")) {
            Map<String, Object> r = object(routeObj, "route");
            checkKeys(r, Set.of("path", "methods", "root", "default_file", "redirect",
                    "redirect_status", "directory_listing", "cgi"), "route");

            String path = string(required(r, "path", "route"), "route.path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("Path must start with /");

            List<String> methods = new ArrayList<>();
            for (Object m : list(required(r, "methods", "route"), "route.methods")) {
                String method = string(m, "method").toUpperCase(Locale.ROOT);
                if (!HttpMethods.supported(method)) throw new IllegalArgumentException("Unsupported method: " + method);
                methods.add(method);
            }
            if (methods.isEmpty()) throw new IllegalArgumentException("Empty route methods");

            String routeRoot = r.containsKey("root") ? string(r.get("root"), "route.root") : ".";
            checkRelative(routeRoot, "route.root");
            String defaultFile = r.containsKey("default_file") ? string(r.get("default_file"), "route.default_file") : null;
            if (defaultFile != null) checkRelative(defaultFile, "route.default_file");

            String redirect = r.containsKey("redirect") ? string(r.get("redirect"), "route.redirect") : null;
            if (redirect != null && !redirect.startsWith("/") && !redirect.startsWith("http://") && !redirect.startsWith("https://")) {
                throw new IllegalArgumentException("Invalid redirect location");
            }
            int redirectStatus = Math.toIntExact(number(r.getOrDefault("redirect_status", 302L), "route.redirect_status"));
            if (!REDIRECT_CODES.contains(redirectStatus)) throw new IllegalArgumentException("Invalid redirect status");

            boolean listing = booleanValue(r.getOrDefault("directory_listing", Boolean.FALSE), "directory_listing");
            boolean routeCgi = booleanValue(r.getOrDefault("cgi", Boolean.FALSE), "route.cgi");
            routes.add(new Route(path, methods, routeRoot, defaultFile, redirect, redirectStatus, listing, routeCgi));
        }
        routes.sort((a, b) -> Integer.compare(b.path().length(), a.path().length()));

        List<VirtualServer> servers = new ArrayList<>();
        Set<String> allNames = new HashSet<>();
        for (Object sObj : list(required(json, "servers", "root"), "servers")) {
            try {
            Map<String, Object> s = object(sObj, "server");
                checkKeys(s, Set.of("address", "ports", "server_names"), "server");
                String address = string(required(s, "address", "server"), "server.address");
                if (address.isBlank()) throw new IllegalArgumentException("Empty address");

                List<Integer> ports = new ArrayList<>();
                for (Object pObj : list(required(s, "ports", "server"), "server.ports")) {
                    int p = Math.toIntExact(number(pObj, "port"));
                    if (p < 1 || p > 65535 || ports.contains(p)) throw new IllegalArgumentException("Bad port: " + p);
                    ports.add(p);
                }
                if (ports.isEmpty()) throw new IllegalArgumentException("Empty server ports");

                List<String> names = new ArrayList<>();
                if (s.containsKey("server_names")) {
                    for (Object nObj : list(s.get("server_names"), "server_names")) {
                        String name = string(nObj, "server_name");
                        String norm = name.toLowerCase(Locale.ROOT);
                        if (!name.matches("^[A-Za-z0-9.-]+$") || allNames.contains(norm)) {
                            throw new IllegalArgumentException("Duplicate/invalid name: " + name);
                        }
                        allNames.add(norm);
                        names.add(name);
                    }
                }
                servers.add(new VirtualServer(address, ports, names));
            } catch (IllegalArgumentException e) {
                System.err.println("Skipping invalid server: " + e.getMessage());
            }
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("No valid virtual servers");

        return new Config(root, uploads, maxBodySize, timeout, errorPages, routes, servers, cgi);
    }

    private static Path checkDir(Path base, String dirStr) throws IOException {
        Path p = base.resolve(dirStr).normalize();
        if (!p.startsWith(base) || !Files.isDirectory(p)) throw new IllegalArgumentException("Bad dir: " + dirStr);
        return p.toRealPath();
    }

    private static Path checkFile(Path base, String fileStr) throws IOException {
        Path p = base.resolve(fileStr).normalize();
        if (!p.startsWith(base) || !Files.isRegularFile(p)) throw new IllegalArgumentException("Bad file: " + fileStr);
        Path real = p.toRealPath();
        if (!real.startsWith(base)) throw new IllegalArgumentException("File outside base: " + fileStr);
        return real;
    }

    private static void checkRelative(String pathStr, String field) {
        Path p = Path.of(pathStr).normalize();
        if (p.isAbsolute() || p.startsWith("..")) throw new IllegalArgumentException(field + " not relative");
    }

    private static Object required(Map<String, Object> object, String key, String context) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("Missing required field '" + key + "' in " + context);
        }
        return object.get(key);
    }

    private static String string(Object o, String field) {
        if (o instanceof String s) return s;
        throw new IllegalArgumentException("Expected string for " + field);
    }

    private static long number(Object o, String field) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i.longValue();
        throw new IllegalArgumentException("Expected integer for " + field);
    }

    private static boolean booleanValue(Object o, String field) {
        if (o instanceof Boolean b) return b;
        throw new IllegalArgumentException("Expected boolean for " + field);
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Expected object for " + field);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected string key in " + field);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> list(Object value, String field) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException("Expected list for " + field);
        }
        return new ArrayList<>(raw);
    }

    private static void checkKeys(Map<String, Object> m, Set<String> allowed, String ctx) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) throw new IllegalArgumentException("Unknown field '" + k + "' in " + ctx);
        }
    }
}
