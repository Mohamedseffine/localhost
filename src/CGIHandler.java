import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Multi-interpreter CGI execution engine using ProcessBuilder.
 * Supports Python, Shell, Java, and custom configured scripts.
 */
public final class CGIHandler {
    private static final long CGI_TIMEOUT_SECONDS = 10L;

    private CGIHandler() {}

    /**
     * Executes a CGI script and captures its standard output.
     */
    public static byte[] execute(
            ConfigLoader.ServerConfig config,
            Path scriptFile,
            HttpRequest request,
            String dataPayload,
            String pathInfo,
            ConfigLoader.VirtualServer server,
            int port
    ) throws IOException {
        String ext = getExtension(scriptFile);
        String interpreter = resolveInterpreter(ext, config.cgi());

        ProcessBuilder pb;
        if (interpreter == null || interpreter.isEmpty()) {
            // Direct executable (e.g. binary or script with shebang)
            pb = new ProcessBuilder(scriptFile.toAbsolutePath().toString(), dataPayload != null ? dataPayload : "");
        } else {
            pb = new ProcessBuilder(interpreter, scriptFile.toAbsolutePath().toString(), dataPayload != null ? dataPayload : "");
        }

        pb.directory(config.rootDir().toFile());
        Map<String, String> env = pb.environment();

        // Standard CGI environment variables
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("REQUEST_METHOD", request.method());
        env.put("PATH_INFO", pathInfo != null ? pathInfo : "");
        env.put("SCRIPT_FILENAME", scriptFile.toAbsolutePath().toString());
        env.put("SCRIPT_NAME", scriptFile.getFileName().toString());
        env.put("QUERY_STRING", request.queryString());
        env.put("SERVER_NAME", server != null ? server.defaultName() : "localhost");
        env.put("SERVER_PORT", String.valueOf(port));

        if (!request.header("content-type").isEmpty()) {
            env.put("CONTENT_TYPE", request.header("content-type"));
        }
        if (!request.header("content-length").isEmpty()) {
            env.put("CONTENT_LENGTH", request.header("content-length"));
        } else if (request.body().length > 0) {
            env.put("CONTENT_LENGTH", String.valueOf(request.body().length));
        }

        // Forward HTTP headers as HTTP_*
        for (Map.Entry<String, String> h : request.headers().entrySet()) {
            String name = "HTTP_" + h.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
            env.put(name, h.getValue());
        }

        pb.redirectErrorStream(true);

        Process process = pb.start();
        try {
            // Write payload to stdin if present
            if (request.body().length > 0) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(request.body());
                    os.flush();
                } catch (IOException ignored) {}
            } else if (dataPayload != null && !dataPayload.isEmpty()) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(dataPayload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {}
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {}
            }

            boolean finished = process.waitFor(CGI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("CGI script execution timed out (" + CGI_TIMEOUT_SECONDS + "s)");
            }

            byte[] output;
            try (InputStream is = process.getInputStream()) {
                output = is.readAllBytes();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String err = new String(output, StandardCharsets.UTF_8).trim();
                throw new IOException("CGI process exited with code " + exitCode + ": " + err);
            }

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("CGI execution interrupted", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String resolveInterpreter(String ext, ConfigLoader.CgiConfig defaultCgi) {
        if (ext.equalsIgnoreCase(defaultCgi.extension())) {
            return defaultCgi.command();
        }
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "py" -> "python3";
            case "sh" -> "/bin/sh";
            case "java" -> "java";
            case "php" -> "php";
            case "pl" -> "perl";
            case "js" -> "node";
            default -> null;
        };
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}
