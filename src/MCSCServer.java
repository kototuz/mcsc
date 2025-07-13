package src;

import com.mojang.brigadier.*;
import com.mojang.brigadier.tree.*;
import com.mojang.brigadier.exceptions.*;
import com.mojang.brigadier.context.ContextChain;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import java.nio.file.Files;
import java.nio.file.Paths;

import java.io.RandomAccessFile;
import java.io.File;
import java.io.BufferedWriter;

import build.Mappings;
import utils.FilePipe;

public class MCSCServer {
    static FilePipe fp;
    public static void main(CommandDispatcher<Object> dispatcher) {
        try (var pipe = new FilePipe()) {
            long size = 0;
            Parser parser = new Parser(dispatcher, pipe);
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
                        for (var filePath : filePaths) {
                            parser.parseFile(filePath);
                        }

                        if (parser.errorCount == 0) {
                            pipe.write("success\n");
                        }

                        parser.errorCount = 0;
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

    static Object createPermissionSourceInstance() throws Exception {
        var permissionSourceInterface = Class.forName(Mappings.PERMISSION_SOURCE);
        return Proxy.newProxyInstance(
            permissionSourceInterface.getClassLoader(),
            new Class<?>[] {permissionSourceInterface},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
                    return true;
                }
            }
        );
    }

    static class Parser {
        CommandDispatcher<Object> dispatcher;
        FilePipe pipe;
        int errorCount = 0;

        Parser(CommandDispatcher<Object> dispatcher, FilePipe pipe) {
            this.dispatcher = dispatcher;
            this.pipe = pipe;
        }

        void parseFile(String filePath) throws Exception {
            try (var reader = Files.newBufferedReader(Paths.get(filePath))) {
                while (true) {
                    String command;
                    while (true) {
                        command = reader.readLine();
                        if (command == null) return;
                        command = command.trim();
                        if (command.equals("")) continue;
                        if (command.charAt(0) == '#') continue;

                        break;
                    }

                    var results = dispatcher.parse(
                        command,
                        createPermissionSourceInstance()
                    );

                    try {
                        validateParseResults(results);
                        ContextChain.tryFlatten(results.getContext().build(command))
                            .orElseThrow(() -> { 
                                return CommandSyntaxException
                                    .BUILT_IN_EXCEPTIONS
                                    .dispatcherUnknownCommand()
                                    .createWithContext(results.getReader());
                            });
                    } catch (CommandSyntaxException e) {
                        this.errorCount += 1;
                        pipe.write(e.getRawMessage().getString() + "\n");
                        if (e.getInput() != null && e.getCursor() >= 0) {
                            int a = Math.min(e.getInput().length(), e.getCursor());
                            var sb = new StringBuilder();

                            sb.append("    ");
                            if (a > 10) {
                                sb.append("...");
                            }

                            sb.append(e.getInput().substring(Math.max(0, a - 10), a));
                            if (a < e.getInput().length()) {
                                sb.append(e.getInput().substring(a));
                            }

                            sb.append("<--[HERE]\n");

                            pipe.write(sb.toString());
                        }
                    }
                }
            }
        }

        static void validateParseResults(ParseResults<Object> results) throws CommandSyntaxException {
            if (!results.getReader().canRead()) {
                return;
            } else if (results.getExceptions().size() == 1) {
                throw (CommandSyntaxException)results.getExceptions().values().iterator().next();
            } else {
                throw results.getContext().getRange().isEmpty()
                    ? CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(results.getReader())
                    : CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(results.getReader());
            }
        }
    }
}
