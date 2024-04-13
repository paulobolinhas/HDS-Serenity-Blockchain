package pt.ulisboa.tecnico.hdsledger.service.models;

import pt.ulisboa.tecnico.hdsledger.communication.*;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MessageBucket {

    private static final CustomLogger LOGGER = new CustomLogger(MessageBucket.class.getName());
    // Quorum size
    private final int quorumSize;
    private final int f;
    // Instance -> Round -> Sender ID -> Consensus message
    private final Map<Integer, Map<Integer, Map<String, MessageSigWrapper>>> bucket = new ConcurrentHashMap<>();

    public MessageBucket(int nodeCount) {
        this.f = Math.floorDiv(nodeCount - 1, 3); // 1
        quorumSize = Math.floorDiv(nodeCount + this.f, 2) + 1; // 3
    }

    /*
     * Add a message to the bucket
     * 
     * @param consensusInstance
     * 
     * @param message
     */
    public void addMessage(MessageSigWrapper messageSigWrapper) {

        Message message = messageSigWrapper.getInternalMessage();
        int consensusInstance = message.getConsensusInstanceID();
        int round = message.getCurrentRound();

        bucket.putIfAbsent(consensusInstance, new ConcurrentHashMap<>());
        bucket.get(consensusInstance).putIfAbsent(round, new ConcurrentHashMap<>());
        bucket.get(consensusInstance).get(round).put(message.getSenderId(), messageSigWrapper);
    }

    public Optional<String> hasValidPrepareQuorum(int instance, int round) {
        // Create mapping of value to frequency
        HashMap<String, Integer> frequency = new HashMap<>();

        Map<Integer,Map<String, MessageSigWrapper>> rounds = bucket.getOrDefault(instance, null);
        if (rounds == null)
            return Optional.empty();

        Map<String, MessageSigWrapper> messages = rounds.getOrDefault(round, null);
        if (messages == null)
            return Optional.empty();

        messages.values().forEach(messageSigWrapper -> {
            Message message = messageSigWrapper.getInternalMessage();
            String block = message.getMessage();
            frequency.put(block, frequency.getOrDefault(block, 0) + 1);
        });

        // Only one value (if any, thus the optional) will have a frequency
        // greater than or equal to the quorum size
        return frequency.entrySet().stream()
                .filter((Map.Entry<String, Integer> entry) -> entry.getValue() >= quorumSize)
                .map(Map.Entry::getKey).findFirst();
    }

    public Optional<String> hasValidRoundChangeFPlus1(String inputValue, int instance, int round) {
        // Create mapping of value to frequency
        HashMap<String, Integer> frequency = new HashMap<>();
        bucket.get(instance).get(round).values().forEach((message) -> {
            frequency.put(inputValue, frequency.getOrDefault(inputValue, 0) + 1);
        });

        //for debugging purposes
//        bucket.get(instance).get(round).keySet().forEach((key) -> {
//            Message message = bucket.get(instance).get(round).get(key).getInternalMessage();
//            RoundChangeMessage roundChangeMessage = message.deserializeRoundChangeMessage();
//            System.out.println("Adding frequency with key " + key + ", frequency " + frequency.get(inputValue) +
//                    " and value " + inputValue);
//        });

        // Only one value (if any, thus the optional) will have a frequency
        // greater than or equal to f+1
        int requiredFrequency = this.f + 1; // f+1
        return frequency.entrySet().stream()
                .filter((Map.Entry<String, Integer> entry) -> entry.getValue() >= requiredFrequency)
                .map(Map.Entry::getKey).findFirst();
    }

    public Optional<String> hasValidRoundChangeQuorum(String inputValue, int instance, int round) {
        // Create mapping of value to frequency
        HashMap<String, Integer> frequency = new HashMap<>();
        bucket.get(instance).get(round).values().forEach((message) -> {
            frequency.put(inputValue, frequency.getOrDefault(inputValue, 0) + 1);
        });

        // Only one value (if any, thus the optional) will have a frequency
        // greater than or equal to the quorum size
        return frequency.entrySet().stream()
                .filter((Map.Entry<String, Integer> entry) -> entry.getValue() >= quorumSize)
                .map(Map.Entry::getKey).findFirst();
    }

    public Optional<String> hasValidCommitQuorum(int instance, int round) {
        // Create mapping of value to frequency
        HashMap<String, Integer> frequency = new HashMap<>();
        bucket.get(instance).get(round).values().forEach((message) -> {
            String block = message.getInternalMessage().getMessage();
            frequency.put(block, frequency.getOrDefault(block, 0) + 1);
        });

        // Only one value (if any, thus the optional) will have a frequency
        // greater than or equal to the quorum size
        return frequency.entrySet().stream().filter((Map.Entry<String, Integer> entry) -> entry.getValue() >= quorumSize).map(Map.Entry::getKey).findFirst();
    }

    public Map<String, MessageSigWrapper> getMessages(int instance, int round) {
        try {
            return bucket.get(instance).get(round);
        } catch (Exception e) {
            return null;
        }
    }
}