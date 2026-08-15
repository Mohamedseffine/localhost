import java.nio.file.Path;

/** Program entry point. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Path config = arguments(args);
            try (Server server = new Server(ConfigLoader.load(config))) {
                server.run();
            }
        } catch (IllegalArgumentException error) {
            System.err.println("Config error: " + error.getMessage());
            System.exit(2);
        } catch (Exception error) {
            System.err.println("Server error: " + error.getMessage());
            System.exit(1);
        }
    }

    private static Path arguments(String[] args) {
        if (args.length == 0) return Path.of("config.json");
        if (args.length == 2 && (args[0].equals("--config") || args[0].equals("-c"))) {
            return Path.of(args[1]);
        }
        if (args.length == 1 && args[0].startsWith("--config=")) {
            return Path.of(args[0].substring("--config=".length()));
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Usage: java -jar build/java-server.jar [--config config.json]");
            System.exit(0);
        }
        throw new IllegalArgumentException("Use --config <file>");
    }
}
