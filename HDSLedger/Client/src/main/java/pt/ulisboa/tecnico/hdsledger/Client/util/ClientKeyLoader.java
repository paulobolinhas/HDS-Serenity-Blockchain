package pt.ulisboa.tecnico.hdsledger.Client.util;

import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;
import pt.ulisboa.tecnico.hdsledger.utilities.ClientConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

public class ClientKeyLoader {

    public static ClientConfig[] loadKeys(
            String id,
            ProcessConfig[] nodeConfigs,
            ClientConfig[] clientConfigs,
            ClientConfig config,
            String clientKeyPath,
            String nodeKeysPath
    ) {
        for (ClientConfig clientConfig : clientConfigs) {
            PrivateKey privateKey;
            PublicKey publicKey;

            String privateKeyPath = clientKeyPath + "client" + clientConfig.getId() + "_pr.pem";
            String clientPublicKeyPath = clientKeyPath + "client" + clientConfig.getId() + "_pu.pem";

            try {
                PublicKey clientPublicKey = CryptoLib.loadPublicKey(clientPublicKeyPath);

                if (Objects.equals(id, clientConfig.getId())) {

                    privateKey = CryptoLib.loadPrivateKey(privateKeyPath);

                    // Set its own data
                    config.setPrivateKey(privateKey);
                    config.setClientPublicKey(clientPublicKey);
                }

                // Set the data on the global tracker
                clientConfig.setClientPublicKey(clientPublicKey);

                for (ProcessConfig nodeConfig : nodeConfigs) {
                    String nodePublicKeyPath = nodeKeysPath + "node" + nodeConfig.getId() + "_pu.pem";
                    publicKey = CryptoLib.loadPublicKey(nodePublicKeyPath);
                    config.setNodesPublicKey(nodeConfig.getId(), publicKey);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return clientConfigs;
    }
}
