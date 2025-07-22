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
import java.io.File;

import cn.maxpixel.mcdecompiler.reader.ClassifiedMappingReader;
import cn.maxpixel.mcdecompiler.util.Utils;
import cn.maxpixel.mcdecompiler.util.JarUtil;
import cn.maxpixel.mcdecompiler.util.FileUtil;
import cn.maxpixel.mcdecompiler.ClassifiedDeobfuscator;
import cn.maxpixel.mcdecompiler.mapping.PairedMapping;
import cn.maxpixel.mcdecompiler.reader.AbstractMappingReader;
import cn.maxpixel.mcdecompiler.mapping.Mapping;

import org.json.simple.*;

public class Download {
    static final HttpClient HTTP_CLIENT      = HttpClient.newHttpClient();
    static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    static Path versionDirPath;

    static String     manifestStr;
    static JSONObject manifestJson;
    static String     versionStr;
    static JSONObject versionJson;
    static JSONObject versionDownloadsJson;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            downloadManifest();
            downloadLatestVersionInfo();
            downloadMappings();
            downloadServer();
            deobfuscateServerCode();
            return;
        }

        if (args.length == 1 && args[0].charAt(0) != '-') {
            downloadManifest();
            downloadVersionInfo(args[0]);
            downloadMappings();
            downloadServer();
            deobfuscateServerCode();
            return;
        }

        switch (args[0]) {
        case "-help":
            help();
            break;

        case "-list-versions":
            downloadManifest();
            var list = (JSONArray) manifestJson.get("versions");
            for (int i = list.size() - 1; i >= 0; i--)
                System.out.println(
                    ((JSONObject)list.get(i)).get("id").toString()
                );
            break;

        case "-download-manifest-file":
            downloadManifest();
            writeStringToFile(manifestStr, "manifest.json");
            break;

        case "-download-version-file":
            downloadManifest();
            if (args.length > 1)
                downloadVersionInfo(args[1]);
            else
                downloadLatestVersionInfo();
            writeStringToFile(versionStr, "version.json");
            break;

        default:
            System.err.println("error: invalid option");
            help();
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    static void deobfuscateServerCode() throws Exception {
        info("deobfuscating server code...");
        try (var launcherFs = JarUtil.createZipFs(versionDirPath.resolve("server_launcher.jar"))) {
            var paths = FileUtil.iterateFiles(launcherFs.getPath(""));
            var serverJarPath = paths
                .filter(p -> p.startsWith("META-INF/versions/"))
                .findFirst().get();

            var mappingsPath = versionDirPath.resolve("mappings.txt").toString();
            var reader = new ClassifiedMappingReader<>(Utils.tryIdentifyingMappingType(mappingsPath), mappingsPath);
            var deobfuscator = new ClassifiedDeobfuscator((ClassifiedMappingReader<PairedMapping>) reader);
            deobfuscator.deobfuscate(serverJarPath, versionDirPath.resolve("server.jar"));
        }
    }

    static void help() {
        System.out.println("""
            available arguments:
                [<version>]                           download <version> or latest
                -help                                 print this help
                -list-versions                        list all available versions
                -download-manifest-file               download manifest as file 
                -download-version-file [<version>]    download version info as file"""
        );
    }

    static void downloadManifest() throws Exception {
        info("downloading version manifest...");
        manifestStr = downloadFileAsString(VERSION_MANIFEST_URL);
        manifestJson = (JSONObject) JSONValue.parseWithException(manifestStr);
    }

    static void downloadVersionInfo(String version) throws Exception {
        info("downloading " + version + " info...");
        var versionURL = find((JSONArray)manifestJson.get("versions"), (obj) -> {
            var versionInfo = (JSONObject) obj;
            if (versionInfo.get("id").toString().equals(version)) {
                return Optional.of(versionInfo.get("url").toString());
            }
            return Optional.empty();
        });

        versionStr = downloadFileAsString(versionURL);
        versionJson = (JSONObject) JSONValue.parseWithException(versionStr);
        versionDownloadsJson = (JSONObject) versionJson.get("downloads");

        versionDirPath = Paths.get("build/versions/" + version);
    }

    static void downloadLatestVersionInfo() throws Exception {
        var latestVersion = ((JSONObject)manifestJson
                .get("latest"))
                .get("release");
        downloadVersionInfo(latestVersion.toString());
    }

    static void downloadMappings() throws Exception {
        info("downloading server mappings...");
        var serverMappingsURL = ((JSONObject)versionDownloadsJson
                .get("server_mappings"))
                .get("url").toString();
        downloadFileAsFile(serverMappingsURL, versionDirPath.resolve("mappings.txt"));
    }

    static void downloadServer() throws Exception {
        info("downloading server...");
        var server = (JSONObject) versionDownloadsJson.get("server");
        downloadFileAsFile(server.get("url").toString(), versionDirPath.resolve("server_launcher.jar"));
    }

    // TODO: Different versions have different changes in command system.
    //       I think we should not implement this function for each version
    //       in `Download.java`. We can just extract all mappings and
    //       generate a class.
    // static void extractMappingsIntoClass() throws Exception {
    //     var matcher = Pattern.compile(
    //         "net\\.minecraft\\.commands\\.PermissionSource -> (\\w+):"
    //     ).matcher(mappingsStr);
    //     if (!matcher.find()) {
    //         info("mappings not found");
    //         return;
    //     }
    //
    //     writeStringToFile(
    //         """
    //         package build;
    //         public class Mappings {
    //             public static final String PERMISSION_SOURCE = \"%s\";
    //         }
    //         """.formatted(matcher.group(1)),
    //         "build/version/Mappings.java"
    //     );
    // }

    static void extractBrigadierLibName() throws Exception {
        var brigadierLibName = find((JSONArray)versionJson.get("libraries"), (obj) -> {
            var library = (JSONObject) obj;
            if (library.get("name").toString().startsWith("com.mojang:brigadier")) {
                var artifact = (JSONObject) ((JSONObject)library.get("downloads")).get("artifact");
                return Optional.of(Paths.get(artifact.get("path").toString()).getFileName().toString());
            }
            return Optional.empty();
        });

        Files.write(Paths.get("build/version/brigadier_lib_name.txt"), brigadierLibName.getBytes());
    }

    static void writeStringToFile(String str, String outputPath) throws Exception {
        Files.write(Paths.get(outputPath), str.getBytes());
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

    static void downloadFileAsFile(String fileURL, Path outputPath) throws Exception {
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
