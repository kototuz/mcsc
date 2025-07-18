package utils;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.ByteBuffer;

public class FilePipe implements AutoCloseable {
    private static final Path PIPE_PATH = Paths.get("/tmp/mcsc.pipe");

    public FileChannel channel;

    public FilePipe() throws Exception {
        this.channel = FileChannel.open(
            PIPE_PATH,
            StandardOpenOption.CREATE, 
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public void write(String msg) throws Exception {
        channel.write(ByteBuffer.wrap(msg.getBytes()));
    }

    public String read() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate((int)channel.size());
        channel.read(buffer);
        buffer.flip();
        return new String(buffer.array()).trim();
    }

    public FileLock lockForWrite() throws Exception {
        return channel.lock();
    }

    public FileLock lockForRead() throws Exception {
        return channel.lock(0, Long.MAX_VALUE, true);
    }

    @Override
    public void close() throws Exception {
        this.channel.close();
    }
}
