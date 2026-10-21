import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Writes a copy of a Java trust store without the roots listed (by SHA-256) in distrusted-ca-roots.txt.
 * Usage: java DistrustCaRoots.java <fingerprint list> <source cacerts> <target cacerts>
 */
public class DistrustCaRoots {
    public static void main(String[] args) throws Exception {
        Set<String> distrusted = new HashSet<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (!line.isBlank() && !line.startsWith("#")) distrusted.add(line.split(" ", 2)[0].toUpperCase());
        }
        char[] password = "changeit".toCharArray();
        KeyStore store = KeyStore.getInstance(new File(args[1]), password);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (String alias : Collections.list(store.aliases())) {
            Certificate certificate = store.getCertificate(alias);
            if (certificate == null) continue;
            String fingerprint = HexFormat.of().withUpperCase().formatHex(sha256.digest(certificate.getEncoded()));
            if (distrusted.contains(fingerprint)) {
                store.deleteEntry(alias);
                System.out.println("Distrusted " + alias);
            }
        }
        Files.createDirectories(Path.of(args[2]).toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(Path.of(args[2]))) {
            store.store(out, password);
        }
    }
}
