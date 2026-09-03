import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import utils.Json;

/**
 * Loads, validates, and models the server configuration.
 */
public final class ConfigLoader {
    private ConfigLoader() {}

    public record RouteConfig(
            String path,
            List<String> methods,
            String root,
            String defaultFile,
            String redirect,
            int redirectStatus,
            boolean directoryListing,
            boolean cgi
    ) {}

    public record VirtualServer(
            String address,
            List<Integer> ports,
            List<String> serverNames
    ) {
        public String defaultName() {
            return serverNames.isEmpty() ? address : serverNames.get(0);
        }

        public boolean matchesHost(String hostHeader) {
            if (hostHeader == null || hostHeader.isBlank()) return false;
            String host = hostHeader.trim().toLowerCase(Locale.ROOT);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            for (String name : serverNames) {
                if (name.equalsIgnoreCase(host)) return true;
            }
            return false;
        }
    }

    public record CgiConfig(String extension, String command) {}

    public record ServerConfig(
            Path rootDir,
            Path uploadDir,
            long maxBodySize,
            int requestTimeoutSeconds,
            Map<Integer, Path> errorPages,
            List<RouteConfig> routes,
            List<VirtualServer> servers,
            CgiConfig cgi
    ) {
        public List<InetSocketAddress> getListenAddresses() {
            List<InetSocketAddress> addrs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (VirtualServer s : servers) {
                for (int port : s.ports()) {
                    String key = s.address() + ":" + port;
                    if (seen.add(key)) {
                        addrs.add(new InetSocketAddress(s.address(), port));
                    }
                }
            }
            return addrs;
        }

        public List<VirtualServer> getServersFor(String address, int port) {
            List<VirtualServer> matched = new ArrayList<>();
            for (VirtualServer s : servers) {
                if (s.address().equals(address) && s.ports().contains(port)) {
                    matched.add(s);
                }
            }
            return matched;
        }

        public VirtualServer resolveServer(String hostHeader, List<VirtualServer> candidates) {
            if (candidates == null || candidates.isEmpty()) return null;
            if (hostHeader != null && !hostHeader.isBlank()) {
                for (VirtualServer s : candidates) {
                    if (s.matchesHost(hostHeader)) {
                        return s;
                    }
                }
            }
            return candidates.get(0);
        }
    }

