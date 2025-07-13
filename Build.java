import org.json.simple.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.io.FileInputStream;

public class Build {
    static String VERSION = "1.21.6";
    static HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        JSONObject version;
        if (args.length == 0) {
            info("downloading version manifest...");
            var resp = downloadFileAsString("https://launchermeta.mojang.com/mc/game/version_manifest.json");
            var versionManifest = (JSONObject) JSONValue.parseWithException(resp);
            var versionURL = find((JSONArray)versionManifest.get("versions"), (obj) -> {
                var versionInfo = (JSONObject) obj;
                if (versionInfo.get("id").toString().equals(VERSION)) {
                    return Optional.of(versionInfo.get("url").toString());
                }
                return Optional.empty();
            });

            info("downloading version's json...");
            downloadFileAsFile(versionURL, "build/version.json");
            version = (JSONObject) JSONValue.parseWithException(Files.readString(Paths.get("build/version.json")));

            var downloads = (JSONObject) version.get("downloads");

            info("downloading server...");
            var server = (JSONObject) downloads.get("server");
            downloadFileAsFile(server.get("url").toString(), "build/server.jar");

            info("downloading server mappings...");
            var serverMappingsURL = ((JSONObject)downloads.get("server_mappings")).get("url").toString();
            var serverMappings = downloadFileAsString(serverMappingsURL);
            extractMappingsIntoClass(serverMappings);
        } else if (args.length == 1 && args[0].equals("local")) {
            version = (JSONObject) JSONValue.parseWithException(Files.readString(Paths.get("build/version.json")));
        } else {
            throw new RuntimeException("Unknown argument");
        }

        info("building MCSC server...");
        var compileCmd = new ArrayList<String>();
        compileCmd.add("javac");
        compileCmd.addAll(findFilesRecursively("thirdparty/brigadier/src"));
        compileCmd.add("src/MCSCServer.java");
        compileCmd.add("build/Mappings.java");
        compileCmd.add("src/utils/FilePipe.java");
        compileCmd.add("-d");
        compileCmd.add("build/classes/mcsc-server");
        runCmd(compileCmd);
        runCmd(Arrays.asList(
            "jar", "-cf",
            "build/brigadier.jar",
            "-C", "build/classes/mcsc-server", "."
        ));

        info("building MCSC client...");
        runCmd(Arrays.asList(
            "javac", "src/MCSCClient.java",
            "src/utils/FilePipe.java",
            "-d", "build/classes/mcsc-client"
        ));
        runCmd(Arrays.asList(
            "jar", "-cfe",
            "build/bin/mcsc_client.jar", "MCSCClient",
            "-C", "build/classes/mcsc-client", "."
        ));

        var brigadierLibName = find((JSONArray)version.get("libraries"), (obj) -> {
            var library = (JSONObject) obj;
            if (library.get("name").toString().startsWith("com.mojang:brigadier")) {
                var artifact = (JSONObject) ((JSONObject)library.get("downloads")).get("artifact");
                return Optional.of(Paths.get(artifact.get("path").toString()).getFileName().toString());
            }
            return Optional.empty();
        });

        info("building minecraft server launcher...");
        runCmd(Arrays.asList(
            "javac", "src/MCServerLauncher.java",
            "-d", "build/classes/mc-server-launcher"
        ));
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

        var serverJar = new JarFile("build/server.jar");
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

    static void extractMappingsIntoClass(String mappings) throws Exception {
        var matcher = Pattern.compile(
            "net\\.minecraft\\.commands\\.PermissionSource -> (\\w+):"
        ).matcher(mappings);
        matcher.find();

        Files.write(
            Paths.get("build/Mappings.java"),
            """
            package build;
            public class Mappings {
                public static final String PERMISSION_SOURCE = \"%s\";
            }
            """.formatted(matcher.group(1)).getBytes()
        );
    }

    static void runCmd(List<String> cmd) throws Exception {
        var procBuilder = new ProcessBuilder(cmd);
        procBuilder.inheritIO();

        int exitCode = procBuilder.start().waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed");
        }
    }

    static List<String> findFilesRecursively(String directoryPath) throws Exception {
        try (Stream<Path> stream = Files.walk(Paths.get(directoryPath))) {
            return stream
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .collect(Collectors.toList());
        }
    }

    static <T> T find(JSONArray src, Function<Object, Optional<T>> p) {
        for (var el : src) {
            var result = p.apply(el);
            if (result.isPresent()) {
                return result.get();
            }
        }

        throw new RuntimeException("Element not found");
    }

    static void downloadFileAsFile(String fileURL, String outputPathString) throws Exception {
        Path outputPath = Paths.get(outputPathString);
        Files.createDirectories(outputPath.getParent());
        Files.copy(
            new URI(fileURL).toURL().openStream(),
            outputPath,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    static String downloadFileAsString(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();

        return Build.httpClient.send(
            request, 
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    static void info(String msg) {
        System.out.println("info: " + msg);
    }
}

// TODO: Improve build system:
// Maybe we should create two files: `Download.java`, `Build.java`
// One is for downloading content and another for building. Then
// if we need the new version we run `Download.java` and `Build.java`,
// but if we need just local recompiling we can use `Build.java`
