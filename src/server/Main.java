package server;

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
        // TODO: Notify about launching
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

                    try (var lock = pipe.lockForWrite()) {
                        parser.parseFiles(filePaths);
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
