# Minecraft Command Syntax Checker - MCSC

> [!WARNING]
> The software is unfinished

## How it works

We install [minecraft server](https://www.minecraft.net/en-us/download/server),
remap the server code and inject our code using `javassist`. Thus we get the
command parser.

If we start minecraft server every time we want to check the file with
commands it will be so slow. That's why we create client-server model.
It looks like [LSP](https://en.wikipedia.org/wiki/Language_Server_Protocol).
You start server once and then just send messages from the client to the server
through a file, which is something like a pipe.

## Build

Download version stuff:

> [!WARNING]
> At the moment it is recommended to use only `1.21.7` version.
> Other versions may not work

``` console
java @download.txt -help
java @download.txt 1.21.7
```

Build:
``` console
java @build.txt
```

## Usage

Run server:

> [!NOTE]
> At the moment you should run the server every time in the same directory
> because the server search for libraries in the current working directory
> and if it does not find them it will produce new ones

``` console
java -jar build/bin/server_launcher.jar --help
java -jar build/bin/server_launcher.jar
```

Client:
``` console
java -jar build/bin/client.jar <files>
```
