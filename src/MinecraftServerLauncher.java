import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.Arrays;

import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class MinecraftServerLauncher {
    public static void main(String[] args) throws Exception {
        var jarPath = Paths.get(
            MinecraftServerLauncher.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
        ).toString();

        var jar = new JarFile(jarPath);
        Files.createDirectories(Paths.get("libs/"));
        for (var entry : jar.stream().collect(Collectors.toList())) {
            var entryName = entry.getName();
            if (!entryName.endsWith(".jar")) continue;

            var libPath = Paths.get("libs/" + entryName);
            if (!Files.exists(libPath)) {
                System.out.println("unpacking " + libPath + "...");
                Files.copy(
                    jar.getInputStream(entry),
                    libPath,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        }

        var libPaths = jar.stream()
            .map(e -> "libs/" + e.getName())
            .collect(Collectors.toList());

        var classPath = String.join(":", libPaths);

        runCmd(Arrays.asList(
            "java", "-cp", classPath,
            "net.minecraft.server.Main", "nogui"
        ));
    }

    static void runCmd(List<String> cmd) throws Exception {
        var procBuilder = new ProcessBuilder(cmd);
        procBuilder.inheritIO();

        int exitCode = procBuilder.start().waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed");
        }
    }
}