    public static ServerConfig load(Path configFilePath) throws IOException {
        Path absPath = configFilePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absPath)) {
            throw new IllegalArgumentException("Configuration file not found: " + absPath);
        }

        String jsonText = Files.readString(absPath, StandardCharsets.UTF_8);
        Map<String, Object> rootObj = Json.parseObject(jsonText);
        Path basePath = absPath.getParent();

        // 1. Root & uploads directory
        String rootStr = getString(rootObj, "root", "public");
        Path rootDir = basePath.resolve(rootStr).normalize();
        if (!Files.isDirectory(rootDir)) {
            throw new IllegalArgumentException("Root directory does not exist: " + rootDir);
        }
        rootDir = rootDir.toRealPath();

        String uploadsStr = getString(rootObj, "uploads", "uploads");
        Path uploadDir = rootDir.resolve(uploadsStr).normalize();
        if (!uploadDir.startsWith(rootDir)) {
            throw new IllegalArgumentException("Uploads directory must be inside root directory");
        }
        Files.createDirectories(uploadDir);
        uploadDir = uploadDir.toRealPath();

        // 2. Limits & timeouts
        long maxBodySize = getLong(rootObj, "max_body_size", 1048576L);
        if (maxBodySize < 0) throw new IllegalArgumentException("max_body_size cannot be negative");

        int timeout = (int) getLong(rootObj, "request_timeout_seconds", 15L);
        if (timeout < 1) timeout = 15;

        // 3. Error pages
        Map<Integer, Path> errorPages = new HashMap<>();
        if (rootObj.get("error_pages") instanceof Map<?, ?> epMap) {
            for (Map.Entry<?, ?> e : epMap.entrySet()) {
                try {
                    int code = Integer.parseInt(e.getKey().toString());
                    Path pagePath = basePath.resolve(e.getValue().toString()).normalize();
                    if (Files.isRegularFile(pagePath)) {
                        errorPages.put(code, pagePath.toRealPath());
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 4. CGI
        CgiConfig cgi = new CgiConfig("py", "python3");
        if (rootObj.get("cgi") instanceof Map<?, ?> cgiMap) {
            String ext = getString(cgiMap, "extension", "py");
            String cmd = getString(cgiMap, "command", "python3");
            cgi = new CgiConfig(ext, cmd);
        }

        // 5. Routes
        List<RouteConfig> routes = new ArrayList<>();
        if (rootObj.get("routes") instanceof List<?> routeList) {
            for (Object ro : routeList) {
                if (ro instanceof Map<?, ?> rm) {
                    String path = getString(rm, "path", "/");
                    if (!path.startsWith("/")) path = "/" + path;

                    List<String> methods = new ArrayList<>();
                    if (rm.get("methods") instanceof List<?> ml) {
                        for (Object m : ml) {
                            if (m != null) methods.add(m.toString().toUpperCase(Locale.ROOT));
                        }
                    }
                    if (methods.isEmpty()) {
                        methods.add("GET");
                    }

                    String routeRoot = getString(rm, "root", ".");
                    String defaultFile = rm.containsKey("default_file") ? String.valueOf(rm.get("default_file")) : null;
                    String redirect = rm.containsKey("redirect") ? String.valueOf(rm.get("redirect")) : null;
                    int redirectStatus = (int) getLong(rm, "redirect_status", 302L);
                    boolean dirListing = getBoolean(rm, "directory_listing", false);
                    boolean isCgi = getBoolean(rm, "cgi", false);

                    routes.add(new RouteConfig(path, methods, routeRoot, defaultFile, redirect, redirectStatus, dirListing, isCgi));
                }
            }
        }
        // Sort routes by path length descending (longest prefix matching)
        routes.sort(Comparator.comparingInt((RouteConfig r) -> r.path().length()).reversed());

        // 6. Virtual Servers
        List<VirtualServer> servers = new ArrayList<>();
        if (rootObj.get("servers") instanceof List<?> serverList) {
            for (Object so : serverList) {
                if (so instanceof Map<?, ?> sm) {
                    try {
                        String addr = getString(sm, "address", "127.0.0.1");
                        List<Integer> ports = new ArrayList<>();
                        Set<Integer> uniquePorts = new HashSet<>();
                        if (sm.get("ports") instanceof List<?> pl) {
                            for (Object po : pl) {
                                if (po instanceof Number num) {
                                    int p = num.intValue();
                                    if (p > 0 && p <= 65535 && uniquePorts.add(p)) {
                                        ports.add(p);
                                    }
                                }
                            }
                        }
                        if (ports.isEmpty()) {
                            System.err.println("Warning: Server on address " + addr + " has no valid ports; skipping.");
                            continue;
                        }

                        List<String> names = new ArrayList<>();
                        if (sm.get("server_names") instanceof List<?> nl) {
                            for (Object no : nl) {
                                if (no != null) names.add(no.toString().trim());
                            }
                        }

                        servers.add(new VirtualServer(addr, ports, names));
                    } catch (Exception e) {
                        System.err.println("Warning: Skipping invalid server block: " + e.getMessage());
                    }
                }
            }
        }

        if (servers.isEmpty()) {
            throw new IllegalArgumentException("No valid servers defined in configuration");
        }

        return new ServerConfig(rootDir, uploadDir, maxBodySize, timeout, errorPages, routes, servers, cgi);
    }

    private static String getString(Map<?, ?> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    private static long getLong(Map<?, ?> map, String key, long defaultVal) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static boolean getBoolean(Map<?, ?> map, String key, boolean defaultVal) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v != null) return Boolean.parseBoolean(v.toString());
        return defaultVal;
    }
}
