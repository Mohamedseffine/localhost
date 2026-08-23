import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the one configured CGI type with a ten-second limit. */
public final class CGIHandler {
    private CGIHandler() {}

    public static byte[] execute(ConfigLoader.Config config, Path script, String data,
                             String pathInfo) throws IOException {
        Path output = Files.createTempFile("java-server-cgi-", ".out");
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    config.cgi().command(), script.toString(), data));
            builder.directory(config.root().toFile());
            builder.environment().put("PATH_INFO", pathInfo);
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("CGI timeout");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("CGI interrupted", error);
            }
            byte[] result = Files.readAllBytes(output);
            if (process.exitValue() != 0) {
                throw new IOException("CGI failed: " + new String(result, StandardCharsets.UTF_8).trim());
            }
            return result;
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
