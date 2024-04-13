package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;
import pt.ulisboa.tecnico.hdsledger.communication.Message.Type;
import pt.ulisboa.tecnico.hdsledger.communication.builder.MessageBuilder;
import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;
import pt.ulisboa.tecnico.hdsledger.utilities.*;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.io.*;
import java.net.*;
import java.security.KeyPair;
import java.security.PublicKey;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogManager;

public class Link {

    private static final CustomLogger LOGGER = new CustomLogger(Link.class.getName());
    // Time to wait for an ACK before resending the message
    private final int BASE_SLEEP_TIME;
    // UDP Socket
    private final DatagramSocket socket;
    // Map of all nodes in the network
    private final Map<String, ProcessConfig> nodes = new ConcurrentHashMap<>();
    private final Map<String, ClientConfig> clients = new ConcurrentHashMap<>();


    // Reference to the node itself
    private final ProcessConfig config;
    // Class to deserialize messages to
    private final Class<? extends Message> messageClass;
    // Set of received messages from specific node (prevent duplicates)
    private final Map<String, CollapsingSet> receivedMessagesNodes = new ConcurrentHashMap<>();

    // ------- usar isto no receive ---------
    private final Map<String, CollapsingSet> receivedMessagesClients = new ConcurrentHashMap<>();


    // Set of received ACKs from specific node
    private final CollapsingSet receivedAcks = new CollapsingSet();
    // Message counter
    private final AtomicInteger messageCounter = new AtomicInteger(0);
    // Send messages to self by pushing to queue instead of through the network
    private final Queue<MessageSigWrapper> localhostQueue = new ConcurrentLinkedQueue<>();

    private final Long MESSAGE_TIME_THRESHOLD = 30_000L;

    public Link(ProcessConfig self, int port, ProcessConfig[] nodes, ClientConfig[] clients, Class<? extends Message> messageClass) {
        this(self, port, nodes, clients, messageClass, false, 200);
    }

    public Link(ProcessConfig self, int port, ProcessConfig[] nodes, ClientConfig[] clients, Class<? extends Message> messageClass,
                boolean activateLogs, int baseSleepTime) {

        this.config = self;
        this.messageClass = messageClass;
        this.BASE_SLEEP_TIME = baseSleepTime;

        Arrays.stream(nodes).forEach(node -> {
            String id = node.getId();
            this.nodes.put(id, node);
            this.receivedMessagesNodes.put(id, new CollapsingSet());
        });

        Arrays.stream(clients).forEach(client -> {
            String encodedPublicKey = Base64.getEncoder()
                    .encodeToString(SerenitySerializer.serialize(client.getAccount().getPublicKey()));

            this.clients.put(encodedPublicKey, client);
            this.receivedMessagesClients.put(encodedPublicKey, new CollapsingSet());
        });

        try {
            this.socket = new DatagramSocket(port, InetAddress.getByName(config.getHostname()));
        } catch (UnknownHostException | SocketException e) {
            throw new HDSSException(ErrorMessage.CannotOpenSocket);
        }
        if (!activateLogs) {
            LogManager.getLogManager().reset();
        }
    }

    public void closeSocket() throws SocketException {
        this.socket.close();
    }

    public boolean isClientMessageRepeated(Message message) {
        return !this.receivedMessagesClients.get(message.getSenderId()).add(message.getMessageId());
    }

    public Map<String, ClientConfig> getClients() {
        return this.clients;
    }

