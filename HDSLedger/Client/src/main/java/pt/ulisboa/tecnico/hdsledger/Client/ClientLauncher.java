package pt.ulisboa.tecnico.hdsledger.Client;

import pt.ulisboa.tecnico.hdsledger.service.Node;
import pt.ulisboa.tecnico.hdsledger.utilities.*;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.util.Arrays;
import java.util.logging.Level;

import static pt.ulisboa.tecnico.hdsledger.Client.util.ClientKeyLoader.loadKeys;


public class ClientLauncher {
    private static final CustomLogger LOGGER = new CustomLogger(Node.class.getName());
    private static String nodesConfigPath = "src/main/resources/";
    private static String clientsConfigPath = "src/main/resources/";
    private static TestScenario testScenario = TestScenario.PRODUCTION;

    private static String clientKeyPath = "src/main/resources/ClientKeys/";

    private static String nodeKeysPath = "src/main/resources/NodeKeys/";

    public static void main(String[] args) {

        try {
            // Command line arguments
            String id = args[0];
            nodesConfigPath += args[1];
            clientsConfigPath += args[2];

            try {
                String argTestScenario = args[3];

                testScenario = TestScenario.valueOf(argTestScenario);
            } catch (Exception re) {
                testScenario = TestScenario.PRODUCTION;
            }

            LOGGER.log(Level.INFO, String.format("Look-up config at %s", nodesConfigPath));

            ProcessConfig[] nodeConfigs = new ProcessConfigBuilder().fromFile(nodesConfigPath);
            ClientConfig[] clientConfigs = new ClientConfigBuilder().fromFile(clientsConfigPath);

            ClientConfig clientConfig = Arrays.stream(clientConfigs)
                    .filter(c -> c.getId().equals(id))
                    .findAny()
                    .get();

            clientConfigs = loadKeys(
                    id,
                    nodeConfigs,
                    clientConfigs,
                    clientConfig,
                    clientsConfigPath,
                    nodeKeysPath
            );

            LOGGER.log(Level.INFO, String.format("Client launched with id: %s", id));

            new Client(clientConfig, nodeConfigs, clientConfigs);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
