package pt.ulisboa.tecnico.hdsledger.service;

import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;
import pt.ulisboa.tecnico.hdsledger.service.services.NodeService;
import pt.ulisboa.tecnico.hdsledger.utilities.*;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.logging.Level;

public class Node {

    private static final CustomLogger LOGGER = new CustomLogger(Node.class.getName());
    // Hardcoded path to files
    private static String nodesConfigPath = "src/main/resources/";
    private static String clientsConfigPath = "src/main/resources/";

    private static String nodeKeysPath = "src/main/resources/NodeKeys/";
    private static String clientKeysPath = "src/main/resources/ClientKeys/";
    private static TestScenario testScenario = TestScenario.PRODUCTION;


    public static void main(String[] args) {

        try {
            // Command line arguments
            String id = args[0];
            nodesConfigPath += args[1];
            clientsConfigPath += args[2];

            try {
                String argTestScenario = args[3];

                testScenario = TestScenario.valueOf(argTestScenario);

            } catch (RuntimeException re) {
                testScenario = TestScenario.PRODUCTION;
            }

            NodeService nodeService = instantiateNodeService(id);

            nodeService.listen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static NodeService instantiateNodeService(String id) {
        return instantiateNodeService(id, nodesConfigPath, clientsConfigPath);
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

        ProcessConfig[] modifiedNodeConfigs = loadKeys(
                id,
                nodeConfigs,
                clientConfigs,
                nodeConfig,
                leaderConfig
        );

        nodeConfig.setTestScenario(testScenario);
        leaderConfig.setTestScenario(testScenario);

        // Abstraction to send and receive messages
        Link linkToNodes = new Link(nodeConfig, nodeConfig.getPort(), modifiedNodeConfigs, clientConfigs,
                Message.class, true, 200);

        return new NodeService(linkToNodes, nodeConfig, leaderConfig,
                nodeConfigs);
    }

    private static ProcessConfig[] loadKeys(
            String id,
            ProcessConfig[] nodeConfigs,
            ClientConfig[] clientConfigs,
            ProcessConfig config,
            ProcessConfig leaderConfig
    ) {

        for (ProcessConfig configuration : nodeConfigs) {
            PrivateKey privateKey;
            PublicKey publicKey;

            String publicKeyPath = nodeKeysPath + "node" + configuration.getId() + "_pu.pem";
            String privateKeyPath = nodeKeysPath + "node" + configuration.getId() + "_pr.pem";

            try {
                publicKey = CryptoLib.loadPublicKey(publicKeyPath);
                config.setNodesPublicKey(configuration.getId(), publicKey);
                leaderConfig.setNodesPublicKey(configuration.getId(), publicKey);

                if (Objects.equals(id, configuration.getId())) {
                    privateKey = CryptoLib.loadPrivateKey(privateKeyPath);
                    config.setPrivateKey(privateKey);
                    leaderConfig.setPrivateKey(privateKey);

                    if (config.isLeader()) {
                        if (testScenario == TestScenario.LEADER_TIMEOUT
                                || testScenario == TestScenario.DETECT_REPLAY_ATTACK) {
                            leaderConfig.setTestScenario(testScenario);

                            System.out.println("Setting test scenario to: " + testScenario.toString());

                        }
                    } else if (Objects.equals(config.getId(), "2")
                            && testScenario == TestScenario.MALFORMED_NODE_SIGNATURE) {
                        config.setTestScenario(testScenario);
                        KeyPair keyPair = CryptoLib.generateKeyPair();

                        assert keyPair != null;
                        config.setPrivateKey(keyPair.getPrivate());

                        System.out.println("Setting test scenario to: " + testScenario.toString());
                    } else if (Objects.equals(config.getId(), "3")
                            && testScenario == TestScenario.UNAUTHORIZED_NODE) {
                        config.setTestScenario(testScenario);
                        System.out.println("Setting test scenario to: " + testScenario.toString());
                    } else if (testScenario == TestScenario.ROUND_CHANGE_AFTER_PREPARE) {
                        config.setTestScenario(testScenario);
                        System.out.println("Setting test scenario to: " + testScenario.toString());
                    }
                }

                for (ClientConfig clientConfig : clientConfigs) {
                    publicKeyPath = clientKeysPath + "client" + clientConfig.getId() + "_pu.pem";

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

        LOGGER.log(Level.INFO, MessageFormat.format("{0} - Running at {1}:{2}; is leader: {3}",
                config.getId(), config.getHostname(), config.getPort(),
                config.isLeader()));

        return nodeConfigs;
    }
}
