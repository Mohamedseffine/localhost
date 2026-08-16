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

/** Loads and validates the single JSON configuration file. */
public final class ConfigLoader {
    private static final Set<Integer> ERROR_CODES = Set.of(400, 403, 404, 405, 413, 500);

    private ConfigLoader() {}

    public record Config(Path root, Path uploads, long maxBodySize, int requestTimeoutSeconds,
                         Map<Integer, Path> errorPages, List<Route> routes,
                         List<VirtualServer> servers, Cgi cgi) {
        public List<InetSocketAddress> listenAddresses() {
            return servers.stream()
                    .flatMap(server -> server.ports().stream()
                            .map(port -> new InetSocketAddress(server.address(), port)))
                    .distinct().toList();
        }

        public List<VirtualServer> serversFor(String address, int port) {
            return servers.stream()
                    .filter(server -> server.address().equals(address) && server.ports().contains(port))
                    .toList();
        }

        public VirtualServer selectServer(String hostHeader, List<VirtualServer> candidates) {
            String host = hostHeader == null ? "" : hostHeader.trim().toLowerCase(Locale.ROOT);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            for (VirtualServer server : candidates) {
                for (String name : server.serverNames()) {
                    if (name.equalsIgnoreCase(host)) return server;
                }
            }
            return candidates.get(0);
        }
    }

    public record Route(String path, List<String> methods, String root, String defaultFile,
                        String redirect, int redirectStatus, boolean directoryListing,
                        boolean cgi) {}

    public record VirtualServer(String address, List<Integer> ports, List<String> serverNames) {
        public String name() {
            return serverNames.isEmpty() ? address : serverNames.get(0);
        }
    }

    public record Cgi(String extension, String command) {}

    public static Config load(Path file) throws IOException {
        Path configFile = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("Configuration file not found: " + configFile);
        }
        Map<String, Object> json = object(new JsonParser(
            Files.readString(configFile, StandardCharsets.UTF_8)).parse(), "root");
        keys(json, Set.of("root", "uploads", "max_body_size", "request_timeout_seconds",
                "error_pages", "cgi", "routes", "servers"), "root");

        Path base = configFile.getParent().toRealPath();
        Path root = safeDirectory(base, text(required(json, "root"), "root"));
        Path uploads = root.resolve(text(required(json, "uploads"), "uploads")).normalize();
        if (!uploads.startsWith(root)) throw new IllegalArgumentException("uploads must stay inside root");
        Files.createDirectories(uploads);
        uploads = uploads.toRealPath();
        if (!uploads.startsWith(root)) throw new IllegalArgumentException("uploads resolves outside root");

        long maxBodySize = integer(required(json, "max_body_size"), "max_body_size");
        if (maxBodySize < 0) throw new IllegalArgumentException("max_body_size cannot be negative");
        int timeout = Math.toIntExact(integer(json.getOrDefault("request_timeout_seconds", 15L),
                "request_timeout_seconds"));
        if (timeout < 1 || timeout > 3600) {
            throw new IllegalArgumentException("request_timeout_seconds must be between 1 and 3600");
        }

