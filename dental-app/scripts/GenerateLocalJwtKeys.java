import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Creates a development key pair in the local .env file. */
class GenerateLocalJwtKeys {
    private static final String PRIVATE_KEY = "DENTAL_JWT_PRIVATE_KEY=";
    private static final String PUBLIC_KEY = "DENTAL_JWT_PUBLIC_KEY=";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("Run from the project root without arguments.");
        }
        Path env = Path.of(".env");
        List<String> lines = Files.exists(env)
                ? new ArrayList<>(Files.readAllLines(env, StandardCharsets.UTF_8))
                : new ArrayList<>();

        boolean hasPrivate = hasValue(lines, PRIVATE_KEY);
        boolean hasPublic = hasValue(lines, PUBLIC_KEY);
        if (hasPrivate && hasPublic) {
            System.out.println("Local JWT keys are already configured.");
            return;
        }
        if (hasPrivate || hasPublic) {
            throw new IllegalStateException("Configure both JWT keys in .env, or clear both entries to generate a new pair.");
        }

        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var base64 = Base64.getEncoder();
        lines.removeIf(line -> line.startsWith(PRIVATE_KEY) || line.startsWith(PUBLIC_KEY));
        lines.add(PRIVATE_KEY + base64.encodeToString(pair.getPrivate().getEncoded()));
        lines.add(PUBLIC_KEY + base64.encodeToString(pair.getPublic().getEncoded()));
        Files.write(env, lines, StandardCharsets.UTF_8);
        System.out.println("Local JWT keys written to .env.");
    }

    private static boolean hasValue(List<String> lines, String prefix) {
        return lines.stream().anyMatch(line -> line.startsWith(prefix)
                && !line.substring(prefix.length()).isBlank());
    }
}
