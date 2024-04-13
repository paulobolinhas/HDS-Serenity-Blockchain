package pt.ulisboa.tecnico.hdsledger.utilities;

import pt.ulisboa.tecnico.hdsledger.utilities.model.ClientAccount;
import pt.ulisboa.tecnico.hdsledger.utilities.model.ProcessAccount;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;

public class ProcessConfig {

    private boolean isLeader;

    private String hostname;

    private String id;

    private int port;

    private final HashMap<String, PublicKey> nodesPublicKeys = new HashMap<>();

    // The key represents the Client's Public Key formatted in Base64
    private final HashMap<String, ClientAccount> clientAccounts = new HashMap<>();

    private ProcessAccount processAccount = null;


    private TestScenario testScenario;

    private PrivateKey privateKey;

    public boolean isLeader() {
        return isLeader;
    }

    public void setLeader(boolean leader) {
        this.isLeader = leader;
    }

    public int getPort() {
        return port;
    }

    public String getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public PublicKey getNodesPublicKey(String id) {
        return nodesPublicKeys.get(id);
    }

    public void setNodesPublicKey(String id, PublicKey publicKey) {

        this.nodesPublicKeys.put(id, publicKey);
        if (id.equals(this.id)) {
            this.processAccount = new ProcessAccount(id, publicKey);
        }
    }

    public PublicKey getClientsPublicKey(String id) {
        return clientAccounts.get(id).getPublicKey();
    }

    public ClientAccount getAccount(String id) {
        return clientAccounts.get(id);
    }

    public ProcessAccount getProcessAccount() {
        return this.processAccount;
    }

    public  HashMap<String, ClientAccount> getAccountsData() {
        return clientAccounts;
    }

    public void setClientsPublicKey(String id, PublicKey publicKey) {
        this.clientAccounts.put(id, new ClientAccount(id, publicKey));
    }

    public void transfer(String from, String to, int amount) {
        this.getAccount(from).subtractBalance(amount);
        this.getAccount(to).addBalance(amount);
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public void setTestScenario(TestScenario testScenario) {
        this.testScenario = testScenario;
    }

    public TestScenario getTestScenario() {
        return this.testScenario;
    }
}