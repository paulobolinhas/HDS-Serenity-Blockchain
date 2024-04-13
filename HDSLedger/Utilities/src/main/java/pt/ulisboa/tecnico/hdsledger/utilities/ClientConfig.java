package pt.ulisboa.tecnico.hdsledger.utilities;

import pt.ulisboa.tecnico.hdsledger.utilities.model.ClientAccount;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HashMap;

public class ClientConfig {

    public ClientConfig() {}

    private String id;

    private String encodedPublicKey;

    private String hostname;

    private TestScenario testScenario;

    private int port;

    private PrivateKey privateKey;
    private ClientAccount clientAccount;
    private final HashMap<String, PublicKey> nodesPublicKeys = new HashMap<>();


    public int getPort() {
        return port;
    }

    public String getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public void setTestScenario(TestScenario testScenario) {
        this.testScenario = testScenario;
    }

    public TestScenario getTestScenario() {
        return this.testScenario;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public PublicKey getNodesPublicKey(String id) {
        return nodesPublicKeys.get(id);
    }

    public void setNodesPublicKey(String id, PublicKey publicKey) {
        this.nodesPublicKeys.put(id, publicKey);
    }

    public void setClientPublicKey(PublicKey publicKey) {
        this.clientAccount = new ClientAccount(id, publicKey);
        this.encodedPublicKey = Base64.getEncoder().encodeToString(SerenitySerializer.serialize(publicKey));
    }

    public String getEncodedPublicKey() {
        return this.encodedPublicKey;
    }

    public ClientAccount getAccount() {
        return this.clientAccount;
    }

}
