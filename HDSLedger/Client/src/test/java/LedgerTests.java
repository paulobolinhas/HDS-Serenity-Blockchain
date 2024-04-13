import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.ulisboa.tecnico.hdsledger.Client.Client;
import pt.ulisboa.tecnico.hdsledger.Client.util.ClientKeyLoader;
import pt.ulisboa.tecnico.hdsledger.communication.Block;
import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;
import pt.ulisboa.tecnico.hdsledger.service.services.NodeService;
import pt.ulisboa.tecnico.hdsledger.utilities.*;

import java.net.SocketException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Unit tests for the Ledger logic to ensure the correctness of blockchain operations.
 */
public class LedgerTests {

    private static String clientKeyPath = "src/main/resources/ClientKeys/";
    private static String nodeKeyPath = "src/main/resources/NodeKeys/";
    private static String nodesConfigPath = "src/main/resources/regular_config.json";
    private static String clientsConfigPath = "src/main/resources/clients_config.json";

    private static String client1Id = "1";
    private static String client2Id = "2";

    private static NodeService node1 = null;
    private static NodeService node2 = null;
    private static NodeService node3 = null;
    private static NodeService node4 = null;


    @BeforeEach
    public void instantiateNodes() {
        node1 = instantiateNodeService("1", nodesConfigPath, clientsConfigPath);
        node2 = instantiateNodeService("2", nodesConfigPath, clientsConfigPath);
        node3 = instantiateNodeService("3", nodesConfigPath, clientsConfigPath);
        node4 = instantiateNodeService("4", nodesConfigPath, clientsConfigPath);
        node1.listen();
        node2.listen();
        node3.listen();
        node4.listen();
    }

