package server;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.lang.reflect.InvocationTargetException;

import utils.FilePipe;
import static server.ObjectWrapper.MethodSignature.*;
import static server.ObjectWrapper.*;

public class CommandParser {
    public ObjectWrapper server;
    public ObjectWrapper dispatcher;
    public FilePipe      pipe;

    public CommandParser(Object server, FilePipe pipe) throws Exception {
        this.pipe = pipe;
        this.server = new ObjectWrapper(server);
        this.dispatcher = this.server
            .invoke(sig("getCommands"))
            .invoke(sig("getDispatcher"));
    }

    public void parseFiles(String[] filePaths) throws Exception {
        var errorCount = 0;
        for (var filePath : filePaths) {
            errorCount += parseFile(filePath);
        }

        if (errorCount == 0) {
            pipe.write("success\n");
        }
    }

    public int parseFile(String filePath) throws Exception {
        var errorCount = 0;
        try (var reader = Files.newBufferedReader(Paths.get(filePath))) {
            int line = 0;
            while (true) {
                String command;
                while (true) {
                    command = reader.readLine();
                    line += 1;
                    if (command == null) return errorCount;
                    command = command.trim();
                    if (command.equals(""))       continue;
                    if (command.charAt(0) == '#') continue;
                    break;
                }

                errorCount += parseCommand(command, filePath, line);
            }
        }
    }

    // TODO: Move the the function body to `parseFile()` to print
    //       the error file name and line?
    public int parseCommand(
        String command,
        String filePath,
        int    line
    ) throws Exception {
        var results = this.dispatcher.invoke(
            sig("parse", String.class, Object.class),
            command,
            this.server.invoke(sig("createCommandSourceStack")).object
        );

        try {
            invokeStatic(
                "net.minecraft.commands.Commands",
                sig("validateParseResults", results.object.getClass()),
                results.object
            );

            var commandContext = results
                .invoke(sig("getContext"))
                .invoke(sig("build", String.class), command)
                .object;

            var result = (Optional) invokeStatic(
                "com.mojang.brigadier.context.ContextChain",
                sig("tryFlatten", commandContext.getClass()),
                commandContext
            ).object;

            if (!result.isPresent()) {
                throw (Throwable) getStatic(
                    "com.mojang.brigadier.exceptions.CommandSyntaxException",
                    "BUILT_IN_EXCEPTIONS"
                )
                .invoke(sig("dispatcherUnknownCommand"))
                .invoke(
                    sig("createWithContext"),
                    results.invoke(sig("getReader")).object
                ).object;
            }
        } catch (InvocationTargetException t) {
            var e = new ObjectWrapper(t.getCause());

            // TODO: Maybe client should put the `cwd` and relative file path
            //       to the pipe. This way we achive the file name printing as
            //       in compilers
            var msg = (String) e
                .invoke(sig("getRawMessage"))
                .invoke(sig("getString"))
                .object;
            this.pipe.write(String.format(
                "%s:%d: %s\n",
                filePath, line,
                msg
            ));

            var input = (String) e.invoke(sig("getInput")).object;
            var cursor = (int) e.invoke(sig("getCursor")).object;
            if (input != null && cursor >= 0) {
                int a = Math.min(input.length(), cursor);
                var sb = new StringBuilder();

                sb.append("    ");
                if (a > 10) {
                    sb.append("...");
                }

                sb.append(input.substring(Math.max(0, a - 10), a));
                if (a < input.length()) {
                    sb.append(input.substring(a));
                }

                sb.append("<--[HERE]\n");

                this.pipe.write(sb.toString());
            }

            return 1;
        } catch (Throwable t) {
            System.out.println("UNREACHABLE");
            t.printStackTrace();
        }

        return 0;
    }
}
