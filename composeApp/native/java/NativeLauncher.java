import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import kotlin.jvm.internal.Reflection;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.SerializersKt;
import kotlinx.serialization.json.Json;

public final class NativeLauncher {
    public static void main(String[] args) throws Exception {
        Path executable = Path.of(ProcessHandle.current().info().command().orElseThrow());
        System.setProperty("java.home", executable.getParent().resolve("runtime").toString());
        boolean smoke = Arrays.asList(args).contains("--smoke-test");
        if (smoke || Arrays.asList(args).contains("--check-serialization")) {
            checkSerialization();
            if (!smoke) return;
            String prefsNode = "/hue-manager-native-smoke/" + UUID.randomUUID();
            System.setProperty("hue.preferencesNode", prefsNode);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Preferences.userRoot().node(prefsNode).removeNode();
                    Preferences.userRoot().flush();
                } catch (Exception error) {
                    error.printStackTrace();
                }
            }));
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                error.printStackTrace();
                System.exit(1);
            });
            Thread watcher = new Thread(() -> {
                try {
                    AtomicBoolean visible = new AtomicBoolean();
                    for (int attempt = 0; attempt < 100; attempt++) {
                        Thread.sleep(100);
                        SwingUtilities.invokeAndWait(() -> {
                            for (Window window : Window.getWindows()) {
                                if (window.isShowing() && window.getWidth() > 100 && window.getHeight() > 100) {
                                    visible.set(true);
                                }
                            }
                        });
                        if (visible.get()) {
                            Thread.sleep(2000);
                            System.out.println("PASS: native Compose window opened");
                            System.exit(0);
                        }
                    }
                    throw new AssertionError("Native Compose window did not open within 10 seconds");
                } catch (Throwable error) {
                    error.printStackTrace();
                    System.exit(1);
                }
            }, "native-smoke-test");
            watcher.setDaemon(true);
            watcher.start();
        }
        io.github.commandertvis.huemanager.MainKt.main();
    }

    private static void checkSerialization() throws Exception {
        int count = 0;
        try (var reader = new BufferedReader(new InputStreamReader(
                NativeLauncher.class.getResourceAsStream("/native-models.txt"), StandardCharsets.UTF_8))) {
            for (String name; (name = reader.readLine()) != null;) {
                Class<?> type = Class.forName(name);
                KSerializer<Object> serializer = SerializersKt.serializer(
                    Json.Default.getSerializersModule(), Reflection.typeOf(type));
                SerializersKt.serializer(Json.Default.getSerializersModule(), type);
                if (name.endsWith(".StatusResponse")) {
                    String fixture = "{\"connected\":true,\"bridgeIp\":null,\"needsLinking\":false,\"automationState\":\"AWAKE\",\"entertainmentActive\":false}";
                    Object status = Json.Default.decodeFromString(serializer, fixture);
                    if (!Json.Default.encodeToString(serializer, status).contains("\"connected\":true")) {
                        throw new AssertionError("StatusResponse round trip failed");
                    }
                }
                count++;
            }
        }
        if (count == 0) throw new AssertionError("No serializers tested");
        System.out.println("PASS: " + count + " serializers via KType and Java Type; StatusResponse decode/encode");
    }
}