    /**
     * In cases where the messages take too long to get to the nodes or to the client is possible
     * that a Socket Exception is thrown. The sleep here mitigates that but does not fully solve
     */
    @AfterEach
    public void cleanup() {
        try {
            Thread.sleep(2000); //give time to late messages to arrive
            node1.closeLinkSocket();
            node2.closeLinkSocket();
            node3.closeLinkSocket();
            node4.closeLinkSocket();

        } catch (SocketException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Test case to verify that the ledger is not null after instantiating and listening to nodes.
     *
     * This test asserts the following:
     * 1. The ledger is not null
     *
     */
    @Test
    public void ledgerNotNull() {
        assert node1.getLedger() != null;
        assert node2.getLedger() != null;
        assert node3.getLedger() != null;
        assert node4.getLedger() != null;
    }


    /**
     * Test case to verify behavior when a transaction with insufficient fee is attempted.
     *
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     *
     * This test asserts the following:
     * 1. The client's balance is deducted by the fee amount on each node.
     * 2. Invalid transactions, such as those with insufficient fees, do not appear in the priority buffer of any node.
     *
     * @throws Exception if an error occurs during the test.
     */
    @Test
    public void transactionInsufficientFee() throws Exception {

        Client client = instantiateClientTestLauncher(client2Id);

        // Transaction with insufficient fee
        client.transferAmountTest(client2Id, client1Id, 1, 10);

        int originalBalance = 200;
        int balanceMinusFee = originalBalance - 20;

        // Assert that the balance of the client has been deducted by the fee amount on each node
        assert node1.getBalance(getEncodedPublicKey(client)) == balanceMinusFee;

        // Assert that invalid transactions do not appear in the priority buffer of any node
        assert node1.getPriorityBufferTx().isEmpty();

        client.closeLinkSocket();
    }


    /**
     * Test case to verify behavior when attempting a transaction with more funds than the client's balance.
     *
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     *
     * This test asserts the following:
     * 1. The client's balance is deducted by the fee amount on the originating node.
     * 2. Invalid transactions, such as those attempting to transfer more funds than the client's balance,
     *    do not appear in the priority buffer of any node.
     *
     * @throws Exception if an error occurs during the test.
     */
    @Test
    public void moreFundsThanBalance() throws Exception {

        Client client = instantiateClientTestLauncher(client2Id);

        // Perform an over-funding transfer
        client.transferAmountTest(client2Id, client1Id, 400, 20);

        // Define original and updated balances
        int originalBalance = 200;
        int balanceMinusFee = originalBalance - 20;

        // Assert that the balance of the client is deducted by the fee amount on the originating node
        assert node1.getBalance(getEncodedPublicKey(client)) == balanceMinusFee;

        // Assert that invalid transactions do not appear in the priority buffer of any node
        assert node1.getPriorityBufferTx().isEmpty();
        assert node2.getPriorityBufferTx().isEmpty();
        assert node3.getPriorityBufferTx().isEmpty();
        assert node4.getPriorityBufferTx().isEmpty();

        client.closeLinkSocket();

    }


    /**
     * Test case to verify behavior when a malicious client floods the network with transactions using insufficient fees.
     *
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     *
     * This test asserts the following:
     * 1. The client's balance is depleted after attempting multiple transactions with insufficient fees.
     *
     * @throws Exception if an error occurs during the test.
     */
    @Test
    public void maliciousClientFloodNetwork() throws Exception {

        Client client = instantiateClientTestLauncher(client2Id);

        // Flood transfers with fee 1
        // If client does more than 10 transfers he gets out of funds
        for (int i = 0; i < 10; i++) {
            client.transferAmountTest(client2Id, client1Id, 1, 1);
        }

        // Assert that the client's balance is depleted
        assert node1.getBalance(getEncodedPublicKey(client)) == 0;

        client.closeLinkSocket();

    }


    /**
     * Test case to verify behavior when a malicious client attempts to forge the sender of a transaction.
     *
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     *
     * This test asserts the following:
     * 1. The transaction sent by the malicious client, where the sender is another client and the receiver is the malicious client itself, does not appear in the priority buffer of any node.
     *
     * @throws Exception if an error occurs during the test.
     */
    @Test
    public void maliciousClientForgeSender() throws Exception {

        // Malicious client sending a transaction where the sender is another client and the receiver is himself
        Client client = instantiateClientTestLauncher(client2Id);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        // Assert that the transaction does not appear in the priority buffer of any node
        assert node1.getPriorityBufferTx().isEmpty();

        client.closeLinkSocket();

    }


    /**
     * Test case when the sender tries to send a transaction to him
     *
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     *
     * This test asserts the following:
     * 1. The transaction sent does not appear in the priority buffer of any node.
     *
     * @throws Exception if an error occurs during the test.
     */
    @Test
    public void sendToHimSelf() throws Exception {

        // Malicious client sending a transaction where the sender is another client and the receiver is himself
        Client client = instantiateClientTestLauncher(client2Id);
        client.transferAmountTest(client2Id, client2Id, 50, 20);

        // Assert that the transaction does not appear in the priority buffer of any node
        assert node1.getPriorityBufferTx().isEmpty();

        client.closeLinkSocket();

    }


    /**
     *
     * Validates that after a given set of transactions, when blocks are added to the ledger. - The ledger is able
     * to be properly audited.
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     */
    @Test
    public void testValidChainHash() throws Exception {
        Client client = instantiateClientTestLauncher(client1Id);
        client.transferAmountTest(client1Id, client2Id, 1, 20);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        Thread.sleep(3000);

        client.transferAmountTest(client1Id, client2Id, 1, 20);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        Thread.sleep(3000);

        client.transferAmountTest(client1Id, client2Id, 1, 20);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        Thread.sleep(3000);

        client.closeLinkSocket();

        for (int i = 0; i < node1.getLedger().size(); i++) {
            if (i == 0) continue;

            Block previousBlock = node1.getLedger().get(i - 1);
            String previousBlockHash = node1.getLedger().get(i).getPreviousBlockHash();

            assert CryptoLib.validateHash(previousBlock, previousBlockHash);
        }
    }

    /**
     *
     * Validates that after a given set of transactions, when blocks are added to the ledger. - The ledger is able
     * to be properly audited.
     * If a given hash of a block within the ledger is corrupted, the auditor is able to detect it.
     * Late messages of the client throw a "Socket is Closed" exception, because we close the socket
     * this does not affect the test and could be fixed by delaying the close, but we did not do it
     * to do not make the tests slower
     */
    @Test
    public void testInvalidChainHash() throws Exception {
        Client client = instantiateClientTestLauncher(client1Id);
        client.transferAmountTest(client1Id, client2Id, 1, 20);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        Thread.sleep(3000);

        client.transferAmountTest(client1Id, client2Id, 1, 20);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        Thread.sleep(3000);

        client.transferAmountTest(client1Id, client2Id, 1, 20);
        client.transferAmountTest(client1Id, client2Id, 1, 20);

        Thread.sleep(3000);

        client.closeLinkSocket();

        for (int i = 0; i < node1.getLedger().size(); i++) {
            if (i == 0) continue;

            Block previousBlock = node1.getLedger().get(i - 1);

            assert !CryptoLib.validateHash(previousBlock, "asdsad");
        }
    }



    /**
     * -----------------
     * AUX methods
     * -----------------
     */

    public static Client instantiateClientTestLauncher(String id) throws InterruptedException {

        ProcessConfig[] nodeConfigs = new ProcessConfigBuilder().fromFile(nodesConfigPath);
        ClientConfig[] clientConfigs = new ClientConfigBuilder().fromFile(clientsConfigPath);

        ClientConfig clientConfig = Arrays.stream(clientConfigs)
                .filter(c -> c.getId().equals(id))
                .findAny()
                .get();

        clientConfigs = ClientKeyLoader.loadKeys(
                id,
                nodeConfigs,
                clientConfigs,
                clientConfig,
                clientKeyPath,
                nodeKeyPath
        );

        return new Client(clientConfig, nodeConfigs, clientConfigs, true);

    }

    public static NodeService instantiateNodeService(String id, String nodesConfigPath, String clientsConfigPath) {
        ClientConfig[] clientConfigs = new ClientConfigBuilder().fromFile(clientsConfigPath);
        ProcessConfig[] nodeConfigs = new ProcessConfigBuilder().fromFile(nodesConfigPath);

        ProcessConfig leaderConfig = Arrays.stream(nodeConfigs)
                .filter(ProcessConfig::isLeader)
                .findAny()
                .get();

        ProcessConfig nodeConfig = Arrays.stream(nodeConfigs)
                .filter(c -> c.getId().equals(id))
                .findAny()
                .get();

        ProcessConfig[] modifiedNodeConfigs = loadKeysNodes(
                id,
                nodeConfigs,
                clientConfigs,
                nodeConfig,
                leaderConfig
        );

        // Abstraction to send and receive messages
        Link linkToNodes = new Link(nodeConfig, nodeConfig.getPort(), modifiedNodeConfigs, clientConfigs,
                Message.class, true, 200);

        return new NodeService(linkToNodes, nodeConfig, leaderConfig,
                modifiedNodeConfigs);
    }

    private static ProcessConfig[] loadKeysNodes(
            String id,
            ProcessConfig[] nodeConfigs,
            ClientConfig[] clientConfigs,
            ProcessConfig config,
            ProcessConfig leaderConfig
    ) {

        for (ProcessConfig configuration : nodeConfigs) {
            PrivateKey privateKey;
            PublicKey publicKey;

            String publicKeyPath = nodeKeyPath + "node" + configuration.getId() + "_pu.pem";
            String privateKeyPath = nodeKeyPath + "node" + configuration.getId() + "_pr.pem";

            try {
                publicKey = CryptoLib.loadPublicKey(publicKeyPath);
                config.setNodesPublicKey(configuration.getId(), publicKey);
                leaderConfig.setNodesPublicKey(configuration.getId(), publicKey);

                if (Objects.equals(id, configuration.getId())) {
                    privateKey = CryptoLib.loadPrivateKey(privateKeyPath);
                    config.setPrivateKey(privateKey);
                    leaderConfig.setPrivateKey(privateKey);
                }

                for (ClientConfig clientConfig : clientConfigs) {
                    publicKeyPath = clientKeyPath + "client" + clientConfig.getId() + "_pu.pem";

                    publicKey = CryptoLib.loadPublicKey(publicKeyPath);
                    String encodedPublicKey = Base64.getEncoder().encodeToString(SerenitySerializer.serialize(publicKey));
                    config.setClientsPublicKey(encodedPublicKey, publicKey);
                    leaderConfig.setClientsPublicKey(encodedPublicKey, publicKey);
                    clientConfig.setClientPublicKey(publicKey);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return nodeConfigs;
    }

    public String getEncodedPublicKey(Client client){
        return client.getClientLibrary().getSelfConfig().getEncodedPublicKey();
    }

}
