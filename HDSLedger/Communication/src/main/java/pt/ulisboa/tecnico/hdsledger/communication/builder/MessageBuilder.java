package pt.ulisboa.tecnico.hdsledger.communication.builder;

import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.MessageSigWrapper;

import java.util.Map;

public class MessageBuilder {
    private final Message instance;

    public MessageBuilder(String sender, Message.Type type, Message.ClientType senderType) {
        instance = new Message(sender, type, senderType);
    }

    public MessageBuilder setMessage(String message) {
        instance.setMessage(message);
        return this;
    }

    public MessageBuilder setErrorType(Message.ErrorType errorType) {
        instance.setErrorType(errorType);
        return this;
    }

    public MessageBuilder setConsensusInstanceID(int consensusInstance) {
        instance.setConsensusInstanceID(consensusInstance);
        return this;
    }

    public MessageBuilder setCurrentRound(int currentRound) {
        instance.setCurrentRound(currentRound);
        return this;
    }

    public MessageBuilder setReplyTo(String replyTo) {
        instance.setReplyTo(replyTo);
        return this;
    }

    public MessageBuilder setReplyToMessageId(int replyToMessageId) {
        instance.setReplyToMessageId(replyToMessageId);
        return this;
    }

    public MessageBuilder setMessageId(int messageId) {
        this.instance.setMessageId(messageId);
        return this;
    }

    public Message build() {
        return instance;
    }

    public MessageBuilder setQuorumCommits(Map<String, MessageSigWrapper> quorumCommitMessages) {
        this.instance.setQuorumCommits(quorumCommitMessages);
        return this;
    }


}
