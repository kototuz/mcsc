package server;

import java.util.Arrays;
import java.nio.file.Paths;

import utils.FilePipe;

public class Main {
    public static void init(Object server) {
        new Thread(() -> {
            try {
                main(server);
            } catch (Exception e) {
                System.err.println(e);
            }
        }).start();
    }

    public static void main(Object server) throws Exception {
        System.out.print(
            """
            ========================================
                        MCSC IS RUNNING!            
            ========================================
            """
        );

        try (var pipe = new FilePipe()) {
            long size = 0;
            var parser = new CommandParser(server, pipe);
            while (true) {
                try {
                    while (pipe.channel.size() == size) {}
                    while (pipe.channel.size() == 0) {}

                    String[] filePaths;
                    try (var lock = pipe.lockForRead()) {
                        pipe.channel.position(0);
                        filePaths = pipe.read().split("\n");
                    }

                    for (var p : filePaths)
                        System.out.println(p);

                    try (var lock = pipe.lockForWrite()) {
                        parser.parseFiles(
                            Paths.get(filePaths[0]),
                            Arrays.copyOfRange(filePaths, 1, filePaths.length)
                        );

                        size = pipe.channel.size();
                    }
                } catch (Exception e) {
                    System.err.println("error: could not check commands: " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("error: could not open pipe: " + e);
        }
    }
}
