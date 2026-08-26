package webserver.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import webserver.config.ConfigLoader;

/** Runs a configured CGI command in an isolated working process. */
public final class CGIHandler {
    private static final long TIMEOUT_SECONDS = 10L;

    private CGIHandler() {}

    public static byte[] execute(ConfigLoader.Config config, Path script, String data, String pathInfo)
            throws IOException {
        Path temp = Files.createTempFile("cgi-out-", ".tmp");
        Process process = null;
        try {
                process = start(config, script, data, pathInfo, temp);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("CGI timeout");
            }

            byte[] output = Files.readAllBytes(temp);
            if (process.exitValue() != 0) {
                throw new IOException("CGI error: " + new String(output, StandardCharsets.UTF_8).trim());
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("CGI interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(temp);
        }
    }

    private static Process start(ConfigLoader.Config config, Path script, String data,
            String pathInfo, Path output) throws IOException {
        ProcessBuilder command = new ProcessBuilder(config.cgi().command(), script.toString(),
                data == null ? "" : data);
        command.directory(config.root().toFile());
        command.environment().put("PATH_INFO", pathInfo == null ? "" : pathInfo);
        command.redirectErrorStream(true);
        command.redirectOutput(output.toFile());
        return command.start();
    }
}
