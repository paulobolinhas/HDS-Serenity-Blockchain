package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;

import java.io.Serializable;
import java.util.concurrent.PriorityBlockingQueue;

public class Block implements Serializable {

    private int blockId;
    private final PriorityBlockingQueue<MessageSigWrapper> priorityTxQueue; //each message has a transaction

    private String previousBlockHash; //maybe change the type

    public Block(PriorityBlockingQueue<MessageSigWrapper> fifoTransactionsList) {
        this.priorityTxQueue = fifoTransactionsList;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Block fromJson(String strTransaction) {
        return new Gson().fromJson(strTransaction, Block.class);
    }


    public int getBlockId() {
        return blockId;
    }

    public PriorityBlockingQueue<MessageSigWrapper> getPriorityTxQueue() {
        return priorityTxQueue;
    }

    public void setBlockId(int blockId) {
        this.blockId = blockId;
    }

    public void setPreviousBlockHash(String previousBlockHash) {
        this.previousBlockHash = previousBlockHash;
    }

    public String getPreviousBlockHash() {
        return this.previousBlockHash;
    }
    public String toString() {
        StringBuilder res = new StringBuilder();
        int position = 1;


        res.append("------ Block ").append(this.getBlockId()).append(" ------\n");

        for (MessageSigWrapper txMessage : this.getPriorityTxQueue()) {
            Transaction transaction = txMessage.getInternalMessage().deserializeTransaction();

            res.append("--- Transaction ").append(position).append(" ---\n");
            res.append(transaction.toString()).append("\n");
            position++;
        }
        return res.toString();
    }

}
