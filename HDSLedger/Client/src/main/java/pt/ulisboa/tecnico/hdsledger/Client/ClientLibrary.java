package pt.ulisboa.tecnico.hdsledger.Client;

import com.google.gson.Gson;
import io.reactivex.rxjava3.schedulers.Schedulers;
import pt.ulisboa.tecnico.hdsledger.Client.communication.ClientLibraryExecutionInterface;
import pt.ulisboa.tecnico.hdsledger.communication.Block;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.MessageSigWrapper;
import pt.ulisboa.tecnico.hdsledger.communication.Transaction;
import pt.ulisboa.tecnico.hdsledger.communication.builder.MessageBuilder;
import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;
import pt.ulisboa.tecnico.hdsledger.utilities.*;
import pt.ulisboa.tecnico.hdsledger.utilities.model.ClientAccount;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.io.IOException;
import java.net.*;
import java.security.KeyPair;
import java.security.PublicKey;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;


public class ClientLibrary implements ClientContract {

    private static final CustomLogger LOGGER = new CustomLogger(ClientLibrary.class.getName());

    private ProcessConfig[] nodesConfig;

    private ClientConfig[] clientConfigs;

    public ClientConfig getSelfConfig() {
        return selfConfig;
    }

    public ClientAccount getClientAccByID(String id){
        return Arrays.stream(this.clientConfigs).filter(clientConfig -> clientConfig.getId().equals(id)).findFirst().get().getAccount();
    }


    private int f;

    private String hostname;
    private int port;

    private final CollapsingSet receivedAcks = new CollapsingSet();

    private final Map<String, CollapsingSet> receivedMessagesNodes = new ConcurrentHashMap<>();

    // Instance -> Sender ID -> Consensus message
    private Map<Integer, Map<String, Message>> decidedMessages;

    // originalMessageID -> Sender ID -> Consensus message
    private Map<String, Map<String, Message>> requestBalanceMessages;


    private Map<Integer, Boolean> counterf1Messages;
    private Map<Integer, Boolean> counterRequestBalancef1Messages;

    private ClientConfig selfConfig;

    private final AtomicInteger consensusInstance = new AtomicInteger(0);

    private final AtomicInteger currentMessageId = new AtomicInteger(0);

    private final DatagramSocket socket;

    private static long expirationTimeSeconds = 10;
    private final int BASE_SLEEP_TIME;

    private int currentFee = 20;

    private final ClientLibraryExecutionInterface clientLibraryExecutionInterface;


    public ClientLibrary(
            ProcessConfig[] nodesConfig,
            ClientConfig selfConfig,
            ClientConfig[] clientConfigs,
            ClientLibraryExecutionInterface clientLibraryExecutionInterface
    ) {
        this.nodesConfig = nodesConfig;
        this.clientConfigs = clientConfigs;
        this.selfConfig = selfConfig;
        this.hostname = selfConfig.getHostname();
        this.port = selfConfig.getPort();
        this.decidedMessages = new ConcurrentHashMap<>();
        this.requestBalanceMessages = new ConcurrentHashMap<>();
        this.counterf1Messages = new HashMap<>();
        this.counterRequestBalancef1Messages = new HashMap<>();
        this.BASE_SLEEP_TIME = 200;
        this.clientLibraryExecutionInterface = clientLibraryExecutionInterface;

        this.f = Math.floorDiv(this.nodesConfig.length - 1, 3); // 1


        for (ProcessConfig processConfig : this.nodesConfig) {
            this.receivedMessagesNodes.put(processConfig.getId(), new CollapsingSet());
        }

        try {
            this.socket = new DatagramSocket(port, InetAddress.getByName(this.hostname));
        } catch (UnknownHostException | SocketException e) {
            throw new HDSSException(ErrorMessage.CannotOpenSocket);
        }
    }

    private Optional<PublicKey> getClientPublicKeyById(String id) {
        return Arrays.stream(clientConfigs).filter(clientConfig -> Objects.equals(clientConfig.getId(), id))
                .map(clientConfig -> clientConfig.getAccount().getPublicKey())
                .findFirst();
    }

