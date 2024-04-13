#!/usr/bin/env python

import os
import json
import sys
import signal


# Terminal Emulator used to spawn the processes
terminal = "kitty"

# Blockchain node configuration file name
configs = [
    "regular_config.json",
    "clients_config.json",
]


server_config = configs[0]
clients_config = configs[1]
test_scenario = "MALFORMED_CLIENT_SIGNATURE"

def quit_handler(*args):
    os.system(f"pkill -i {terminal}")
    sys.exit()


# Compile classes
os.system("mvn clean install")

pid = os.fork()
if pid == 0:
    os.system(f"{terminal} sh -c \"cd Client; mvn exec:java -Dexec.args='1 {server_config} {clients_config} {test_scenario}' ; sleep 500\"")
    sys.exit()

signal.signal(signal.SIGINT, quit_handler)

while True:
    print("Type quit to quit")
    command = input(">> ")
    if command.strip() == "quit":
        quit_handler()
