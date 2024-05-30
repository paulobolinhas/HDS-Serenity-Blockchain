# HDSLedger IBFT Blockchain

## Introduction

HDSLedger is a simplified permissioned (closed membership) blockchain system with high dependability
guarantees. It uses the Istanbul BFT consensus algorithm to ensure that all nodes run commands
in the same order, achieving State Machine Replication (SMR) and guarantees that all nodes
have the same state.

## Requirements

- [Java 17](https://www.oracle.com/java/technologies/javase-jdk17-downloads.html) - Programming language;

- [Maven 3.8](https://maven.apache.org/) - Build and dependency management tool;

- [Python 3](https://www.python.org/downloads/) - Programming language;

---

# Configuration Files

### Node configuration

Can be found inside the `resources/` folder of the `Service` module.

```json
{
    "id": <NODE_ID>,
    "isLeader": <IS_LEADER>,
    "hostname": "localhost",
    "port": <NODE_PORT>,
}
```

## Dependencies

To install the necessary dependencies run the following command:

```bash
./install_deps.sh
```

This should install the following dependencies:

- [Google's Gson](https://github.com/google/gson) - A Java library that can be used to convert Java Objects into their JSON representation.

## Puppet Master

The puppet master is a python script `puppet-master.py` which is responsible for starting the nodes
of the blockchain.
The script runs with `kitty` terminal emulator by default since it's installed on the RNL labs.

To run the script you need to have `python3` installed.
The script has arguments which can be modified:

- `terminal` - the terminal emulator used by the script
- `server_config` - a string from the array `server_configs` which contains the possible configurations for the blockchain nodes

Run the script with the following command:

```bash
python3 puppet-master.py
```
Note: You may need to install **kitty** in your computer

## Maven

It's also possible to run the project manually by using Maven.

### Instalation

Compile and install all modules using:

```
mvn clean install
```

### Execution

Run without arguments

```
cd <module>/
mvn compile exec:java
```

Run with arguments

```
cd <module>/
mvn compile exec:java -Dexec.args="..."
```
---
This codebase was adapted from last year's project solution, which was kindly provided by the following group: [David Belchior](https://github.com/DavidAkaFunky), [Diogo Santos](https://github.com/DiogoSantoss), [Vasco Correia](https://github.com/Vaascoo). We thank all the group members for sharing their code.

## Tests

In order to check the behaviour of our app, we have done `test scenarios` where we created environments with some introduced unstable variables (flags), a kind of honeypot execution, and `unit tests` for the crypto library and most important to test behaviour under attack.

### Unit Tests

Running the command in the root directory "HDSLedger" runs both Crypto and Behaviour Under Attack tests, to run a specific module you need to get into the correspondent one (Crypto and Client, respectively)
```bash
mvn tests
```


#### Behaviour Under Attack

- ledgerNotNull: Test case to verify that the ledger is not null after instantiating and listening to nodes.

- transactionInsufficientFee: Test case to verify behaviour when a transaction with insufficient fee is attempted.

- moreFundsThanBalance: Test case to verify behaviour when attempting a transaction with more funds than the client's balance.

- maliciousClientFloodNetwork: Test case to verify behaviour when a malicious client floods the network with transactions using insufficient fees.

- maliciousClientForgeSender: Test case to verify behaviour when a malicious client attempts to forge the sender of a transaction.

- sendToHimSelf: Test case when the sender tries to send a transaction to him

- testValidChainHash: Validates that after a given set of transactions, when blocks are added to the ledger. 
The ledger is able to be properly audited.
Late messages of the client throw a "Socket is Closed" exception, because we close the socket this does not affect the test and could be fixed by delaying the close, but we did not do it to do not make the tests slower

- testInvalidChainHash: Validates that after a given set of transactions, when blocks are added to the ledger.
The ledger is able to be properly audited.
If a given hash of a block within the ledger is corrupted, the auditor is able to detect it.
Late messages of the client throw a "Socket is Closed" exception, because we close the socket this does not affect the test and could be fixed by delaying the close, but we did not do it to do not make the tests slower


#### Crypto

Run:
```
* cd HDSLedger/Crypto
* mvn test
```


- testSignAndVerify: This test verifies the functionality of the signing and verification process within the system. It involves signing a message with a cryptographic key and then verifying that the signature is valid.

- testInvalidSignature: This test is designed to handle cases where an invalid signature is provided. It validates the system's ability to detect and reject signatures that do not match the expected format or are not generated by authorized entities.

- testCorruptedMessage: This test assesses the system's resilience against corrupted messages. It involves introducing deliberate corruption into a message and then verifying that the system is able to detect and handle this corruption appropriately.


### Test Scenarios

#### PRODUCTION

This is the standard execution environment where the system operates normally.

Run (normal execution):
```
cd HDSLedger/
python3 puppet-master.py
// Wait until the first program compiles
python3 clients_launcher.py
```

#### UNAUTHORIZED_CLIENT

This test simulates a scenario where a client attempts to send messages without proper authorization (with a non corresponding private key).
When running the test (... receives SignatureException) -  cant validate the integrity of the data, so the append from the client will be ignored.

Run:
```
cd HDSLedger/
python3 puppet-master.py
// Wait until the first program compiles
python3 tests/unauthorized_client.py
```

#### MALFORMED_CLIENT_SIGNATURE

This test involves sending a client message with a signature that is improperly formed or invalid.
When running the test (... receives IllegalArgumentException) - cant recognize signature, append from client will be ignored.

Run:
```
cd HDSLedger/
python3 puppet-master.py
// Wait until the first program compiles
python3 tests/bad_client_signature.py
```


#### MALFORMED_NODE_SIGNATURE

This test involves sending a node message with a signature that is improperly formed or invalid.
This specifies that one of the nodes from the blockchain is Byzantine. 

The expected outcome is that the network is still able to perform correctly even when one of the nodes has been compromised (**b2f + 1**).


Run:
```
cd HDSLedger
python3 tests/malformed_node.py
// Wait until the first program compiles
python3 clients_launcher.py
```

#### LEADER_TIMEOUT

This test simulates the crash of the leader by not broadcasting pre-prepare which triggers the timeout on the client that broadcasts the message again.

After this, each node starts its timer and after the timeout start a round change. After concluding it the consensus is decided as usual.

Run:
```
cd HDSLedger
python3 tests/leader_timeout.py
// Wait until the first program compiles
python3 clients_launcher.py
```

#### DETECT_REPLAY_ATTACK

This test involves detecting and handling attempts to replay of messages with the comparison of the time of creation with a threshold.

When a node receives a message that has been created over the allowed threshold, it will be rejected as it may be a replay attack.

**Current threshold is of 30 seconds.**

Run:
```
cd HDSLedger
python3 tests/detect_replay_attack.py
// Wait until the first program compiles
python3 clients_launcher.py
```


#### ROUND_CHANGE_AFTER_PREPARE
This test simulates the network delays or packet drop by sleeping each process after receiving quorum prepare messages, this will trigger the round change
that will use the prepared value on the last round and decide the consensus as usual.


Run:
```
cd HDSLedger
python3 tests/round_change_after_prepare.py
// Wait until the first program compiles
python3 clients_launcher.py
```

