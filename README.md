# Minecraft Command Syntax Checker - MCSC

> [!WARNING]
> The software is unfinished

## How it works

We install [minecraft server](https://www.minecraft.net/en-us/download/server).
The server uses different libraries.
We need the [brigadier](https://github.com/Mojang/brigadier) library because
it is an opensource project. We replace this library with our version.
Now we can intercept the Minecraft command parser and use it for
our advantage. 

If we start minecraft server every time we want to check the file with
commands it will be so slow. That's why we create client-server model.
It looks like [LSP](https://en.wikipedia.org/wiki/Language_Server_Protocol).
You start server once and then just send messages from the client to the server
through a file, which is something like a pipe.

## Build

``` console
java @build.txt
```

## Usage

Go to `playground/`

Run server:
``` console
java @run_server.txt
```

Client:
``` console
java -jar mcsc_client.jar <files>
```