    private Message deepCopy(Message original) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bos);
            out.writeObject(original);
            out.flush();
            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            ObjectInputStream in = new ObjectInputStream(bis);
            return (Message) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null; // Handle error appropriately in your code
        }
    }

    /*
     * Broadcasts a message to all nodes in the network
     *
     * @param data The message to be broadcasted
     */
    public void broadcast(Message data, Message.ClientType broadcastTo) {

        if (broadcastTo == Message.ClientType.NODE)
            this.nodes.forEach((destId, dest) -> send(destId, Message.ClientType.NODE, data));
        else
            this.clients.forEach((destId, dest) -> send(destId, Message.ClientType.CLIENT, data));

    }

    /*
     * Sends a message to a specific node with guarantee of delivery
     *
     * @param nodeId The node identifier
     *
     * @param data The message to be sent
     */
    // DO NOT CHANGE THIS METHODS SIGNATURE
    public void send(String receiverId, Message.ClientType receiverType, Message data) {

        new Thread(() -> {
            try {
                Message newData = deepCopy(data);
                int destPort = 0;
                InetAddress destAddress = null;
                if (receiverType == Message.ClientType.NODE) {

                    ProcessConfig node = this.nodes.get(receiverId);

                    if (node == null)
                        throw new HDSSException(ErrorMessage.NoSuchNode);

                    // If the message is not ACK, it will be resent
                    destAddress = InetAddress.getByName(node.getHostname());
                    destPort = node.getPort();

                } else {
                    ClientConfig client = this.clients.get(receiverId);
                    destAddress = InetAddress.getByName(client.getHostname());
                    destPort = client.getPort();
                }

                //se enviar para o cliente é suposto fazer esta linha?
                newData.setMessageId(messageCounter.getAndIncrement());
//                System.out.println("\nCreated message for " +receiverId+" with id "+newData.getMessageId()+"\n");

                int count = 1;
                int messageId = newData.getMessageId();
//                System.out.println("\nAFTER CREATE - message for " +
//                        destAddress +":"+destPort +
//                        " with id "+newData.getMessageId()+" AND VAR " + messageId +"\n");
                int sleepTime = BASE_SLEEP_TIME;

                String signature;
                if (this.config.getTestScenario() == TestScenario.UNAUTHORIZED_NODE) {
                    KeyPair keyPair = CryptoLib.generateKeyPair();
                    assert keyPair != null;
                    signature = CryptoLib.signData(newData, keyPair.getPrivate());
                } else {
                    signature = CryptoLib.signData(newData, this.config.getPrivateKey());
                }

//                System.out.println("\nAFTER SIGNED - message for " +
//                        destAddress +":"+destPort +
//                        " with id "+newData.getMessageId()+"\n");

                if (this.config.getTestScenario() == TestScenario.MALFORMED_NODE_SIGNATURE)
                    signature += "CORRUPTED";

                //the message 'data' can be casted to consensus message which has another message
                //like pre-prepared which has the input value that is the block that has all the original wrapped messages
                // TODO simplify, this is the original
                MessageSigWrapper messageSigWrapper = new MessageSigWrapper(newData, signature);
//                System.out.println("\nAFTER CREATED SigWrapper - message for " +
//                        destAddress +":"+destPort +
//                        " with id "+messageSigWrapper.getInternalMessage().getMessageId()+" AND VAR " + messageId+"\n");
                // Send message to local queue instead of using network if destination in self
                if (receiverType == Message.ClientType.NODE && receiverId.equals(this.config.getId())) {
                    this.localhostQueue.add(messageSigWrapper);

                    LOGGER.log(Level.INFO,
                            MessageFormat.format("{0} - Message {1} with ID {2} (locally) sent to {3}:{4} successfully",
                                    config.getId(), newData.getType(), messageId, destAddress, destPort));

                    return;
                }

                for (;;) {
                    LOGGER.log(Level.INFO, MessageFormat.format(
                            "{0} - Sending {1} message to {2}:{3} with message ID {4} - Attempt #{5}", config.getId(),
                            newData.getType(), destAddress, destPort, messageId, count++));

//                    System.out.println("\nBEFORE UNRELIABLE SEND - message for " +
//                            destAddress +":"+destPort +
//                            " with id "+messageSigWrapper.getInternalMessage().getMessageId()+"\n");
                    unreliableSend(destAddress, destPort, messageSigWrapper);

                    // Wait (using exponential back-off), then look for ACK
                    Thread.sleep(sleepTime);

                    // Receive method will set receivedAcks when sees corresponding ACK
                    if (receivedAcks.contains(messageId)) {
                        //System.out.println("Recebeu ACK de mensagem com ID: " + messageId);
                        break;
                    }

                    sleepTime <<= 1;
                }

                LOGGER.log(Level.INFO, MessageFormat.format("{0} - Message {1} sent to {2}:{3} successfully",
                        config.getId(), newData.getType(), destAddress, destPort));
            } catch (InterruptedException | UnknownHostException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /*
     * Sends a message to a specific node without guarantee of delivery
     * Mainly used to send ACKs, if they are lost, the original message will be
     * resent
     *
     * @param address The address of the destination node
     *
     * @param port The port of the destination node
     *
     * @param data The message to be sent
     */
    public void unreliableSend(InetAddress hostname, int port, MessageSigWrapper data) {
        new Thread(() -> {
            try {
                byte[] serializedData = SerenitySerializer.serialize(data);
                DatagramPacket packet = new DatagramPacket(serializedData, serializedData.length, hostname, port);
                socket.send(packet);
            } catch (Exception e) {
                e.printStackTrace();
                throw new HDSSException(ErrorMessage.SocketSendingError);
            }
        }).start();
    }

    /*
     * Receives a message from any node in the network (blocking)
     */
    public MessageSigWrapper receive() throws IOException, ClassNotFoundException {

        MessageSigWrapper messageSigWrapper;
        Message message;
        Boolean local = false;
        DatagramPacket response = null;

        if (this.localhostQueue.size() > 0) { //significa que o nó enviou para ele proprio

            messageSigWrapper = this.localhostQueue.poll();
            message = messageSigWrapper.getInternalMessage();
            local = true;
            this.receivedAcks.add(message.getMessageId());

        } else {

            byte[] buf = new byte[65535];
            response = new DatagramPacket(buf, buf.length);

            socket.receive(response);

            messageSigWrapper = SerenitySerializer.deserialize(response.getData(), response.getOffset(), response.getLength());
            message = messageSigWrapper.getInternalMessage();
            System.out.println("Received Message " + message.getType() + " from " + message.getSenderId() + " with ID " + message.getSenderId());
        }

        String senderId = message.getSenderId();
        int messageId = message.getMessageId();

        //verificar é mesmo cliente (comparar com a public key)
        //O mesmo para os nós
        if (message.getSenderType() == Message.ClientType.CLIENT) { //APPEND || ACK

            byte[] encodedPublicKey = Base64.getDecoder().decode(message.getSenderId());
            PublicKey publicKey = SerenitySerializer.deserialize(encodedPublicKey);

            boolean isVerified = CryptoLib.validateSignature(
                    message,
                    messageSigWrapper.getSignature(),
                    publicKey
            );

            System.out.println("Signature verification of original message from CLIENT: " + (isVerified ? "Successful" : "Failed"));

            if (!isVerified) {

                Message responseMessage = new Message(this.config.getId(), Type.IGNORE, Message.ClientType.NODE);
                responseMessage.setMessageId(messageId);

                String signature = CryptoLib.signData(responseMessage, this.config.getPrivateKey());

                // Unauthorized client, ignoring message...
                return new MessageSigWrapper(responseMessage, signature);

            } else {

                Instant messageCreatedAt = Instant.ofEpochMilli(message.getCreatedAt());
                Instant currentInstant = Instant.now();

                boolean isDetectReplayAttackTest = this.config.getTestScenario() == TestScenario.DETECT_REPLAY_ATTACK;
                if (ChronoUnit.MILLIS.between(messageCreatedAt, currentInstant) > MESSAGE_TIME_THRESHOLD
                        || isDetectReplayAttackTest) {

                    if (isDetectReplayAttackTest) {
                        System.out.println("DETECT_REPLAY_ATTACK test.");
                    }

                    Message responseMessage = new Message(this.config.getId(), Type.IGNORE, Message.ClientType.NODE);
                    responseMessage.setMessageId(messageId);

                    String signature = CryptoLib.signData(responseMessage, this.config.getPrivateKey());

                    // Old message, ignoring message...
                    return new MessageSigWrapper(responseMessage, signature);
                }

            }
        } else {

            Gson gson = new Gson();
            String serializedMessage = gson.toJson(message);

            if (message.getType() != Type.PRE_PREPARE)
                System.out.println("Received message: " + serializedMessage);
            else
                System.out.println("Received PRE-PREPARE (not printing because it can be too long)");

            System.out.println("SenderID: " + message.getSenderId() + " ID " + message.getMessageId());

            boolean isVerified = CryptoLib.validateSignature(
                    message,
                    messageSigWrapper.getSignature(),
                    this.config.getNodesPublicKey(senderId)
            );

            System.out.println("Signature verification message coming from node: " + (isVerified ? "Successful" : "Failed"));

            boolean isOriginalDataVerified = true;
            if (message.getType() == Type.PRE_PREPARE) {

                Block block = Block.fromJson(message.getMessage());
                System.out.println("\nBLOCK " + block.toString() + "\n");

                for (MessageSigWrapper txMessage : block.getPriorityTxQueue()) {

                    Message currentMessage = txMessage.getInternalMessage();

                    System.out.println("\nCURRENT MESSAGE WITH TX " + (new Gson().toJson(currentMessage)) + "\n");

                    isOriginalDataVerified = CryptoLib.validateSignature(
                            currentMessage,
                            txMessage.getSignature(),
                            this.config.getClientsPublicKey(currentMessage.getSenderId()));

                    System.out.println("Signature verification of TX original message inside block: " + (isOriginalDataVerified ? "Successful" : "Failed"));

                    if (!isOriginalDataVerified)
                        break;
                }
            }

            // If the messages between nodes are corrupted OR if the original message has been modified - ignore.
            if (!isVerified && !isOriginalDataVerified) {

                Message responseMessage = new Message(this.config.getId(), Type.IGNORE, Message.ClientType.NODE);
                responseMessage.setMessageId(messageId);

                String signature = CryptoLib.signData(responseMessage, this.config.getPrivateKey());

                // Unauthorized client, ignoring message...
                return new MessageSigWrapper(responseMessage, signature);

            } else {

                Instant messageCreatedAt = Instant.ofEpochMilli(message.getCreatedAt());
                Instant currentInstant = Instant.now();

                boolean isDetectReplayAttackTest = this.config.getTestScenario() == TestScenario.DETECT_REPLAY_ATTACK;

                if (ChronoUnit.MILLIS.between(messageCreatedAt, currentInstant) > MESSAGE_TIME_THRESHOLD
                        || isDetectReplayAttackTest) {

                    if (isDetectReplayAttackTest) {
                        System.out.println("DETECT_REPLAY_ATTACK test.");
                    }

                    Message responseMessage = new Message(this.config.getId(), Type.IGNORE, Message.ClientType.NODE);
                    responseMessage.setMessageId(messageId);

                    String signature = CryptoLib.signData(responseMessage, this.config.getPrivateKey());

                    // Old message, ignoring message...
                    return new MessageSigWrapper(responseMessage, signature);

                }
            }
        }

        // Handle ACKS, since it's possible to receive multiple acks from the same message
        if (message.getType() == Type.ACK) {
            receivedAcks.add(messageId);
            return messageSigWrapper;
        }

        // It's not an ACK -> Deserialize for the correct type
        //  if (!local) // TODO should verify message
        //      message = new Gson().fromJson(serialized, MessageSigWrapper.class);

        boolean isRepeated = false;
        if (message.getSenderType() == Message.ClientType.NODE)
            isRepeated = !this.receivedMessagesNodes.get(message.getSenderId()).add(messageId);

        Type originalType = message.getType();
        // Message already received (add returns false if already exists) => Discard
        if (isRepeated) {
            message.setType(Message.Type.IGNORE);
        }

        switch (message.getType()) {
            case IGNORE -> {
                if (!originalType.equals(Type.COMMIT))
                    return messageSigWrapper;
            }
            case PREPARE, COMMIT -> {
                if (message.getReplyTo() != null && message.getReplyTo().equals(this.config.getId())) {
                    this.receivedAcks.add(message.getReplyToMessageId());
                }
            }
            default -> {}
        }

        if (!local) {
            InetAddress address = InetAddress.getByName(response.getAddress().getHostAddress());
            int port = response.getPort();

            Message ack = new MessageBuilder(this.config.getId(), Message.Type.ACK, Message.ClientType.NODE)
                    .setMessageId(messageId)
                    .build();

            String signature = CryptoLib.signData(ack, this.config.getPrivateKey());
            System.out.println("\nSending ACK for message from node or user " + senderId + " with ID " + messageId + "\n");
            unreliableSend(address, port, new MessageSigWrapper(ack, signature));
        }

        return messageSigWrapper;
    }
}