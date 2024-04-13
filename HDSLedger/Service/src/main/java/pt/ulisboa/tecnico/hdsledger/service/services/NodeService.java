package pt.ulisboa.tecnico.hdsledger.service.services;

import com.google.gson.Gson;
import io.reactivex.rxjava3.schedulers.Schedulers;
import pt.ulisboa.tecnico.hdsledger.communication.*;
import pt.ulisboa.tecnico.hdsledger.communication.builder.MessageBuilder;
import pt.ulisboa.tecnico.hdsledger.crypto.CryptoLib;
import pt.ulisboa.tecnico.hdsledger.service.comparator.PriorityComparator;
import pt.ulisboa.tecnico.hdsledger.service.contract.SerenityContract;
import pt.ulisboa.tecnico.hdsledger.service.models.ConsensusInstance;
import pt.ulisboa.tecnico.hdsledger.service.models.MessageBucket;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.RoundChangeTracker;
import pt.ulisboa.tecnico.hdsledger.utilities.test.TestScenario;

import java.io.IOException;
import java.net.SocketException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class NodeService implements UDPService, SerenityContract {

    private static final CustomLogger LOGGER = new CustomLogger(NodeService.class.getName());
    // Nodes configurations
    private final ProcessConfig[] nodesConfig;

    // Current node is leader
    private final ProcessConfig config;
    // Leader configuration
    private ProcessConfig leaderConfig;

    // Link to communicate with nodes
    private final Link link;

    // Consensus instance -> Round -> List of prepare messages
    private final MessageBucket prepareMessages;
    // Consensus instance -> Round -> List of commit messages
    private final MessageBucket commitMessages;

    // Consensus instance -> Round -> List of RoundChange messages
    private final MessageBucket roundChangeMessages;

    // Store if already received pre-prepare for a given <consensus, round>
    private final Map<Integer, Map<Integer, Boolean>> receivedPrePrepare = new ConcurrentHashMap<>();
    // Consensus instance information per consensus instance
    private final Map<Integer, ConsensusInstance> consensunsMap = new ConcurrentHashMap<>();
    // Current consensus instance

    // Last decided consensus instance
    private final AtomicInteger lastDecidedConsensusInstance = new AtomicInteger(0);

    // Ledger (for now, just a list of strings)
    private ArrayList<Block> ledger = new ArrayList<>();
    private final int minimumFee = 20;
    private static final int MAX_TX_PER_BLOCK = 2;

    private PriorityBlockingQueue<MessageSigWrapper> priorityBufferTx = new PriorityBlockingQueue<>(MAX_TX_PER_BLOCK, new PriorityComparator()); //each message has a transaction

    public PriorityBlockingQueue<MessageSigWrapper> getPriorityBufferTx() {
        return priorityBufferTx;
    }

    //state variable
    private final AtomicInteger consensusInstanceID = new AtomicInteger(0);

    private boolean flagAmplification = false;

    private RoundChangeTracker timer;
    private static final long EXPIRATION_TIME = 5;

    private int testCounter = 1;

    public NodeService(Link link, ProcessConfig config,
                       ProcessConfig leaderConfig, ProcessConfig[] nodesConfig) {

        this.link = link;
        this.config = config;
        this.leaderConfig = leaderConfig;
        this.nodesConfig = nodesConfig;
        this.timer = new RoundChangeTracker(EXPIRATION_TIME);

        this.prepareMessages = new MessageBucket(this.nodesConfig.length);
        this.commitMessages = new MessageBucket(this.nodesConfig.length);
        this.roundChangeMessages = new MessageBucket(this.nodesConfig.length);

    }

    public int getConsensusInstanceID() {
        return this.consensusInstanceID.get();
    }

    public ConsensusInstance getConsensusInstance() {
        return this.consensunsMap.get(this.getConsensusInstanceID());
    }

    public int getCurrentRound() {
        return this.getConsensusInstance().getCurrentRound();
    }

    public int getPreparedRound() {
        return this.getConsensusInstance().getPreparedRound();
    }

    public String getPreparedValue() {
        return this.getConsensusInstance().getPreparedValue();
    }

    public String getInputValue() {
        return this.getConsensusInstance().getInputValue();
    }

    private void changeLeader(int newRound) {
        int newLeaderId = newRound % this.nodesConfig.length;

        ProcessConfig current = this.getNodeConfig(this.leaderConfig.getId()).get();
        this.leaderConfig.setLeader(false);
        current.setLeader(false);

        this.leaderConfig = Arrays.stream(this.nodesConfig).filter(nodeConfig -> nodeConfig.getId().equals(String.valueOf(newLeaderId))).findAny().get();

        this.leaderConfig.setLeader(true);
        Arrays.stream(this.nodesConfig).filter(ProcessConfig::isLeader).findFirst().get().setLeader(true);

    }

    private Optional<ProcessConfig> getNodeConfig(String id) {
        return Arrays.stream(this.nodesConfig)
                .filter(processConfig -> processConfig.getId().equals(id))
                .findFirst();
    }

    private Optional<Message.ErrorType> verifyTransaction(Message message) {
        System.out.println("====================================================================================");
        System.out.println("Verifying transaction.");

        Transaction transaction = message.deserializeTransaction();

        if (!transaction.getSenderID().equals(message.getSenderId())) {
            System.out.println("\nERROR: Sender of the message is not the same of the transaction");
            return Optional.of(Message.ErrorType.ERROR);
        }

        if (transaction.getFee() < this.minimumFee) {
            System.out.println("Fee of transaction is not enough - Current fee is " + this.minimumFee + " and provided is " + transaction.getFee());
            return Optional.of(Message.ErrorType.NOT_ENOUGH_FEE);
        }

        int senderBalance = this.config.getAccount(transaction.getSenderID()).getBalance();
        int totalAmount = transaction.getAmount() + transaction.getFee();
        if (senderBalance < totalAmount) {
            System.out.println("Balance of the sender is not enough - Current balance is " + senderBalance + " and total amount asked is " + totalAmount);
            return Optional.of(Message.ErrorType.NOT_ENOUGH_BALANCE);
        }
        return Optional.empty();
    }

    public synchronized void uponTransfer(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();

        if (this.link.isClientMessageRepeated(message)) {
            System.out.println("Receiving REPEATED append message " + message.getMessageId() +
                    " with the data " + (new Gson().toJson(message)) + " from CLIENT " + message.getSenderId());
            return;
        } else {
            System.out.println("Receiving append message " + message.getMessageId() +
                    " with the data " + (new Gson().toJson(message)) + " from CLIENT " + message.getSenderId());
        }

        Transaction transaction = message.deserializeTransaction();

        if (verifyTransaction(message).isPresent()) {

            Message.ErrorType errorType = verifyTransaction(message).get();

            //regarding the transaction validation a fee is paid by the message sender
            //this way we can prevent malicious clients from flooding the network

            this.config.getAccount(message.getSenderId()).subtractBalance(this.minimumFee);

            switch (errorType) {
                case ERROR -> {
                    return; //ignore because its a malicious error
                }
                case NOT_ENOUGH_BALANCE, NOT_ENOUGH_FEE -> {

                    Message errorMessage = new MessageBuilder(this.config.getId(), Message.Type.TRANSFER, Message.ClientType.NODE)
                            .setMessage("ERROR")
                            .setErrorType(errorType)
                            .setReplyTo(message.getSenderId())
                            .setReplyToMessageId(message.getReplyToMessageId())
                            .build();

                    this.link.send(message.getSenderId(), Message.ClientType.CLIENT, errorMessage);
                    return;
                }
            }
        }

        //regarding the transaction validation a fee paid by the message sender
        //this way we can prevent malicious clients from flooding the network
        this.config.getAccount(message.getSenderId()).subtractBalance(transaction.getFee());

        this.priorityBufferTx.add(messageSigWrapper);

        if (this.priorityBufferTx.size() >= MAX_TX_PER_BLOCK) {

            Block newBlock = new Block(priorityBufferTx);
            this.priorityBufferTx = new PriorityBlockingQueue<>(MAX_TX_PER_BLOCK, new PriorityComparator());

            Block blockWithHash = setPreviousBlockHash(newBlock);

            this.startConsensus(blockWithHash);

        } else {
            System.out.println("Added new transaction to buffer");
        }
    }

    /*
     * Start an instance of consensus for a value
     * Only the current leader will start a consensus instance
     * the remaining nodes only update values.
     *
     * @param inputValue Value to value agreed upon
     */
    public void startConsensus(Block block) {

        // Only start a consensus instance if the last one was decided
        // We need to be sure that the previous value has been decided
        if (lastDecidedConsensusInstance.get() < this.getConsensusInstanceID()) {
            System.out.println("\nThe current instance was not decided yet, ignoring the received one\n");
            return;
        }


        //Test Scenario of when a node takes too long so the others send all the commit
        //messages to him to catch up
//        if (this.config.getId().equals("1")) {
//            if (counter==1) { //dont ask me why but only with this, works
//                counter2++;
//                return;
//            }
//            System.out.println("A ESPERAR 10 SEGUNDOSSSS");
//            try {
//                Thread.sleep(10000);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }

        this.changeLeader(1);
        ConsensusInstance newConsensusInstance = new ConsensusInstance(block.toJson());
        this.consensusInstanceID.incrementAndGet();

        // if consensus already existed then return
        if (this.consensunsMap.put(this.getConsensusInstanceID(), newConsensusInstance) != null) {
            LOGGER.log(Level.INFO, MessageFormat.format("{0} - Node already started consensus for instance {1}",
                    config.getId(), this.getConsensusInstanceID()));
            return;
        }

        System.out.println("Input value now is Block with ID: " + this.getInputValue()); //returns block id


        //Create test of receiving an append message when the current instance is not decided
        //basically this if delays the current instance, the client sends two appends really fast
        //and the second is going to be ignored
//        try {
//            System.out.println("\nWaiting 2 seconds\n");
//            Thread.sleep(2000);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        // Leader broadcasts PRE-PREPARE message
        if (this.config.isLeader()) {

            LOGGER.log(Level.INFO, MessageFormat.format("{0} - Node is leader, sending PRE-PREPARE message", config.getId()));

            //if test scenario is LEADER_TIMEOUT it does not send anything to simulate crash
            if (this.config.getTestScenario() != TestScenario.LEADER_TIMEOUT) {

                System.out.println("--- Pre-Prepared message has round " + this.getConsensusInstance().getCurrentRound() + " ---");
                Message toBroadcast = new MessageBuilder(config.getId(), Message.Type.PRE_PREPARE, Message.ClientType.NODE)
                        .setConsensusInstanceID(this.getConsensusInstanceID())
                        .setCurrentRound(this.getConsensusInstance().getCurrentRound())
                        .setMessage(this.getInputValue())
                        .build();

                this.link.broadcast(toBroadcast, Message.ClientType.NODE);

            }
        } else { // if nodes receive this, then the leader did not propagate the PRE_PREPARE message
            LOGGER.log(Level.INFO,
                    MessageFormat.format("{0} - Node is not leader, waiting for PRE-PREPARE message", config.getId()));
        }

        this.timer.startTimerNode(this.getCurrentRound())
                .getExpirationObservable()
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .doOnNext(trackedRound -> {

                    System.out.println("Timer stopped");

                    if (this.config.getTestScenario() == TestScenario.LEADER_TIMEOUT && this.config.isLeader()) {

                    } else
                        this.uponTimerExpired();
                })
                .subscribe();
    }

    /*
     * creates a Round Change message with a new proposed round and adds the Quorum Prepared messages that the node received
     * if it has received quorum.
     * Broadcasts the message created
     */
    public void uponTimerExpired() {

        System.out.println("uponTimerExpired");

        //consensus instance current round only changes upon quorum messages
        ConsensusInstance consensusInstance = this.consensunsMap.get(this.getConsensusInstanceID());
        int newProposedRound = consensusInstance.getCurrentRound() + 1;

        System.out.println(" ============ New proposed round on uponTimerExpired: " + newProposedRound + " ============");
        RoundChangeMessage roundChangeMessage = null;
        Optional<String> prValue = this.prepareMessages.hasValidPrepareQuorum(this.getConsensusInstanceID(), this.getCurrentRound());

        if (prValue.isPresent()) {
            Map<String, MessageSigWrapper> prepareMessages = this.prepareMessages.getMessages(this.getConsensusInstanceID(), this.getCurrentRound());
            roundChangeMessage = new RoundChangeMessage(this.getConsensusInstanceID(), newProposedRound, this.getPreparedRound(), this.getPreparedValue(), prepareMessages);
        } else {
            roundChangeMessage = new RoundChangeMessage(this.getConsensusInstanceID(), newProposedRound);
        }

        Message toBroadcast = new MessageBuilder(this.config.getId(), Message.Type.ROUND_CHANGE, Message.ClientType.NODE)
                .setConsensusInstanceID(this.getConsensusInstanceID())
                .setCurrentRound(consensusInstance.getCurrentRound())
                .setMessage(roundChangeMessage.toJson())
                .build();

        System.out.println("Broadcasting roundChange proposing new round " + roundChangeMessage.getNewProposedRound() +
                " for the consensus instance ID " + roundChangeMessage.getConsensusInstanceID());

        this.link.broadcast(toBroadcast, Message.ClientType.NODE);
    }


    /*
     * Ignores messages that are not valid.
     *
     * Verifies if received f+1 Round Changes and calls the upon rule
     * Verifies if received Quorum Round Changes. If it did, justifies it and makes the round change.
     *
     * If it is the new leader, broadcasts Pre-Prepare message
     */
    public synchronized void uponRoundChange(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();

        RoundChangeMessage roundChangeMessage = message.deserializeRoundChangeMessage();
        int newProposedRound = roundChangeMessage.getNewProposedRound();

        if (this.lastDecidedConsensusInstance.get() >= message.getConsensusInstanceID()) {

            System.out.println("--- Last instance decided was " + this.lastDecidedConsensusInstance.get() + " but receiving message from " +
                    message.getConsensusInstanceID() + " SENDING QUORUM COMMITS ---");

            //when receives a late round change from Pj for an instance that has been decided by Pi (the current process), Pi sends
            //the quorum commits to Pj to it can catch up and decide too

            int f = Math.floorDiv(this.nodesConfig.length - 1, 3); // 1
            int quorumSize = Math.floorDiv(this.nodesConfig.length + f, 2) + 1; // 3

            Map<String, MessageSigWrapper> quorumCommits;
            int roundToget = 1;
            while (true) {

                //it can find only one commit message, for instance if it comes from a bizantine
                //so it searches until finds a quorum
                quorumCommits = this.commitMessages.getMessages(message.getConsensusInstanceID(), roundToget++);
                if (quorumCommits == null)
                    continue;

                if (quorumCommits.values().size() >= quorumSize)
                    break;

            }

            System.out.println("\n--- AMOUNT OF COMMITS " + quorumCommits.values().size() + " ---\n");


            Message toSend = new MessageBuilder(this.config.getId(), Message.Type.COMMIT, Message.ClientType.NODE)
                    .setConsensusInstanceID(this.getConsensusInstanceID())
                    .setCurrentRound(this.getConsensusInstance().getCurrentRound())
                    .setQuorumCommits(quorumCommits)
                    .build();
//                    .setMessage(message.getMessage())

            this.link.send(message.getSenderId(), Message.ClientType.NODE, toSend);

            return;
        }

        //if the proposed round already is equal or smaller to the current round, so the process already got quorum messages, ignoring
        if (newProposedRound <= this.getConsensusInstance().getCurrentRound()) {
            System.out.println("Receiving LATE roundChange - current round " + this.getConsensusInstance().getCurrentRound() +
                    " new ProposedRound " + newProposedRound + " - sending ACK");
            return;
        } else {
            System.out.println("Receiving round change from " + message.getSenderId() +
                    " with ID " + message.getMessageId() + " with Consensus instance ID " +
                    this.getConsensusInstanceID() + " with new proposed round " + newProposedRound);
        }

        this.roundChangeMessages.addMessage(messageSigWrapper);

        // upon f + 1 Round Changes
        Optional<String> amplificationValue = this.roundChangeMessages.hasValidRoundChangeFPlus1(message.getMessage(), message.getConsensusInstanceID(), message.getCurrentRound());

        if (amplificationValue.isPresent() && !flagAmplification) {
            System.out.println("\nENTERING AMPLIFICATION\n");
            flagAmplification = true;

            int minRound = getMinRound(this.roundChangeMessages);

            System.out.println("============ MIN ROUND " + minRound + "============");

            // Update the current round to the minimum round found
            this.getConsensusInstance().setCurrentRound(minRound);

            this.timer.startTimerNode(this.getCurrentRound())
                    .getExpirationObservable()
                    .observeOn(Schedulers.io())
                    .subscribeOn(Schedulers.io())
                    .doOnNext(trackedRound -> {
                        System.out.println("Timer expired for amplification " + trackedRound);
                        this.uponTimerExpired();
                    })
                    .subscribe();

            Message roundChangeMessageAmplified = new MessageBuilder(this.config.getId(), Message.Type.ROUND_CHANGE, Message.ClientType.NODE)
                    .setConsensusInstanceID(this.getConsensusInstanceID())
                    .setCurrentRound(this.getConsensusInstance().getCurrentRound())
                    .setMessage(roundChangeMessage.toJson())
                    .build();

            this.link.broadcast(roundChangeMessageAmplified, Message.ClientType.NODE);
            System.out.println("\nLEAVING AMPLIFICATION\n");
        }


        //if received Quorum Round Changes, call the justify (supposedly, only the leader does this)
        //upon QuorumRoundChange
        Optional<String> roundChangeValue = this.roundChangeMessages.hasValidRoundChangeQuorum(message.getMessage(), message.getConsensusInstanceID(), message.getCurrentRound());
        if (roundChangeValue.isEmpty()) { // TODO add this || flagAmplification
            System.out.println("----- Round change value is empty or flag amplification -----");
            flagAmplification = false;
            return;
        }

        System.out.println("----- Recebeu QUORUM ROUND CHANGES -----");

        Map<String, MessageSigWrapper> quorumRoundMessages = this.roundChangeMessages.getMessages(message.getConsensusInstanceID(), message.getCurrentRound());

        //needs to call it here to use the value next
        String[] tuple = highestPrepared(quorumRoundMessages);

        String receivedPr = tuple[0];
        String receivedPv = tuple[1];

        if (!this.justifyRoundChange(quorumRoundMessages, tuple)) {
            System.out.println("ROUND CHANGE IS NOT JUSTIFIED");
            return;
        } else {
            System.out.println("ROUND CHANGE IS JUSTIFIED");
        }

        String value;
//        System.out.println("\nreceivedPv: "+receivedPv+" receivedPr:"+receivedPr+" Comparations "+
//                receivedPv.equals("-1") + " and " + receivedPr.equals("-1") + "\n");

        if (!receivedPv.equals("-1") && !receivedPr.equals("-1")) {

            value = receivedPv;
            System.out.println("Message with highest PR " + receivedPr + " and PV " + receivedPv);

        } else {

            System.out.println("No message with pr and pv initialized");
//            System.out.println("\nAAAAAAAAAAAAAAAAAAAAAA\n");
            System.out.println("\ninput value: " + this.getInputValue() + "\n");
            value = this.getInputValue();

        }

        //Changing the round
        this.changeLeader(newProposedRound);
        System.out.println("New Leader has ID " + this.leaderConfig.getId() + " for round " + newProposedRound);

        this.getConsensusInstance().setCurrentRound(this.getCurrentRound() + 1);
        this.consensunsMap.put(this.getConsensusInstanceID(), this.getConsensusInstance());

        System.out.println("Round changed from " + (this.getConsensusInstance().getCurrentRound() - 1) +
                " to " + this.getConsensusInstance().getCurrentRound());

        if (this.config.isLeader()) {

            Message toBroadcast = new MessageBuilder(this.config.getId(), Message.Type.PRE_PREPARE, Message.ClientType.NODE)
                    .setConsensusInstanceID(this.getConsensusInstanceID())
                    .setCurrentRound(this.getConsensusInstance().getCurrentRound())
                    .setMessage(value)
                    .build();

            System.out.println("--- Node is leader, broadcasting pre-prepared after round change ---");
            this.link.broadcast(toBroadcast, Message.ClientType.NODE);
        }


    }

    private int getMinRound(MessageBucket fPlus1RoundChangeMessages) {
        // Initialize with the current round
        AtomicInteger minRound = new AtomicInteger(-1);

        // Find the minimum round number among received f+1 round change messages
        fPlus1RoundChangeMessages.getMessages(this.getConsensusInstanceID(), this.getCurrentRound()).values().forEach((messageSigWrapper) -> {
            Message message = messageSigWrapper.getInternalMessage();
            int rj = message.getCurrentRound();

            if (minRound.get() == -1)
                minRound.set(rj);

            if (rj > this.getCurrentRound() && rj >= minRound.get()) {
                System.out.println("========== Setting new min round: " + rj + " ==========");
                minRound.set(rj);
            }
        });

        return minRound.get();
    }

    /*
     * if the round is 1 return true
     *
     * otherwise checks if it received Quorum Round Change messages and calls justifyRoundChange
     */
    private boolean justifyPrePrepare() {

        if (this.getCurrentRound() == 1)
            return true;

        Optional<String> roundChangeValue = this.roundChangeMessages.hasValidRoundChangeQuorum(this.getInputValue(), this.getConsensusInstanceID(), this.getCurrentRound() - 1);
        if (roundChangeValue.isEmpty()) {
            return false;
        }

        Map<String, MessageSigWrapper> quorumRoundMessages = this.roundChangeMessages.getMessages(this.getConsensusInstanceID(), this.getCurrentRound() - 1);
        String[] tuple = highestPrepared(quorumRoundMessages);

        return justifyRoundChange(quorumRoundMessages, tuple);
    }

    /*
     * if no round change message has pr and pv initialized then returns true
     *
     * if received Quorum Prepare message compares the returned value with the tuple of the highest prepared called before
     */
    private boolean justifyRoundChange(Map<String, MessageSigWrapper> quorumRoundMessages, String[] tuple) {

        //∀ ROUND-CHANGE, λi ri, prj , pvj i ∈ Qrc : prj = ⊥ ∧ pvj = ⊥
        AtomicBoolean isPvAndPrInitialized = new AtomicBoolean(false);

        quorumRoundMessages.forEach((id, wrappedMessage) -> {
            RoundChangeMessage roundChangeMessage = wrappedMessage.getInternalMessage().deserializeRoundChangeMessage();
            isPvAndPrInitialized.set(roundChangeMessage.isPvInitialized() && roundChangeMessage.isPrInitialized());
        });

        if (!isPvAndPrInitialized.get())
            System.out.println("No message with pr and pv initialized");
        else
            System.out.println("There are messages with pr and pv initialized");

        //received a quorum of valid hPREPARE, λi, pr, pvi messages such that: (pr, pv) = HighestPrepared(Qrc)
        String receivedPr = tuple[0];
        String receivedPv = tuple[1];

        Optional<String> pv = this.prepareMessages.hasValidPrepareQuorum(this.getConsensusInstanceID(), Integer.parseInt(receivedPr));
        if (pv.isEmpty()) {
            return !isPvAndPrInitialized.get();
        }

        /* --- Verifiy signatures ---*/
        AtomicBoolean isVerified = new AtomicBoolean(true);

        quorumRoundMessages.forEach((id, wrappedMessage) -> {

            RoundChangeMessage roundChangeMessage = wrappedMessage.getInternalMessage().deserializeRoundChangeMessage();
            Map<String, MessageSigWrapper> preparedMessages = roundChangeMessage.getPreparedMessages();

            preparedMessages.forEach((nodeId, wrappedPreparedMessage) -> {

                Message preparedMessage = wrappedPreparedMessage.getInternalMessage();

                isVerified.set(CryptoLib.validateSignature(
                        preparedMessage,
                        wrappedPreparedMessage.getSignature(),
                        this.config.getNodesPublicKey(preparedMessage.getSenderId())
                ));

                if (!isVerified.get())
                    return;
            });

            if (!isVerified.get())
                return;
        });

        if (!isVerified.get())
            return false;

        return !isPvAndPrInitialized.get() || pv.get().equals(receivedPv);
    }

    /*
     * checks with round message has the highest PR
     */
    private String[] highestPrepared(Map<String, MessageSigWrapper> quorumRoundMessages) {

        AtomicInteger highestRound = new AtomicInteger(-1);
        AtomicReference<String> highestValue = new AtomicReference<>("-1");

        System.out.println("--- A obter as ROUND CHANGE messages da instancia " + this.getConsensusInstanceID() + " para a round " + this.getCurrentRound() + " ---");

        quorumRoundMessages.forEach((s, message) -> {
            RoundChangeMessage currentRoundChangeMessage = message.getInternalMessage().deserializeRoundChangeMessage();

            if (currentRoundChangeMessage.getPreparedRound() > highestRound.get() && currentRoundChangeMessage.getPreparedValue() != null) {
                highestRound.set(currentRoundChangeMessage.getPreparedRound());
                highestValue.set(currentRoundChangeMessage.getPreparedValue());
            }

        });


        String[] res = new String[2];

        res[0] = Integer.toString(highestRound.get());
        res[1] = highestValue.get();

        return res;
    }

    /*
     * Handle pre prepare messages and if the message
     * came from leader and is justified them broadcast prepare
     *
     * @param message Message to be handled
     */
    public void uponPrePrepare(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();

        if (this.lastDecidedConsensusInstance.get() >= message.getConsensusInstanceID()) {
            System.out.println("--- Last instance decided was " + this.lastDecidedConsensusInstance.get() + " but receiving message from " +
                    message.getConsensusInstanceID() + " IGNORING ---");
            return;
        }

        this.consensusInstanceID.set(message.getConsensusInstanceID());
        this.consensunsMap.putIfAbsent(this.getConsensusInstanceID(), new ConsensusInstance(message.getMessage()));

        if (message.getCurrentRound() < this.getCurrentRound()) {
            System.out.println("Receiving Pre-Prepared message for the round " + message.getCurrentRound() +
                    " but current round is" + this.getCurrentRound() +
                    " ignoring Pre-Prepare");
            return;
        }

        if (!this.justifyPrePrepare()) {
            System.out.println("PrePrepared IS NOT JUSTIFIED");
            return;
        } else {
            System.out.println("PrePrepared IS JUSTIFIED");
        }

        if (this.timer.isRunning()) {
            this.timer.cancelTracker();
            System.out.println("Reseting Timer...");
        }

        Message consensusMessage = new MessageBuilder(this.config.getId(), Message.Type.PREPARE, Message.ClientType.NODE)
                .setConsensusInstanceID(this.getConsensusInstanceID())
                .setCurrentRound(this.getCurrentRound())
                .build();

        this.timer.startTimerNode(this.getCurrentRound())
                .getExpirationObservable()
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .doOnNext(trackedRound -> {
                    System.out.println("Timer expired inside prePrepare for the round " + trackedRound);
                    this.uponTimerExpired();
                })
                .subscribe();

        String senderId = message.getSenderId();
        int senderMessageId = message.getMessageId();


        LOGGER.log(Level.INFO, MessageFormat.format(
                "{0} - Received PRE-PREPARE message from {1} Consensus Instance {2}, Round {3}",
                config.getId(), senderId, this.getConsensusInstanceID(), this.getCurrentRound()));


        // Within an instance of the algorithm, each upon rule is triggered at most once for any round r
        receivedPrePrepare.putIfAbsent(this.getConsensusInstanceID(), new ConcurrentHashMap<>());

        if (receivedPrePrepare.get(this.getConsensusInstanceID()).put(this.getCurrentRound(), true) != null) {
            LOGGER.log(Level.INFO, MessageFormat.format(
                    "{0} - Already received PRE-PREPARE message for Consensus Instance {1}, Round {2}, "
                            + "replying again to make sure it reaches the initial sender",
                    config.getId(), this.getConsensusInstanceID(), this.getCurrentRound()));
        }

        //not needed we can just use this.getInputValue()

        consensusMessage.setMessage(message.getMessage()); //block in json
        consensusMessage.setReplyTo(senderId);
        consensusMessage.setReplyToMessageId(senderMessageId);

        System.out.println("--- Broadcasting PREPARE ---");
        this.link.broadcast(consensusMessage, Message.ClientType.NODE);

    }

    /*
     * Handle prepare messages and if there is a valid quorum broadcast commit
     *
     * @param message Message to be handled
     */
    public synchronized void uponPrepare(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();

        String senderId = message.getSenderId();

        if (this.lastDecidedConsensusInstance.get() >= message.getConsensusInstanceID()) {
            System.out.println("--- Last instance decided was " + this.lastDecidedConsensusInstance.get() + " but receiving message from " +
                    message.getConsensusInstanceID() + " IGNORING ---");
            return;
        }

        System.out.println("Round comming in message " + message.getCurrentRound() + " and current round " + this.getCurrentRound());
        if (message.getCurrentRound() < this.getCurrentRound()) {
            System.out.println("Receiving PREPARE message for the round " + message.getCurrentRound() +
                    " but current round is " + this.getCurrentRound() +
                    " ignoring PREPARE");
            return;
        }

        LOGGER.log(Level.INFO, MessageFormat.format(
                "{0} - Received PREPARE message from {1}: Consensus Instance {2}, Round {3}",
                config.getId(), senderId, this.getConsensusInstanceID(), this.getCurrentRound()));

        Message consensusMessage = new MessageBuilder(config.getId(), Message.Type.COMMIT, Message.ClientType.NODE)
                .setConsensusInstanceID(this.getConsensusInstanceID())
                .setCurrentRound(this.getCurrentRound())
                .setReplyTo(senderId)
                .setReplyToMessageId(message.getMessageId())
                .build();

        prepareMessages.addMessage(messageSigWrapper);

        // Within an instance of the algorithm, each upon rule is triggered at most once
        // for any round r
        // Late prepare (consensus already ended for other nodes) only reply to him (as
        // an ACK)
        if (this.getConsensusInstance().getPreparedRound() >= this.getCurrentRound()) {
            LOGGER.log(Level.INFO, MessageFormat.format(
                    "{0} - Already received PREPARE message for Consensus Instance {1}, Round {2}, "
                            + "replying again to make sure it reaches the initial sender",
                    config.getId(), consensusInstanceID, this.getCurrentRound()));

            consensusMessage.setMessage(this.getPreparedValue());
            return;
        }

        // Find value with valid quorum
        Optional<String> preparedValue = prepareMessages.hasValidPrepareQuorum(this.getConsensusInstanceID(), this.getCurrentRound());
        if (preparedValue.isPresent()) {

            System.out.println("----- Recebeu QUORUM mensagens de PREPARE -----");

            this.getConsensusInstance().setPreparedValue(preparedValue.get());
            this.getConsensusInstance().setPreparedRound(this.getCurrentRound());
            System.out.println("----- PREPARED VALUE " + this.getPreparedValue() + " - PREPARED ROUND " + this.getPreparedRound() + " -----");

            this.consensunsMap.put(this.getConsensusInstanceID(), this.getConsensusInstance());
            consensusMessage.setMessage(preparedValue.get());

            try {
                ProcessConfig nodeConfig = Arrays.stream(this.nodesConfig)
                        .filter(c -> c.getId().equals("4"))
                        .findAny()
                        .get();

                // If node 4 has a test scenario of ROUND_CHANGE_AFTER_PREPARE,
                // each of the nodes will wait 6 seconds to execute.
                // This is used to simulate network delays or packet dropping.
                if (this.testCounter == 1 && nodeConfig.getTestScenario() == TestScenario.ROUND_CHANGE_AFTER_PREPARE) {
                    testCounter++;
                    System.out.println("PARAR 6s");
                    Thread.sleep(6000);
                    System.out.println("DEPOIS 6s");
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println("A fazer broadcast de COMMIT");
            this.link.broadcast(consensusMessage, Message.ClientType.NODE);

        }
    }

    /*
     * Handle commit messages and decide if there is a valid quorum
     *
     * @param message Message to be handled
     */
    public synchronized void uponCommit(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();

        //if last decided instance is smaller than the instance in the message
        if (message.getQuorumCommits().isPresent() && this.lastDecidedConsensusInstance.get() < message.getConsensusInstanceID()) {
            System.out.println("\nReceived Quorum Commits to Catch up, Adding to ledger...\n");

            Map<String, MessageSigWrapper> quorumCommits = message.getQuorumCommits().get();
            AtomicReference<String> block = new AtomicReference<>("");
            quorumCommits.forEach((id, messageWrp) -> block.set(messageWrp.getInternalMessage().getMessage()));
            this.addToLedger(block.get());
            return;
        }

        int messageCurrentRound = message.getCurrentRound();

        if (this.lastDecidedConsensusInstance.get() >= message.getConsensusInstanceID()) {
            System.out.println("--- Last instance decided was " + this.lastDecidedConsensusInstance.get() + " but receiving message from " +
                    message.getConsensusInstanceID() + " IGNORING ---");
            return;
        }

//        System.out.println("Round coming in message " + messageCurrentRound + " and current round " + this.getCurrentRound());
//        if (messageCurrentRound < this.getCurrentRound()) {
//            System.out.println("Receiving COMMIT message for the round " + message.getCurrentRound() +
//                    " but current round is" + this.getCurrentRound() +
//                    " ignoring COMMIT");
//            return;
//        }

        LOGGER.log(Level.INFO,
                MessageFormat.format("{0} - Received COMMIT message from {1}: Consensus Instance {2}, Round {3}",
                        config.getId(), message.getSenderId(), consensusInstanceID, this.getCurrentRound()));

        commitMessages.addMessage(messageSigWrapper);

        // Within an instance of the algorithm, each upon rule is triggered at most once
        // for any round r
        if (this.getConsensusInstance().getCommittedRound() >= this.getCurrentRound()) {
            LOGGER.log(Level.INFO,
                    MessageFormat.format(
                            "{0} - Already received COMMIT message for Consensus Instance {1}, Round {2}, ignoring",
                            config.getId(), consensusInstanceID, this.getCurrentRound()));
            return;
        }

        Optional<String> commitValue = commitMessages.hasValidCommitQuorum(this.getConsensusInstanceID(), this.getCurrentRound());
        if (commitValue.isPresent()) {
            System.out.println("----- Received QUORUM COMMIT messages " + commitValue.get() + " ----- ");

            String value = commitValue.get();
            addToLedger(value);
        }
    }

    private void addToLedger(String value) {

        this.getConsensusInstance().setCommittedRound(this.getCurrentRound());
        Block block = Block.fromJson(value);
        // Append value to the ledger (must be synchronized to be thread-safe)
        synchronized (this.ledger) {

            //------NOT SURE IF WE NEED THIS------
            // Increment size of ledger to accommodate current instance
//            ledger.ensureCapacity(this.getConsensusInstanceID());
//            while (ledger.size() < this.getConsensusInstanceID() - 1) {
//                ledger.add("");
//            }


            for (MessageSigWrapper txMessage : block.getPriorityTxQueue()) {

                Message message = txMessage.getInternalMessage();
                Transaction transaction = message.deserializeTransaction();

                if (verifyTransaction(message).isPresent()) {

                    Message.ErrorType errorType = verifyTransaction(message).get();
                    switch (errorType) {
                        case ERROR -> {
                            return; //ignore because its a malicious error
                        }
                        case NOT_ENOUGH_BALANCE, NOT_ENOUGH_FEE -> {

                            Message errorMessage = new MessageBuilder(this.config.getId(), Message.Type.TRANSFER, Message.ClientType.NODE)
                                    .setErrorType(errorType)
                                    .setReplyTo(message.getSenderId())
                                    .setReplyToMessageId(message.getReplyToMessageId())
                                    .build();

                            this.link.send(message.getSenderId(), Message.ClientType.CLIENT, errorMessage);

                        }
                    }
                } else {
                    this.transfer(transaction.getSenderID(), transaction.getReceiverID(), transaction.getAmount(), transaction.getFee());
                }


            }

            block.setBlockId(this.ledger.size());

            this.ledger.add(block);
            this.timer.cancelTracker();

            LOGGER.log(Level.INFO, MessageFormat.format("---------Block Decided - New Ledger ---------\n{0}", ledgerToString()));
        }

        lastDecidedConsensusInstance.getAndIncrement();

        LOGGER.log(Level.INFO,
                MessageFormat.format(
                        "{0} - Decided on Consensus Instance {1}, Round {2}, Successful? {3}",
                        config.getId(), consensusInstanceID, this.getCurrentRound(), true));

        //send message to each node that was in a transaction of the block

        for (MessageSigWrapper txMessage : block.getPriorityTxQueue()) {

            String originalSenderId = txMessage.getInternalMessage().getSenderId();

            System.out.println("====================================================================================");
            System.out.println("Preparing to dispatch TRANSFER to client.");
            System.out.println("====================================================================================");

            Message msgToClient = new MessageBuilder(this.config.getId(), Message.Type.TRANSFER, Message.ClientType.NODE)
                    .setConsensusInstanceID(this.getConsensusInstanceID())
                    .setCurrentRound(this.getCurrentRound())
                    .setReplyToMessageId(txMessage.getInternalMessage().getMessageId())
                    .setReplyTo(originalSenderId)
                    .setMessage(block.toJson())
                    .build();

            this.link.send(originalSenderId, Message.ClientType.CLIENT, msgToClient);
        }
    }

    @Override
    public void transfer(String senderID, String receiverID, int amount, int fee) {

        if (this.config.isLeader()) {
            System.out.println("Node is leader, add fee to its balance - fee: " + fee);
            this.config.getProcessAccount().addBalance(fee);
        }

        this.leaderConfig.getProcessAccount().addBalance(fee);
        this.config.transfer(senderID, receiverID, amount);

        System.out.println("Payed fee to Leader - current leader balance " + this.leaderConfig.getProcessAccount().getBalance());
        System.out.println("New accounts amount - Sender: " + this.getBalance(senderID));
        System.out.println("New accounts amount - Receiver: " + this.getBalance(receiverID));
    }

    private String ledgerToString() {

        StringBuilder res = new StringBuilder();

        for (Block block : this.ledger) {
            res.append(block.toString());
        }

        return res.toString();
    }

    @Override
    public void listen() {
        try {
            // Thread to listen on every request
            new Thread(() -> {
                try {

                    while (true) {

                        MessageSigWrapper messageSigWrapper = link.receive();
                        Message message = messageSigWrapper.getInternalMessage();

                        // Separate thread to handle each message
                        new Thread(() -> {

                            switch (message.getType()) {
                                case TRANSFER -> uponTransfer(messageSigWrapper);

                                case REQUEST_BALANCE -> uponRequestBalance(messageSigWrapper);

                                case ROUND_CHANGE -> uponRoundChange(messageSigWrapper);

                                case PRE_PREPARE -> uponPrePrepare(messageSigWrapper);

                                case PREPARE -> uponPrepare(messageSigWrapper);

                                case COMMIT -> uponCommit(messageSigWrapper);

                                case ACK ->
                                        LOGGER.log(Level.INFO, MessageFormat.format("{0} - Received ACK message from {1} replying to {2}",
                                                config.getId(), message.getSenderId(), message.getMessageId()));

                                case IGNORE -> LOGGER.log(Level.INFO,
                                        MessageFormat.format("{0} - Received IGNORE message from {1}",
                                                config.getId(), message.getSenderId()));

                                default -> LOGGER.log(Level.INFO,
                                        MessageFormat.format("{0} - Received unknown message {1} from {2}",
                                                config.getId(), message.getType(), message.getSenderId()));
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

    private synchronized void uponRequestBalance(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();

        String requesterID = message.getSenderId();

        System.out.println("User " + requesterID + " Requesting his balance");

        int balance = getBalance(requesterID);

        Message balanceMessage = new MessageBuilder(this.config.getId(), Message.Type.REQUEST_BALANCE, Message.ClientType.NODE)
                .setReplyTo(message.getSenderId())
                .setReplyToMessageId(message.getMessageId())
                .setMessage(String.valueOf(balance))
                .build();

        System.out.println("Sending request balance message " +
                " with the data " + balanceMessage.getMessage() + " to CLIENT " + message.getSenderId());

        this.link.send(message.getSenderId(), Message.ClientType.CLIENT, balanceMessage);
    }

    @Override
    public int getBalance(String requesterID) {
        return this.config.getAccount(requesterID).getBalance();
    }

    public ArrayList<Block> getLedger(){
        return this.ledger;
    }

    /**
     * Sets the previous block's hash.
     *
     * @param block [Block]
     * @return [Block] Either the un-modified block if it is the only one present on the ledger, or the [Block] with
     * the previous [Block]'s hash.
     */
    public Block setPreviousBlockHash(Block block) {
        // Set the previous block's hash into the new block

        /*
         * If we have at least 2 blocks we can validate the N-1 block so that we can guarantee the integrity of N
         * 1 2 [3]
         */
        if (this.ledger.size() > 1) {
            Block n1Block = ledger.get(this.ledger.size() - 1); // Last validated block

            // Now we need the N-2 block
            Block n2Block = ledger.get(this.ledger.size() - 2); // N-1 block

            boolean isPreviousBlocksHashValid = CryptoLib.validateHash(n2Block, n1Block.getPreviousBlockHash());

            if (isPreviousBlocksHashValid) { // We can trust the previous' blocks integrity
                String n1Hash = CryptoLib.hashData(n1Block);

                System.out.println("\n====================================================================================");
                System.out.println("Previous hash validated successfully.");
                System.out.printf("Setting previous hash on block %s%n", block.getBlockId());
                System.out.println("====================================================================================\n");

                block.setPreviousBlockHash(n1Hash);
            } else { // TODO what should we do here?
                System.out.println("====================================================================================");
                System.out.printf("Previous hash on block %s was not validated successfully.%n", n2Block.getBlockId());
                System.out.println("====================================================================================");
            }

            return block;
        } else if (this.ledger.size() == 1) {
            System.out.println("\n====================================================================================");
            System.out.printf("Setting first hash for block %s%n", block.getBlockId());
            System.out.println("====================================================================================\n");
            Block firstBlock = ledger.get(0);
            String firstHash = CryptoLib.hashData(firstBlock);

            block.setPreviousBlockHash(firstHash);
        } else {
            System.out.println("====================================================================================");
            System.out.println("First block - not adding previous block's hash.");
            System.out.println("====================================================================================");
        }

        return block;
    }

    public void closeLinkSocket() throws SocketException {
        this.link.closeSocket();
    }
}
