import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.*;

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
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            compile();
            buildServer();
            buildClient();
            return;
        }

        switch (args[0]) {
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

        Files.createDirectories(Paths.get("build/bin/"));

        Files.copy(
            Paths.get("build/version/server.jar"),
            Paths.get("build/bin/server.jar"),
            StandardCopyOption.REPLACE_EXISTING
        );

        Files.copy(
            Paths.get("build/version/server_launcher.jar"),
            Paths.get("build/bin/server_launcher.jar"),
            StandardCopyOption.REPLACE_EXISTING
        );

        injectOurCodeInto("build/bin/server.jar");
        injectServerJarInto("build/bin/server_launcher.jar");

        Files.delete(Paths.get("build/bin/server.jar"));
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

        var cc = pool.get("net.minecraft.server.Main");
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

    static int skipUntilServerStoreInst(CodeIterator codeIter, ConstPool cp) throws Exception {
        while (codeIter.hasNext()) {
            int inst_begin_idx = codeIter.next();
            if (codeIter.byteAt(inst_begin_idx) == Opcode.INVOKESTATIC) {
                var returnClassName = cp.getMethodrefClassName(codeIter.u16bitAt(inst_begin_idx + 1));
                if (returnClassName.equals("net.minecraft.server.MinecraftServer")) {
                    while (codeIter.hasNext()) {
                        inst_begin_idx = codeIter.next();
                        if (codeIter.byteAt(inst_begin_idx) == Opcode.ASTORE) {
                            return inst_begin_idx;
                        }
                    }

                    throw new RuntimeException("Could not find server store instruction");
                }
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
