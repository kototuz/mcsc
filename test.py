#!/bin/python

import os
import sys
import shutil
import argparse
import subprocess as sp
from threading import Thread
from time import sleep

def test_version(version, stdout=None):
    # Build server
    sp.run(["java", "@build.txt", version, "server"], stdout=stdout)

    # Setup playground
    shutil.rmtree("./playground")
    os.makedirs("./playground")
    with open("./playground/eula.txt", "w") as f:
        f.write("eula=true")
    with open("./playground/test.mcfunction", "w") as f:
        f.write("say Hello, world\nsummon pig")

    # Launcher server
    # TODO: The pipe may not exist
    pipe_last_mtime = os.stat("/tmp/mcsc.pipe").st_mtime
    server = sp.Popen([
        "java",
        "-jar",
        "../build/bin/server_launcher.jar",
        "nogui"
    ], cwd="./playground", stdout=stdout)

    # Wait for MCSC launch
    while (os.stat("/tmp/mcsc.pipe").st_mtime == pipe_last_mtime):
        pass

    # Run client
    client_proc = sp.run([
        "java",
        "-jar", "../build/bin/client.jar",
        "./test.mcfunction"
    ], capture_output=True, cwd="./playground")

    server.kill()

    return client_proc.stdout

parser = argparse.ArgumentParser(
        prog="Test",
        description="Integration test")

parser.add_argument("--version", nargs="?")
parser.add_argument("-v", action="store_true")

args = parser.parse_args()

def verbose_test(version):
    print(f"TEST: {version}")
    res = test_version(version)
    if res == b"success\n":
        print("TEST PASSED")
    else:
        print("TEST FAILED")

def not_verbose_test(version):
    print(f"testing {version}... ", end='', flush=True)
    res = test_version(version, sp.DEVNULL)
    if res == b"success\n":
        print("passed")
    else:
        print("failed")


test_fn = verbose_test if args.v else not_verbose_test
if args.version == None:
    with open("./build/versions/list.txt") as f:
        content = f.read()
        versions = content.split("\n")
        versions = versions[:-1] # Last element is empty string. idk why
        for version in versions:
            test_fn(version)
else:
    test_fn(args.version)
