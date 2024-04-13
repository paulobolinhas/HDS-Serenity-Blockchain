#!/usr/bin/env python

import json
import os
import signal
import sys

# Terminal Emulator used to spawn the processes
terminal = "kitty"

# Blockchain node configuration file name
configs = [
    "regular_config.json",
    "clients_config.json"
]

server_config = configs[0]
clients_config = configs[1]
test_scenario = "UNAUTHORIZED_NODE"

def quit_handler(*args):
    os.system(f"pkill -i {terminal}")
    sys.exit()


# Compile classes
os.system("mvn clean install")

# Spawn blockchain nodes
with open(f"Service/src/main/resources/{server_config}") as f:
    data = json.load(f)
    processes = list()
    for key in data:
        pid = os.fork()
        if pid == 0:
            os.system(
                f"{terminal} sh -c \"cd Service; mvn exec:java -Dexec.args='{key['id']} {server_config} {clients_config} {test_scenario}'; sleep 500\"")
            sys.exit()

signal.signal(signal.SIGINT, quit_handler)

while True:
    print("Type quit to quit")
    command = input(">> ")
    if command.strip() == "quit":
        quit_handler()
