import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.util.function.Function;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.simple.*;

public class Download {
    static final HttpClient HTTP_CLIENT      = HttpClient.newHttpClient();
    static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    public static void main(String[] args) throws Exception {
        // TODO: Introduce different commands. Something like this:
        //       -list-versions
        //       -download-mappings-file
        //       -download-manifest-file
        //       -download-version-file
        if (args.length > 1) {
            System.err.println("error: specify minecraft version or nothing");
            System.exit(1);
        }

        info("downloading version manifest...");
        var resp = downloadFileAsString(VERSION_MANIFEST_URL);
        var versionManifest = (JSONObject) JSONValue.parseWithException(resp);

        String targetVersion;
        if (args.length == 1) {
            info("selected version: " + args[0]);
            targetVersion = args[0];
        } else {
            info("selected version: latest");
            targetVersion = ((JSONObject)versionManifest.get("latest"))
                .get("release").toString();
        }

        var versionURL = find((JSONArray)versionManifest.get("versions"), (obj) -> {
            var versionInfo = (JSONObject) obj;
            if (versionInfo.get("id").toString().equals(targetVersion)) {
                return Optional.of(versionInfo.get("url").toString());
            }
            return Optional.empty();
        });

        info("downloading " + targetVersion + " info...");
        resp = downloadFileAsString(versionURL);
        var versionInfo = (JSONObject) JSONValue.parseWithException(resp);

        var downloads = (JSONObject) versionInfo.get("downloads");

        Files.createDirectories(Paths.get("build/version/"));

        info("downloading server mappings...");
        var serverMappingsURL = ((JSONObject)downloads.get("server_mappings")).get("url").toString();
        var serverMappings = downloadFileAsString(serverMappingsURL);
        extractMappingsIntoClass(serverMappings);

        var brigadierLibName = find((JSONArray)versionInfo.get("libraries"), (obj) -> { var library = (JSONObject) obj; if (library.get("name").toString().startsWith("com.mojang:brigadier")) {
                var artifact = (JSONObject) ((JSONObject)library.get("downloads")).get("artifact");
                return Optional.of(Paths.get(artifact.get("path").toString()).getFileName().toString());
            }
            return Optional.empty();
        });
        Files.write(Paths.get("build/version/brigadier_lib_name.txt"), brigadierLibName.getBytes());

        info("downloading server...");
        var server = (JSONObject) downloads.get("server");
        downloadFileAsFile(server.get("url").toString(), "build/version/server.jar");
    }

    // TODO: Different versions have different changes in command system.
    //       I think we should not implement this function for each version
    //       in `Download.java`. We can just extract all mappings and
    //       generate a class.
    static void extractMappingsIntoClass(String mappings) throws Exception {
        var matcher = Pattern.compile(
            "net\\.minecraft\\.commands\\.PermissionSource -> (\\w+):"
        ).matcher(mappings);
        if (!matcher.find()) {
            info("mappings not found");
            return;
        }

        Files.write(
            Paths.get("build/version/Mappings.java"),
            """
            package build;
            public class Mappings {
                public static final String PERMISSION_SOURCE = \"%s\";
            }
            """.formatted(matcher.group(1)).getBytes()
        );
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

        return HTTP_CLIENT.send(
            request, 
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    static void info(String msg) {
        System.out.println("info: " + msg);
    }
}
