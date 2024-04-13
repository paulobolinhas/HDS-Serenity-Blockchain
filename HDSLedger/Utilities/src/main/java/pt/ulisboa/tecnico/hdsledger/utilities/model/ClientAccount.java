package pt.ulisboa.tecnico.hdsledger.utilities.model;

import java.security.PublicKey;

// TODO remove this from Utilities
public class ClientAccount {

    private int balance;
    private String id;
    private PublicKey publicKey;

    public ClientAccount(String id, PublicKey publicKey) {
        this.id = id;
        this.publicKey = publicKey;
        this.balance = 200;
    }

    public int getBalance() {
        return balance;
    }

    public void addBalance(int amount) {
        this.balance += amount;
    }

    public void setBalance(int newBalance) {
        this.balance = newBalance;
    }

    public void subtractBalance(int amount) {
        if (this.balance < amount) {
            this.balance = 0;
        }
        this.balance -= amount;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }


}