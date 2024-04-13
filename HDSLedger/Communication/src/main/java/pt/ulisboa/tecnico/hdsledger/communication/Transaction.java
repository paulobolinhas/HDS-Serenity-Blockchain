package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;

public class Transaction {

    private String encodedSenderPublicKey;

    private String encodedReceiverPublicKey;

    private String commonSenderIdentifier;

    public String commonReceiverIdentifier;

    private int amount;

    private int fee;

    public Transaction(String encodedSenderPublicKey,
                       String encodedReceiverPublicKey,
                       String commonSenderIdentifier,
                       String commonReceiverIdentifier,
                       int amount,
                       int fee
    ) {
        this.encodedSenderPublicKey = encodedSenderPublicKey;
        this.encodedReceiverPublicKey = encodedReceiverPublicKey;
        this.commonReceiverIdentifier = commonReceiverIdentifier;
        this.commonSenderIdentifier = commonSenderIdentifier;
        this.amount = amount;
        this.fee = fee;
    }

    public String getSenderID() {
        return encodedSenderPublicKey;
    }

    public String getReceiverID() {
        return encodedReceiverPublicKey;
    }

    public int getAmount() {
        return amount;
    }

    @Override
    public String toString() {
        return "Sender ID: " + this.commonSenderIdentifier + "\nReceiverID: " + commonReceiverIdentifier + "\nAmount: " + amount + "\nFee: " + fee;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Transaction fromJson(String strTransaction) {
        return new Gson().fromJson(strTransaction, Transaction.class);
    }


    public int getFee() {
        return fee;
    }
}
