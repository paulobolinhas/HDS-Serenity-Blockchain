package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;
import pt.ulisboa.tecnico.hdsledger.communication.builder.MessageBuilder;
import pt.ulisboa.tecnico.hdsledger.utilities.SerenitySerializer;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public class Message implements Serializable  {


    private String senderId;

    private int messageId;

    private Type type;

    private ErrorType errorType;


    private long createdAt;

    private ClientType senderType;

    private int consensusInstanceID;
    private int currentRound;

    private String replyTo;

    private int replyToMessageId;

    private String message;

    private Map<String, MessageSigWrapper> quorumCommitMessages;



    public enum Type {
        REQUEST_BALANCE, TRANSFER, //requests
        PRE_PREPARE, PREPARE, COMMIT, ACK, ROUND_CHANGE, IGNORE, //consensus messages
    }

    public enum ErrorType {
        NOT_ENOUGH_BALANCE, NOT_ENOUGH_FEE, ERROR; //Errors
    }

    public enum ClientType {
        CLIENT, NODE
    }

    public Message(String senderId, Type type, ClientType senderType) {
        super();
        this.senderId = senderId;
        this.type = type;
        this.senderType = senderType;
        this.createdAt = Instant.now().toEpochMilli();
        this.errorType = null;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(ErrorType errorType) {
        this.errorType = errorType;
    }

    public RoundChangeMessage deserializeRoundChangeMessage() {
        return new Gson().fromJson(this.message, RoundChangeMessage.class);
    }

    public Transaction deserializeTransaction() {
        return new Gson().fromJson(this.message, Transaction.class);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public int getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(int replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public int getConsensusInstanceID() {
        return consensusInstanceID;
    }

    public void setConsensusInstanceID(int consensusInstance) {
        this.consensusInstanceID = consensusInstance;
    }

    public String getSenderId() {
        return senderId;
    }

    public int getMessageId() {
        return messageId;
    }


    // TODO this is being modified after [Message] is created; which breaks the signature
    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setSenderType(ClientType SenderType) {
        this.senderType = SenderType;
    }

    public ClientType getSenderType() {
        return senderType;
    }

    public void setQuorumCommits(Map<String, MessageSigWrapper> quorumCommitMessages) {
        this.quorumCommitMessages = quorumCommitMessages;
    }

    public Optional<Map<String, MessageSigWrapper>> getQuorumCommits() {
        if (quorumCommitMessages == null)
            return Optional.empty();

        return Optional.of(quorumCommitMessages);
    }

}
