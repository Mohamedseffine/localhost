package webserver.delivery;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import webserver.config.ConfigLoader;
import webserver.http.HttpResponse;
import webserver.response.ResponseFactory;

/** Resolves configured resources and performs filesystem-backed delivery work. */
public final class ResourceService {
    private final ConfigLoader.Config config;
    private final ResponseFactory responses;

    public ResourceService(ConfigLoader.Config config, ResponseFactory responses) {
        this.config = config;
        this.responses = responses;
    }

    public Resource resolve(String requestPath, ConfigLoader.Route route, boolean allowDirectory) throws IOException {
        String relative = relativePath(requestPath, route.path());
        Path routeRoot = config.root().resolve(route.root()).normalize();
        Path candidate = routeRoot.resolve(relative).normalize();
        if (!routeRoot.startsWith(config.root()) || !candidate.startsWith(routeRoot)) {
            throw new Forbidden();
        }
        if (!Files.exists(candidate)) return new Resource(candidate, 404, null);

        Path realRoot = routeRoot.toRealPath();
        Path realPath = candidate.toRealPath();
        if (!realRoot.startsWith(config.root()) || !realPath.startsWith(realRoot)) {
            throw new Forbidden();
        }

        if (!Files.isDirectory(realPath)) return new Resource(realPath, 200, null);
        if (!requestPath.endsWith("/")) return new Resource(realPath, 301, requestPath + "/");
        if (allowDirectory) return new Resource(realPath, 200, null);

        if (route.defaultFile() != null) {
            Path defaultPath = realPath.resolve(route.defaultFile()).normalize();
            if (defaultPath.startsWith(config.root()) && Files.isRegularFile(defaultPath)) {
                Path realDefault = defaultPath.toRealPath();
                if (!realDefault.startsWith(realPath)) throw new Forbidden();
                return new Resource(realDefault, 200, null);
            }
        }
        int status = route.directoryListing() ? 200 : 403;
        return new Resource(realPath, status, null);
    }

    public HttpResponse directoryListing(String requestPath, Path directory) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><title>Files</title><h1>Files</h1><ul>");
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                html.append("<li><a href=\"").append(requestPath).append(encoded).append("\">")
                        .append(escapeHtml(name)).append("</a></li>");
            }
        }
        html.append("</ul>");
        return responses.bytes(200, "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
    }

    public String multipart(byte[] body, String contentType) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        final List<MultipartParser.Part> parts;
        try {
            parts = MultipartParser.read(body, contentType);
        } catch (IllegalArgumentException e) {
            throw new BadRequest();
        }

        for (MultipartParser.Part part : parts) {
            if (part.filename() == null || part.filename().isEmpty()) {
                values.put(part.name(), new String(part.content(), StandardCharsets.UTF_8));
                continue;
            }
            String extension = fileExtension(part.filename());
            Path output = config.uploads().resolve(UUID.randomUUID() + extension).normalize();
            if (!output.startsWith(config.uploads())) throw new Forbidden();
            Files.write(output, part.content(), StandardOpenOption.CREATE_NEW);
            values.put(part.name(), config.root().relativize(output).toString().replace('\\', '/'));
        }
        return json(values);
    }

    public void verifyCgi(Path script) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals(config.cgi().extension())) throw new BadRequest();
    }

    public static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static String relativePath(String requestPath, String routePath) {
        String relative = routePath.equals("/") ? requestPath.substring(1)
                : routePath.endsWith("/") && requestPath.equals(routePath.substring(0, routePath.length() - 1)) ? ""
                : requestPath.substring(routePath.length());
        while (relative.startsWith("/")) relative = relative.substring(1);
        return relative;
    }

    private static String fileExtension(String filename) {
        String basename = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dot = basename.lastIndexOf('.');
        if (dot < 0) return "";
        String extension = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("^[a-z0-9]{1,10}$") ? "." + extension : "";
    }

    private static String json(Map<String, String> values) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        return result.append('}').toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    public record Resource(Path path, int status, String redirect) {}

    public static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static final class Forbidden extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
