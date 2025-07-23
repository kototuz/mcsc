import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.*;
import java.util.jar.Manifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileSystem;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;

import cn.maxpixel.mcdecompiler.util.JarUtil;
import cn.maxpixel.mcdecompiler.util.FileUtil;

import javassist.ClassPool;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Opcode;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Bytecode;

public class Build {
    static Path versionDirPath;

    public static void main(String[] args) throws Exception {
        switch (args.length) {
        case 0:
            throw new RuntimeException("version must be provided");

        case 1:
            versionDirPath = Paths.get("build/versions/" + args[0]);
            compile();
            buildServer();
            buildClient();
            return;

        default:
            versionDirPath = Paths.get("build/versions/" + args[0]);
            break;
        }

        switch (args[1]) {
        case "compile":
            compile();
            break;

        case "server":
            compile();
            buildServer();
            break;

        case "client":
            compile();
            buildClient();
            break;

        default:
            System.err.println("error: incorrect argument: " + args[0]);
            System.exit(1);
        }
    }

    static void buildServer() throws Exception {
        info("building server...");

        // TODO: move it to the main()
        Files.createDirectories(Paths.get("build/bin/"));

        var serverJar = versionDirPath.resolve("server.jar");
        var serverLauncherJar = versionDirPath.resolve("server_launcher.jar");

        Files.copy(
            serverJar,
            Paths.get("build/bin/server.jar"),
            StandardCopyOption.REPLACE_EXISTING
        );

        if (Files.exists(serverLauncherJar)) {
            Files.copy(
                serverLauncherJar,
                Paths.get("build/bin/server_launcher.jar"),
                StandardCopyOption.REPLACE_EXISTING
            );

            injectOurCodeInto("build/bin/server.jar");
            injectServerJarInto("build/bin/server_launcher.jar");

            Files.delete(Paths.get("build/bin/server.jar"));
        } else {
            injectOurCodeInto("build/bin/server.jar");

            Files.move(
                Paths.get("build/bin/server.jar"),
                Paths.get("build/bin/server_launcher.jar"),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    static void buildClient() throws Exception {
        info("building client...");
        runCmd(
            "jar", "cfe", "build/bin/client.jar", "client.Main",
            "-C", "build", "client",
            "-C", "build", "utils"
        );
    }

    static void compile() throws Exception {
        info("compiling...");

        var compileCmd = new ArrayList<String>();
        compileCmd.add("javac");
        compileCmd.add("-Xlint:unchecked");
        compileCmd.addAll(findFilesRecursively("src"));
        compileCmd.add("-d");
        compileCmd.add("build");

        runCmd(compileCmd);
    }

    static void injectServerJarInto(String launcherJarPath) throws Exception {
        try (var launcherJarFs = JarUtil.createZipFs(Paths.get(launcherJarPath))) {
            var serverJarPath = FileUtil.iterateFiles(launcherJarFs.getPath(""))
                .filter(p -> p.startsWith("META-INF/versions/"))
                .findFirst()
                .get();

            Files.copy(
                Paths.get("build/bin/server.jar"),
                serverJarPath,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    static void injectOurCodeInto(String serverJarPath) throws Exception {
        try (var serverJarFs = JarUtil.createZipFs(Paths.get(serverJarPath))) {
            var root = serverJarFs.getPath("");
            copyDirectory(Paths.get("build/server"), root);
            copyDirectory(Paths.get("build/utils"), root);

            injectCallToOurServer(serverJarPath, serverJarFs);
        }
    }

    static void injectCallToOurServer(String serverJarPath, FileSystem serverJarFs) throws Exception {
        var pool = ClassPool.getDefault();
        pool.appendClassPath(serverJarPath);

        var cc = pool.get(getJarMainClassName(serverJarFs));
        var cf = cc.getClassFile();

        var methodInfo = cf.getMethod("main");
        var cp = methodInfo.getConstPool();
        var codeIter = methodInfo.getCodeAttribute().iterator();

        var storeInstBeginIdx = skipUntilServerStoreInst(codeIter, cp);
        var serverVarIdx = codeIter.byteAt(storeInstBeginIdx + 1);

        var newCode = new Bytecode(cp);
        newCode.addAload(serverVarIdx);
        newCode.addInvokestatic("server.Main", "init", "(Ljava/lang/Object;)V");
        codeIter.insert(newCode.get());

        methodInfo.setCodeAttribute(codeIter.get());

        var tmpBuf = new ByteArrayOutputStream(cc.toBytecode().length);
        cf.write(new DataOutputStream(tmpBuf));

        Files.write(
            serverJarFs.getPath("net/minecraft/server/Main.class"),
            tmpBuf.toByteArray()
        );
    }

    static String getJarMainClassName(FileSystem jarFs) throws Exception {
        var manifestStream = Files.newInputStream(jarFs.getPath("META-INF/MANIFEST.MF"));
        var manifest = new Manifest(manifestStream);
        var attr = manifest.getMainAttributes();
        return attr.getValue("Main-Class");
    }

    static int skipUntilServerStoreInst(CodeIterator codeIter, ConstPool cp) throws Exception {
        while (codeIter.hasNext()) {
            int inst_begin_idx = codeIter.next();
            int opcode = codeIter.byteAt(inst_begin_idx);
            if (opcode != Opcode.INVOKESTATIC && opcode != Opcode.INVOKESPECIAL)
                continue;

            var returnClassName = cp.getMethodrefClassName(codeIter.u16bitAt(inst_begin_idx + 1));
            if (returnClassName.equals("net.minecraft.server.MinecraftServer") ||
                returnClassName.equals("net.minecraft.server.dedicated.DedicatedServer")) {
                while (codeIter.hasNext()) {
                    inst_begin_idx = codeIter.next();
                    if (codeIter.byteAt(inst_begin_idx) == Opcode.ASTORE) {
                        return inst_begin_idx;
                    }
                }
                throw new RuntimeException("Could not find server store instruction");
            }
        }

        throw new RuntimeException("Could not find server init instruction");
    }

    public static void copyDirectory(Path dirSrc, Path dirDest) throws Exception {
        var paths = FileUtil.iterateFiles(dirSrc).collect(Collectors.toList());
        for (var path : paths) {
            var pathWithoutDirSrc = path.subpath(
                dirSrc.getNameCount()-1,
                path.getNameCount()
            ).toString();
            FileUtil.copyFile(path, dirDest.resolve(pathWithoutDirSrc));
        }
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

// TODO: Add -help
