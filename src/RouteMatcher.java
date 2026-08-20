import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Converts request targets into safe paths and selects configured routes. */
public final class RouteMatcher {
    private final ConfigLoader.Config config;

    public RouteMatcher(ConfigLoader.Config config) {
        this.config = config;
    }

    public Match find(String rawTarget) {
        Target target = parseTarget(rawTarget);
        for (ConfigLoader.Route route : config.routes()) {
            if (owns(route.path(), target.path())) return new Match(target, route);
        }
        return new Match(target, null);
    }

    private static boolean owns(String route, String path) {
        return route.equals("/") || path.equals(route)
                || (route.endsWith("/") && path.equals(route.substring(0, route.length() - 1)))
                || (route.endsWith("/") && path.startsWith(route))
                || (!route.endsWith("/") && path.startsWith(route + "/"));
    }

    private static Target parseTarget(String raw) {
        int question = raw.indexOf('?');
        String rawPath = question < 0 ? raw : raw.substring(0, question);
        String query = question < 0 ? "" : raw.substring(question + 1);
        try {
            String path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid request target");
            }
            return new Target(path, query);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid request target", error);
        }
    }

    public record Match(Target target, ConfigLoader.Route route) {}
    public record Target(String path, String query) {}
}