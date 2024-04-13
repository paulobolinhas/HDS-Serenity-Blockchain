package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;
import pt.ulisboa.tecnico.hdsledger.utilities.RoundChangeTracker;

import java.util.ArrayList;
import java.util.Map;

public class RoundChangeMessage {

    private int consensusInstanceID;

    //If the algorithm has not made sufficient progress for a process
    //pi to decide during some round ri
    //then the timer will eventually expire
    private int newProposedRound;



    //round at which the process has prepared
    private int pr;



    //value that the process has prepared
    private String pv;

    private Map<String, MessageSigWrapper> preparedMessages;



    public RoundChangeMessage(int consensusInstanceID, int newProposedRound, int pr, String pv, Map<String, MessageSigWrapper> preparedMessages) {
        initializeContent(consensusInstanceID, newProposedRound, pr, pv, preparedMessages);
    }

    public RoundChangeMessage(int consensusInstanceID, int newProposedRound) {
        initializeContent(consensusInstanceID, newProposedRound, -1, null, null);
    }

    private void initializeContent(int consensusInstanceID, int newProposedRound,  int pr, String pv, Map<String, MessageSigWrapper> preparedMessages) {
        this.consensusInstanceID = consensusInstanceID;
        this.newProposedRound = newProposedRound;
        this.pr = pr;
        this.pv = pv;
        this.preparedMessages = preparedMessages;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public int getConsensusInstanceID() {
        return consensusInstanceID;
    }

    public int getNewProposedRound() {
        return this.newProposedRound;
    }

    public boolean isPrInitialized() {
        return this.pr != -1;
    }

    public boolean isPvInitialized() {
        return this.pv != null;
    }
    public int getPreparedRound() {
        return this.pr;
    }

    public String getPreparedValue() {
        return this.pv;
    }

    public Map<String, MessageSigWrapper> getPreparedMessages() {
        return preparedMessages;
    }
}
