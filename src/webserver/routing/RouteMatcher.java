package webserver.routing;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import webserver.config.ConfigLoader;

/** Converts request targets into routeable paths. */
public final class RouteMatcher {
    private final ConfigLoader.Config config;

    public RouteMatcher(ConfigLoader.Config config) {
        this.config = config;
    }

    public Match find(String rawTarget) {
        Target target = parseTarget(rawTarget);
        ConfigLoader.Route selected = config.routes().stream()
                .filter(route -> matches(route.path(), target.path()))
                .findFirst().orElse(null);
        return new Match(target, selected);
    }

    private static boolean matches(String routePath, String path) {
        if (routePath.equals("/") || path.equals(routePath)) return true;
        if (routePath.endsWith("/")) {
            String prefix = routePath.substring(0, routePath.length() - 1);
            return path.equals(prefix) || path.startsWith(routePath);
        }
        return path.startsWith(routePath + "/");
    }

    private static Target parseTarget(String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()) {
            throw new IllegalArgumentException("Empty target");
        }
        String[] pieces = rawTarget.split("\\?", 2);
        String rawPath = pieces[0];
        String query = pieces.length == 2 ? pieces[1] : "";

        String path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid target: " + rawPath);
        }
        return new Target(path, query);
    }

    public record Match(Target target, ConfigLoader.Route route) {}
    public record Target(String path, String query) {}
}