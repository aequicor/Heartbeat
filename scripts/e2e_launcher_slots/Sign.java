import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Signs the launcher record like build-logic does: Sign <priv file> <id> <version> <sha256> <kind> <launcherApi>. */
public class Sign {
    public static void main(String[] a) throws Exception {
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(Path.of(a[0])).trim())));
        String text = "heartbeat-launcher-record-v1\nid=" + a[1] + "\nversion=" + a[2] + "\nsha256=" + a[3]
            + "\nkind=" + a[4] + "\nlauncherApi=" + a[5] + "\n";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(text.getBytes(StandardCharsets.UTF_8));
        System.out.print(Base64.getEncoder().encodeToString(signer.sign()));
    }
}
