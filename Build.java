import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;
import java.util.HashMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FileOutputStream;
import java.io.FileInputStream;

public class Build {
    public static void main(String[] args) throws Exception {
        var buildSteps = new HashMap<String, RunnableWithException>();
        buildSteps.put("server",   Build::buildServer);
        buildSteps.put("client",   Build::buildClient);
        buildSteps.put("launcher", Build::buildLauncher);

        if (args.length == 0) {
            for (var buildStep : buildSteps.values()) {
                buildStep.run();
            }
        } else if (args.length == 1) {
            buildSteps.get(args[0]).run();
        } else {
            System.err.println("error: incorrect arguments");
            System.exit(1);
        }
    }

    static void buildServer() throws Exception {
        info("building server...");

        var compileCmd = new ArrayList<String>();
        compileCmd.add("javac");
        compileCmd.addAll(findFilesRecursively("thirdparty/brigadier/src"));
        compileCmd.add("src/MCSCServer.java");
        compileCmd.add("build/version/Mappings.java");
        compileCmd.add("src/utils/FilePipe.java");
        compileCmd.add("-d");
        compileCmd.add("build/classes/mcsc-server");
        runCmd(compileCmd);

        runCmd(
            "jar", "-cf",
            "build/brigadier.jar",
            "-C", "build/classes/mcsc-server", "."
        );
    }

    static void buildClient() throws Exception {
        info("building client...");

        runCmd(
            "javac", "src/MCSCClient.java",
            "src/utils/FilePipe.java",
            "-d", "build/classes/mcsc-client"
        );

        runCmd(
            "jar", "-cfe",
            "build/bin/mcsc_client.jar", "MCSCClient",
            "-C", "build/classes/mcsc-client", "."
        );
    }

    static void buildLauncher() throws Exception {
        info("building launcher...");

        runCmd(
            "javac", "src/MCServerLauncher.java",
            "-d", "build/classes/mc-server-launcher"
        );

        var brigadierLibName = Files.readString(
            Paths.get("build/version/brigadier_lib_name.txt")
        );

        buildLauncherJar(brigadierLibName);
    }

    static void buildLauncherJar(String brigadierLibName) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "MCServerLauncher");
        var newJar = new JarOutputStream(new FileOutputStream("build/bin/mcsc_server.jar"), manifest);

        var mainEntry = new JarEntry("MCServerLauncher.class");
        newJar.putNextEntry(mainEntry);
        new FileInputStream("build/classes/mc-server-launcher/MCServerLauncher.class").transferTo(newJar);
        newJar.closeEntry();

        var brigadierEntry = new JarEntry(brigadierLibName);
        newJar.putNextEntry(brigadierEntry);
        new FileInputStream("build/brigadier.jar").transferTo(newJar);
        newJar.closeEntry();

        var serverJar = new JarFile("build/version/server.jar");
        for (var entry : serverJar.stream().collect(Collectors.toList())) {
            var entryName = entry.getName();
            if (!entryName.endsWith(".jar")) continue;

            var libName = Paths.get(entryName).getFileName().toString();
            var newEntry = new JarEntry(libName);

            if (!libName.equals(brigadierLibName)) {
                newJar.putNextEntry(newEntry);
                serverJar.getInputStream(entry).transferTo(newJar);
                newJar.closeEntry();
            }
        }

        serverJar.close();
        newJar.close();
    }

    static void runCmd(List<String> cmd) throws Exception {
        var procBuilder = new ProcessBuilder(cmd);
        procBuilder.inheritIO();

        int exitCode = procBuilder.start().waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed");
        }
    }

    static void runCmd(String ...cmd) throws Exception {
        runCmd(Arrays.asList(cmd));
    }

    static List<String> findFilesRecursively(String directoryPath) throws Exception {
        try (Stream<Path> stream = Files.walk(Paths.get(directoryPath))) {
            return stream
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .collect(Collectors.toList());
        }
    }

    static void info(String msg) {
        System.out.println("info: " + msg);
    }

    static interface RunnableWithException {
        public void run() throws Exception;
    }
}

// TODO: Change the system of injection our code into the minecraft server
//       To be more independent we need to get rid of using `brigadier`.
//       We only need to get instance of the server in the server main
//       function. The proplem is that the server instance is located
//       on the stack. I think we can use `javaagent` for this
