import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Throwaway Ed25519 keys for scripts/e2e_launcher_slots.py: writes primary, backup and foreign key pairs
 * (<name>.priv in PKCS#8, <name>.pub in X.509, both Base64) and trusted-keys.txt with the primary and backup public keys,
 * the file the launcher selector is built with (-Pheartbeat.trustedKeysFile). Never use these keys for a release, and
 * keep the directory outside the repository: the private files are written readable by their owner only.
 */
public class Keys {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        StringBuilder trusted = new StringBuilder("# throwaway e2e keys\n");
        for (String name : new String[] {"primary", "backup", "foreign"}) {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            Path priv = dir.resolve(name + ".priv");
            Files.writeString(priv, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            try {
                Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) {
                // the file system has no POSIX permissions (Windows): the per-user directory is all there is
            }
            Files.writeString(dir.resolve(name + ".pub"), pub);
            if (!name.equals("foreign")) {
                trusted.append(pub).append('\n');
            }
        }
        Files.writeString(dir.resolve("trusted-keys.txt"), trusted.toString());
    }
}
