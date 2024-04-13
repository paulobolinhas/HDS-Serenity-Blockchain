package pt.ulisboa.tecnico.hdsledger.communication;

import java.io.Serializable;

public class MessageSigWrapper implements Serializable, Comparable<MessageSigWrapper> {

    private String signature = "";
    private Message internalMessage;

    public MessageSigWrapper(Message internalMessage, String signature) {
        this.internalMessage = internalMessage;
        this.signature = signature;
    }

    public String getSignature() {
        return this.signature;
    }


    public Message getInternalMessage() {
        return this.internalMessage;
    }

    // This is irrelevant (but required) because we're using a custom comparator [PriorityComparator] which proceeds the class comparator.
    @Override
    public int compareTo(MessageSigWrapper o) {
        return 0;
    }
}
