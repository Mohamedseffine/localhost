import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** URI target decoding and route matching. */
public final class RouteMatcher {
    private final ConfigLoader.Config config;

    public RouteMatcher(ConfigLoader.Config config) {
        this.config = config;
    }

    public Match find(String rawTarget) {
        Target target = parseTarget(rawTarget);
        for (ConfigLoader.Route route : config.routes()) {
            if (matches(route.path(), target.path())) {
                return new Match(target, route);
            }
        }
        return new Match(target, null);
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
        int q = rawTarget.indexOf('?');
        String rawPath = q < 0 ? rawTarget : rawTarget.substring(0, q);
        String query = q < 0 ? "" : rawTarget.substring(q + 1);

        String path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid target: " + rawPath);
        }
        return new Target(path, query);
    }

    public record Match(Target target, ConfigLoader.Route route) {}
    public record Target(String path, String query) {}
}