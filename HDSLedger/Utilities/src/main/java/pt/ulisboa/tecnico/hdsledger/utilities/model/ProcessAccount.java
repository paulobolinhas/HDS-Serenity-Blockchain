package pt.ulisboa.tecnico.hdsledger.utilities.model;

import java.security.PublicKey;

public class ProcessAccount {

    private int balance;
    private String id;
    private PublicKey publicKey;

    public ProcessAccount(String id, PublicKey publicKey) {
        this.id = id;
        this.publicKey = publicKey;
        this.balance = 0;
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

    public void subtractBalance(int amount) { this.balance -= amount; }

    public PublicKey getPublicKey() {
        return publicKey;
    }


}