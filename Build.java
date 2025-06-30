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

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class Build {
    static String VERSION = "1.21.6";
    static HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) throws Throwable {
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
        resp = downloadFileAsString(versionURL);
        var version = (JSONObject) JSONValue.parseWithException(resp);
        var downloads = (JSONObject) version.get("downloads");

        var server = (JSONObject) downloads.get("server");
        info("downloading server...");
        downloadFileAsFile(server.get("url").toString(), "build/server.jar");
        info("unpacking server...");
        var classPath = new StringBuilder();
        var jar = new JarFile("build/server.jar");
        for (var entry : jar.stream().collect(Collectors.toList())) {
            if (entry.isDirectory()) continue;
            if (!entry.getName().endsWith(".jar")) continue;

            var name = entry.getName().replace("META-INF/", "");
            classPath.append(name+":");
            var outputPath = Paths.get("playground/" + name);
            Files.createDirectories(outputPath.getParent());
            Files.copy(
                jar.getInputStream(entry),
                outputPath,
                StandardCopyOption.REPLACE_EXISTING
            );
        }

        info("generating 'run_server.txt'...");
        classPath.deleteCharAt(classPath.length()-1);
        Files.write(
            Paths.get("playground/run_server.txt"),
            "-cp %s net.minecraft.server.Main nogui"
                .formatted(classPath.toString())
                .getBytes()
        );

        info("downloading server mappings...");
        var serverMappingsURL = ((JSONObject)downloads.get("server_mappings")).get("url").toString();
        var serverMappings = downloadFileAsString(serverMappingsURL);
        extractMappingsIntoClass(serverMappings);

        info("building MCSC server...");
        var brigadierLibPath = find((JSONArray)version.get("libraries"), (obj) -> {
            var library = (JSONObject) obj;
            if (library.get("name").toString().startsWith("com.mojang:brigadier")) {
                var artifact = (JSONObject) ((JSONObject)library.get("downloads")).get("artifact");
                return Optional.of(artifact.get("path").toString());
            }
            return Optional.empty();
        });
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
            "playground/libraries/" + brigadierLibPath,
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
            "playground/mcsc_client.jar", "MCSCClient",
            "-C", "build/classes/mcsc-client", "."
        ));

        info("generating eula...");
        Files.write(
            Paths.get("playground/eula.txt"),
            "eula=true".getBytes()
        );
    }

    static void extractMappingsIntoClass(String mappings) throws Throwable {
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

    static void runCmd(List<String> cmd) throws Throwable {
        int exitCode = new ProcessBuilder(cmd).start().waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed");
        }
    }

    static List<String> findFilesRecursively(String directoryPath) throws Throwable {
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

    static void downloadFileAsFile(String fileURL, String outputPathString) throws Throwable {
        Path outputPath = Paths.get(outputPathString);
        Files.createDirectories(outputPath.getParent());
        Files.copy(
            new URI(fileURL).toURL().openStream(),
            outputPath,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    static String downloadFileAsString(String url) throws Throwable {
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
