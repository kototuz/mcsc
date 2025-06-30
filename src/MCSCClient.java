import utils.FilePipe;

public class MCSCClient {
    public static void main(String[] args) throws Throwable {
        try (var pipe = new FilePipe("pipe")) {
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