    @Override
    public void transfer(
            PublicKey source,
            PublicKey destination,
            String senderID,
            String receiverID,
            int amount,
            int fee
    ) throws InterruptedException {

        if (this.selfConfig.getAccount().getBalance() >= fee) {
            this.selfConfig.getAccount().subtractBalance(this.currentFee); //current fee, not the fee. otherwise the client could send just 1
            System.out.println("Subtracted fee " + this.currentFee + " for making the transaction");
        } else {
            System.out.println("Not enough balance to pay for fees, not doing the transaction...");
            return;
        }

        String encodedBase64SenderPublicKey = Base64.getEncoder().encodeToString(SerenitySerializer.serialize(source));
        String encodedBase64ReceiverPublicKey = Base64.getEncoder().encodeToString(SerenitySerializer.serialize(destination));

        Transaction transaction = new Transaction(
                encodedBase64SenderPublicKey,
                encodedBase64ReceiverPublicKey,
                senderID,
                receiverID,
                amount,
                fee
        );

        Message message = new MessageBuilder(encodedBase64SenderPublicKey, Message.Type.TRANSFER, Message.ClientType.CLIENT)
                .setMessageId(currentMessageId.incrementAndGet())
                .setMessage(transaction.toJson())
                .build();

        ProcessConfig leader = getLeaderNode();

        append(message, leader, false);
    }

    @Override
    public void requestBalance(PublicKey publicKey) throws InterruptedException {
        String encodedPublicKey = Base64.getEncoder().encodeToString(SerenitySerializer.serialize(publicKey));
        Message message = new MessageBuilder(encodedPublicKey, Message.Type.REQUEST_BALANCE, Message.ClientType.CLIENT)
                .setMessageId(currentMessageId.incrementAndGet())
                .build();


        broadcastGlobally(message);
    }


    /**
     * Validates the receiver's ID and retrieves their public key if available.
     *
     * @param senderID    The ID of the sender.
     * @param receiverID  The ID of the receiver.
     * @return An [Optional] containing the receiver's [PublicKey] if the receiver is valid
     *         and their public key is available. Returns an empty [Optional] otherwise.
     */
    public Optional<PublicKey> validateAndGetReceiverPublicKey(String senderID, String receiverID) {

        if (!Objects.equals(senderID, this.selfConfig.getId())) {
            System.out.println();
            System.out.println("====================================================================================");
            System.out.println("Client ID does not match requested sender");
            System.out.println("====================================================================================");
            System.out.println();


            clientLibraryExecutionInterface.onActionResultReceived();

            return Optional.empty();
        }

        if (Objects.equals(receiverID, senderID)) {
            System.out.println();
            System.out.println("====================================================================================");
            System.out.println("Cannot transfer funds to your own account.");
            System.out.println("====================================================================================");
            System.out.println();

            clientLibraryExecutionInterface.onActionResultReceived();

            return Optional.empty();
        }

        Optional<PublicKey> receiverPublicKey = getClientPublicKeyById(receiverID);

        if (receiverPublicKey.isEmpty()) {
            System.out.println();
            System.out.println("====================================================================================");
            System.out.printf("Client %s is not available on Serenity Ledger.%n", receiverID);
            System.out.println("====================================================================================");
            System.out.println();

            clientLibraryExecutionInterface.onActionResultReceived();

            return Optional.empty();
        }

        return receiverPublicKey;
    }


    private void append(Message message, ProcessConfig config, boolean isBroadcast) throws InterruptedException {

        int count = 1;
        int sleepTime = BASE_SLEEP_TIME;

        for (; ; ) {
            LOGGER.log(Level.INFO, MessageFormat.format(
                    "{0} - Sending {1} message to {2}:{3} with message ID {4} - Attempt #{5}", this.selfConfig.getId(),
                    message.getType(), config.getId(), config.getPort(), message.getMessageId(), count++));

            unreliableSend(config, message);

            // Wait (using exponential back-off), then look for ACK
            Thread.sleep(sleepTime);

            // Receive method will set receivedAcks when sees corresponding ACK
            if (receivedAcks.contains(message.getMessageId())) {
                System.out.println("Recebeu ACK de mensagem com ID: " + message.getMessageId());
                break;
            }

            sleepTime <<= 1;
        }

        LOGGER.log(Level.INFO, MessageFormat.format("{0} - Message {1} sent to {2}:{3} successfully",
                this.selfConfig.getId(), message.getType(), config.getId(), this.getLeaderNode().getPort()));


        if (!isBroadcast)
            initiateTracker(message);
    }

    private void initiateTracker(Message message) {
        RoundChangeTracker roundChangeTracker = new RoundChangeTracker(expirationTimeSeconds);

        roundChangeTracker.startTimerClient(message.getMessageId())
                .getExpirationObservable()
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .doOnNext(expiredMessageId -> {


                    Optional<String> decidedValue = hasValidDecidedFPlus1(this.consensusInstance.get());
                    if (decidedValue.isEmpty()) {
                        LOGGER.log(Level.WARNING, String.format("--- Leader timed out for message with id: %d ---", expiredMessageId));
                        //dont increment message ID so that the leader can get that is a repeated message
                        if (this.selfConfig.getTestScenario() != TestScenario.DETECT_REPLAY_ATTACK)
                            broadcastGlobally(message);
                    }
                })
                .subscribe();
    }