        Map<String, Object> errorObject = object(required(json, "error_pages"), "error_pages");
        Map<Integer, Path> errorPages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : errorObject.entrySet()) {
            final int code;
            try {
                code = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid error status: " + entry.getKey());
            }
            if (!ERROR_CODES.contains(code)) throw new IllegalArgumentException("Unsupported error status: " + code);
            errorPages.put(code, safeFile(base, text(entry.getValue(), "error page")));
        }
        if (!errorPages.keySet().containsAll(ERROR_CODES)) {
            throw new IllegalArgumentException("error_pages must define 400, 403, 404, 405, 413, and 500");
        }

        Map<String, Object> cgiObject = object(required(json, "cgi"), "cgi");
        keys(cgiObject, Set.of("extension", "command"), "cgi");
        String extension = text(required(cgiObject, "extension"), "cgi.extension").toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]+")) throw new IllegalArgumentException("Invalid CGI extension");
        String command = text(required(cgiObject, "command"), "cgi.command");
        if (command.isBlank()) throw new IllegalArgumentException("CGI command cannot be empty");
        Cgi cgi = new Cgi(extension, command);

        List<Route> routes = new ArrayList<>();
        for (Object value : array(required(json, "routes"), "routes")) {
            Map<String, Object> route = object(value, "route");
            keys(route, Set.of("path", "methods", "root", "default_file", "redirect",
                    "redirect_status", "directory_listing", "cgi"), "route");
            String path = text(required(route, "path"), "route.path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("Route path must start with /");
            List<String> methods = strings(required(route, "methods"), "route.methods").stream()
                    .map(method -> method.toUpperCase(Locale.ROOT)).toList();
            if (methods.isEmpty() || !Set.of("GET", "POST", "DELETE").containsAll(methods)) {
                throw new IllegalArgumentException("Routes only support GET, POST, and DELETE");
            }
            String routeRoot = optionalText(route, "root", ".");
            safeRelative(routeRoot, "route.root");
            String defaultFile = optionalText(route, "default_file", null);
            if (defaultFile != null) safeRelative(defaultFile, "route.default_file");
            String redirect = optionalText(route, "redirect", null);
            if (redirect != null && !redirect.startsWith("/")
                    && !redirect.startsWith("http://") && !redirect.startsWith("https://")) {
                throw new IllegalArgumentException("redirect must start with /, http://, or https://");
            }
            int redirectStatus = Math.toIntExact(integer(route.getOrDefault("redirect_status", 302L),
                    "redirect_status"));
            if (!Set.of(301, 302, 307, 308).contains(redirectStatus)) {
                throw new IllegalArgumentException("redirect_status must be 301, 302, 307, or 308");
            }
            boolean listing = bool(route.getOrDefault("directory_listing", Boolean.FALSE), "directory_listing");
            boolean routeCgi = bool(route.getOrDefault("cgi", Boolean.FALSE), "route.cgi");
            routes.add(new Route(path, List.copyOf(methods), routeRoot, defaultFile,
                    redirect, redirectStatus, listing, routeCgi));
        }
        routes.sort((left, right) -> Integer.compare(right.path().length(), left.path().length()));

        List<VirtualServer> servers = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object value : array(required(json, "servers"), "servers")) {
            try {
                Map<String, Object> server = object(value, "server");
                keys(server, Set.of("address", "ports", "server_names"), "server");
                String address = text(required(server, "address"), "server.address");
                if (address.isBlank()) throw new IllegalArgumentException("Server address cannot be empty");
                List<Integer> ports = integers(required(server, "ports"), "server.ports");
                if (ports.isEmpty() || new HashSet<>(ports).size() != ports.size()) {
                    throw new IllegalArgumentException("A server must have unique ports");
                }
                for (int port : ports) {
                    if (port < 1 || port > 65535) {
                        throw new IllegalArgumentException("Invalid port: " + port);
                    }
                }
                List<String> serverNames = server.containsKey("server_names")
                        ? strings(server.get("server_names"), "server.server_names") : List.of();
                Set<String> normalizedNames = new HashSet<>();
                for (String name : serverNames) {
                    String normalized = name.toLowerCase(Locale.ROOT);
                    if (!name.matches("[A-Za-z0-9.-]+") || !normalizedNames.add(normalized)
                            || names.contains(normalized)) {
                        throw new IllegalArgumentException("Invalid or duplicate server name: " + name);
                    }
                }
                names.addAll(normalizedNames);
                servers.add(new VirtualServer(address, List.copyOf(ports), List.copyOf(serverNames)));
            } catch (IllegalArgumentException | ArithmeticException error) {
                System.err.println("Ignoring invalid server configuration: " + error.getMessage());
            }
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("At least one valid server is required");
        return new Config(root, uploads, maxBodySize, timeout, Map.copyOf(errorPages),
                List.copyOf(routes), List.copyOf(servers), cgi);
    }

    private static Path safeDirectory(Path base, String value) throws IOException {
        Path path = base.resolve(value).normalize();
        if (!path.startsWith(base) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Invalid directory: " + value);
        }
        return path.toRealPath();
    }

    private static Path safeFile(Path base, String value) throws IOException {
        Path path = base.resolve(value).normalize();
        if (!path.startsWith(base) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Invalid file: " + value);
        }
        Path real = path.toRealPath();
        if (!real.startsWith(base)) throw new IllegalArgumentException("File resolves outside project: " + value);
        return real;
    }

    private static void safeRelative(String value, String field) {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException(field + " must be a safe relative path");
        }
    }

    private static Object required(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) throw new IllegalArgumentException("Missing config field: " + key);
        return values.get(key);
    }

    private static String optionalText(Map<String, Object> values, String key, String fallback) {
        return values.containsKey(key) ? text(values.get(key), key) : fallback;
    }

    private static String text(Object value, String field) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static long integer(Object value, String field) {
        if (value instanceof Long number) return number;
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static boolean bool(Object value, String field) {
        if (value instanceof Boolean result) return result;
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static List<String> strings(Object value, String field) {
        List<String> result = new ArrayList<>();
        for (Object item : array(value, field)) result.add(text(item, field));
        return result;
    }

    private static List<Integer> integers(Object value, String field) {
        List<Integer> result = new ArrayList<>();
        for (Object item : array(value, field)) result.add(Math.toIntExact(integer(item, field)));
        return result;
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(field + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) result.put((String) entry.getKey(), entry.getValue());
        return result;
    }

    private static List<Object> array(Object value, String field) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(field + " must be an array");
        return new ArrayList<>(list);
    }

    private static void keys(Map<String, Object> values, Set<String> allowed, String field) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown field " + field + "." + key);
        }
    }

}
