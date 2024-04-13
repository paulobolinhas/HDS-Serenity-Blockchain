package pt.ulisboa.tecnico.hdsledger.Client;

import pt.ulisboa.tecnico.hdsledger.Client.communication.ClientLibraryExecutionInterface;
import pt.ulisboa.tecnico.hdsledger.service.Node;
import pt.ulisboa.tecnico.hdsledger.utilities.ClientConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.model.ClientAccount;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.logging.Level;


public class Client implements ClientLibraryExecutionInterface {
    private final CustomLogger LOGGER = new CustomLogger(Node.class.getName());
    private String clientId;
    private String hostname;
    private int port;
    private ClientLibrary clientLibrary;

    public ClientLibrary getClientLibrary() {
        return clientLibrary;
    }

    private BufferedReader reader;

    private ClientConfig selfConfig;

    public Client(ClientConfig selfConfig, ProcessConfig[] nodeConfigs, ClientConfig[] clientConfigs) throws InterruptedException {
        this.clientId = selfConfig.getId();
        this.hostname = selfConfig.getHostname();
        this.port = selfConfig.getPort();
        this.selfConfig = selfConfig;

        this.clientLibrary = new ClientLibrary(nodeConfigs, selfConfig, clientConfigs, this);

        this.clientLibrary.listen();
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.run();
    }

    public Client(ClientConfig selfConfig, ProcessConfig[] nodeConfigs, ClientConfig[] clientConfigs, boolean isClientTest) throws InterruptedException {
        this.clientId = selfConfig.getId();
        this.hostname = selfConfig.getHostname();
        this.port = selfConfig.getPort();
        this.selfConfig = selfConfig;

        this.clientLibrary = new ClientLibrary(nodeConfigs, selfConfig, clientConfigs, this);

        this.clientLibrary.listen();
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    private void run() {
        try {
            String input;


            while (true) {
                System.out.println("------- Node " + clientId + "-------");
                displayMenu();
                input = reader.readLine();

                switch (input) {
                    case "1" -> listAvailableClients();
                    case "2" -> checkBalance();
                    case "3" -> transferAmount();
                    case "4" -> {
                        System.out.println("Exiting...");
                        return;
                    }
                    default -> System.out.println("Invalid option, please try again.");
                }
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listAvailableClients() {
        System.out.println("\n================ Available clients ================\n");
        clientLibrary.getAvailableClientIDs().forEach(nodeID -> LOGGER.log(Level.INFO, "Client " + nodeID));
        System.out.println("\n================ End of Available clients ================\n");

        displayMenu();
    }

    private void checkBalance() throws InterruptedException {
        System.out.println("\n================ Requesting balance for client " + this.clientId + " ================\n");
        clientLibrary.requestBalance(this.selfConfig.getAccount().getPublicKey());
        System.out.println("\n================ End of Requesting balance for client " + this.clientId + " ================\n");
    }

    private void transferAmount() throws InterruptedException {

        System.out.println("\n================================\n");

        Scanner scanner = new Scanner(System.in);

        System.out.println("Enter the recipient's ID: ");
        String recipientId = scanner.nextLine();

        Optional<PublicKey> publicKey = clientLibrary
                .validateAndGetReceiverPublicKey(this.selfConfig.getId(), recipientId);

        if (publicKey.isEmpty())
            return;

        System.out.println("Enter the amount to transfer: ");
        int amount = scanner.nextInt();

        int currentFee = clientLibrary.getCurrentFee();
        System.out.println("Enter the fee you want to apply (Current fee is: " + currentFee + "):");
        int fee = scanner.nextInt();

        System.out.println("\n================ Transferring funds ================\n");

        clientLibrary.transfer(
                this.selfConfig.getAccount().getPublicKey(),
                publicKey.get(),
                this.selfConfig.getId(),
                recipientId,
                amount,
                fee
        );
    }

    private void displayMenu() {
        System.out.println("Menu:");
        System.out.println("1. List available clients");
        System.out.println("2. Check balance");
        System.out.println("3. Transfer balance (new Append)");
        System.out.println("4. Exit");
        System.out.print("Choose an option: ");
    }

    @Override
    public void onActionResultReceived() {
        //displayMenu();
    }

    /**
     *
     * Test methods
     *
     * ------------------------------------------------------------------------------------------------------
     *
     */

    public void transferAmountTest(String senderID, String receiverID, int amount, int fee) throws InterruptedException {

            Optional<PublicKey> publicKey = clientLibrary
                .validateAndGetReceiverPublicKey(senderID, receiverID);

        if (publicKey.isEmpty())
            return;

        clientLibrary.transfer(
                clientLibrary.getClientAccByID(senderID).getPublicKey(),
                publicKey.get(),
                senderID,
                receiverID,
                amount,
                fee
        );
    }

    public ClientAccount getClientAccByID(String senderID){
        return clientLibrary.getClientAccByID(senderID);
    }

    public void closeLinkSocket() {
        this.clientLibrary.closeSocket();
    }
}
