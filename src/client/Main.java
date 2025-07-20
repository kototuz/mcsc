package client;

import utils.FilePipe;

import java.nio.file.Paths;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;

public class Main {
    public static void main(String[] args) throws Throwable {
        if (args.length == 0) {
            System.out.println("args: <file1> <file2>...");
            return;
        }

        for (int i = 0; i < args.length; i++) {
            var relativePath = Paths.get(args[i]);
            if (!Files.exists(relativePath)) {
                System.err.println("error: '" + args[i] + "' does not exist");
                return;
            }

            args[i] = relativePath.toAbsolutePath().toString();
        }

        try (var pipe = new FilePipe()) {
            try (var lock = pipe.lockForWrite()) {
                pipe.write(String.join("\n", args));
            }

            var size = pipe.channel.size();
            while (pipe.channel.size() == size) {}

            try (var lock = pipe.lockForRead()) {
                System.out.println(pipe.read());
            }
        }
    }
}