    private void broadcastGlobally(Message message) throws InterruptedException {
        for (ProcessConfig nodeConfig : this.nodesConfig) {
            append(message, nodeConfig, true);
        }
    }

    private ProcessConfig getLeaderNode() {
        Optional<ProcessConfig> leaderNode = Arrays.stream(nodesConfig)
                .filter(ProcessConfig::isLeader)
                .findFirst();

        return leaderNode.orElse(null);
    }

    private void unreliableSend(ProcessConfig nodeConfig, Message message) {

        String signature = CryptoLib.signData(message, this.selfConfig.getPrivateKey());

        TestScenario testScenario = this.selfConfig.getTestScenario();

        if (testScenario == TestScenario.UNAUTHORIZED_CLIENT) {

            KeyPair keyPair = CryptoLib.generateKeyPair();
            assert keyPair != null;
            signature = CryptoLib.signData(message, keyPair.getPrivate());

        } else if (testScenario == TestScenario.MALFORMED_CLIENT_SIGNATURE) {

            signature = CryptoLib.signData(message, nodeConfig.getPrivateKey());
            signature += "CORRUPTED";

        }

        MessageSigWrapper messageSigWrapper = new MessageSigWrapper(message, signature);

        try {
            InetAddress serverAddress = InetAddress.getByName(nodeConfig.getHostname());

            byte[] serializedData = SerenitySerializer.serialize(messageSigWrapper);

            DatagramPacket sendPacket = new DatagramPacket(serializedData, serializedData.length, serverAddress, nodeConfig.getPort());
            this.socket.send(sendPacket);

        } catch (UnknownHostException | SocketException e) {
            throw new HDSSException(ErrorMessage.CannotOpenSocket);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void listen() {

        try {
            // Thread to listen on every request
            new Thread(() -> {
                try {
                    while (true) {
                        Message message = this.receive();

                        new Thread(() -> {

                            switch (message.getType()) {
                                case TRANSFER -> {

                                    this.addDecidedMessage(message);

                                    Optional<String> decidedValue = hasValidDecidedFPlus1(this.consensusInstance.get());

                                    if (decidedValue.isPresent() && !this.counterf1Messages.getOrDefault(message.getConsensusInstanceID(), false)) {

                                        this.counterf1Messages.put(message.getConsensusInstanceID(), true);

                                        if (message.getErrorType() == null) {
                                            Block decidedBlock = Block.fromJson(decidedValue.get());

                                            System.out.println("=====================\n");
                                            LOGGER.log(Level.INFO, String.format("Blockchain appended the block in the position: %s", decidedBlock.getBlockId()));

                                            //does through all the transactions and if some his related to the node itself (at least one shoule be)
                                            //subtracts the amount in the transaction
                                            for (MessageSigWrapper txMessage : decidedBlock.getPriorityTxQueue()) {

                                                Message internalMessage = txMessage.getInternalMessage();
                                                Transaction transaction = internalMessage.deserializeTransaction();

                                                if (internalMessage.getSenderId().equals(this.selfConfig.getEncodedPublicKey())) {
                                                    //dont subtract fee because thats substracted before sending the transaction
                                                    this.selfConfig.getAccount().subtractBalance(transaction.getAmount());
                                                }
                                            }

//                                                not working - its needed to put the balance information on the config file. how is it being done on the nodes?
                                            System.out.println("New Balance of user: " + this.selfConfig.getAccount().getBalance());
                                            System.out.println("\n=====================");

                                            clientLibraryExecutionInterface.onActionResultReceived();
                                        } else {
                                            System.out.println("=====================\n");
                                            LOGGER.log(Level.INFO, String.format("Error: %s", message.getErrorType()));
                                            System.out.println("\n=====================");

                                            clientLibraryExecutionInterface.onActionResultReceived();
                                        }
                                    }


                                }
                                case REQUEST_BALANCE -> {

                                    this.addRequestMessage(message);

                                    Optional<String> requestedValue = hasValidRequestBalanceFPlus1(message.getReplyToMessageId());

                                    if (requestedValue.isPresent() && !this.counterRequestBalancef1Messages.getOrDefault(message.getReplyToMessageId(), false)) {

                                        this.counterRequestBalancef1Messages.put(message.getReplyToMessageId(), true);

                                        int balance = Integer.parseInt(requestedValue.get());

                                        System.out.println("=====================");
                                        LOGGER.log(Level.INFO, String.format("Current balance: %s", balance));
                                        this.selfConfig.getAccount().setBalance(balance); //synchronizes balance with blockchain
                                        System.out.println("=====================");

                                        clientLibraryExecutionInterface.onActionResultReceived();
                                    }

                                }
                                case ACK ->
                                        LOGGER.log(Level.INFO, MessageFormat.format("{0} - Received ACK message from {1} replying to {2}",
                                                this.selfConfig.getId(), message.getSenderId(), message.getMessageId()));
                                case IGNORE -> {
                                    /*
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("Client {0} - Received IGNORE message from {1}",
                                                    this.selfConfig.getId(), message.getSenderId()));
                                     */ // TODO add a debug mode for logs
                                }
                            }

                        }).start();
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    public Message receive() throws IOException, ClassNotFoundException {

        byte[] buf = new byte[65535];
        DatagramPacket response = new DatagramPacket(buf, buf.length);

        this.socket.receive(response);

        MessageSigWrapper messageSigWrapper = SerenitySerializer.deserialize(response.getData(), response.getOffset(), response.getLength());
        Message message = messageSigWrapper.getInternalMessage();

        Gson gson = new Gson();
        String serializedMessage = gson.toJson(message);

        System.out.println("Received message: " + serializedMessage);

        System.out.println("SenderID: " + message.getSenderId());

        boolean isVerified = CryptoLib.validateSignature(
                message,
                messageSigWrapper.getSignature(),
                this.selfConfig.getNodesPublicKey(message.getSenderId())
        );

        if (message.getType() == Message.Type.ACK) {
            this.receivedAcks.add(message.getMessageId());
            return message;
        }

        System.out.println("Node signature verification result on client: " + (isVerified ? "Successful" : "Failed"));

        boolean isRepeated = !receivedMessagesNodes.get(message.getSenderId()).add(message.getMessageId());

        if (isRepeated) {
            System.out.println("Mensagem " + message.getMessageId() + " repetida, ignorar");
            message.setType(Message.Type.IGNORE);
        }


        Message ack = new MessageBuilder(this.selfConfig.getEncodedPublicKey(), Message.Type.ACK, Message.ClientType.CLIENT)
                .setMessageId(message.getMessageId())
                .build();

        ProcessConfig nodeToAck = Arrays.stream(this.nodesConfig).filter(node -> Objects.equals(node.getId(), message.getSenderId())).findFirst().get();
        System.out.println("Sending ACK of message " + ack.getMessageId());


        unreliableSend(nodeToAck, ack);

        return message;
    }

    private Optional<String> hasValidDecidedFPlus1(int consensusInstanceID) {
        int minimumMessages = f + 1;

        HashMap<String, Integer> frequency = new HashMap<>();

        Map<String, Message> consensusInstance = this.decidedMessages.get(consensusInstanceID);
        if (consensusInstance == null)
            return Optional.empty();

        consensusInstance.values().forEach((message) -> {
            String data = message.getMessage();
            frequency.put(data, frequency.getOrDefault(data, 0) + 1);
        });

        return frequency.entrySet().stream().
                filter((Map.Entry<String, Integer> entry) -> entry.getValue() >= minimumMessages).
                map(Map.Entry::getKey).
                findFirst();
    }

    private Optional<String> hasValidRequestBalanceFPlus1(int originalRequestID) {
        int minimumMessages = f + 1;

        HashMap<String, Integer> frequency = new HashMap<>();

        Map<String, Message> consensusInstance = this.requestBalanceMessages.get(String.valueOf(originalRequestID));
        if (consensusInstance == null)
            return Optional.empty();

        consensusInstance.values().forEach((message) -> {
            String data = message.getMessage();
            frequency.put(data, frequency.getOrDefault(data, 0) + 1);
        });

        return frequency.entrySet().stream().
                filter((Map.Entry<String, Integer> entry) -> entry.getValue() >= minimumMessages).
                map(Map.Entry::getKey).
                findFirst();
    }

    public void addDecidedMessage(Message message) {

        int consensusInstance = message.getConsensusInstanceID();
        this.consensusInstance.set(consensusInstance);
        this.decidedMessages.putIfAbsent(consensusInstance, new ConcurrentHashMap<>());
        this.decidedMessages.get(consensusInstance).put(message.getSenderId(), message);

    }

    public void addRequestMessage(Message message) {

        String requestMessageId = String.valueOf(message.getReplyToMessageId());
        this.requestBalanceMessages.putIfAbsent(requestMessageId, new ConcurrentHashMap<>());
        this.requestBalanceMessages.get(requestMessageId).put(message.getSenderId(), message);

    }

    public List<String> getAvailableClientIDs() {
        return Arrays.stream(clientConfigs).map(ClientConfig::getId).toList();
    }

    public int getCurrentFee() {
        return this.currentFee;
    }

    public void closeSocket() {
        this.socket.close();
    }
}

