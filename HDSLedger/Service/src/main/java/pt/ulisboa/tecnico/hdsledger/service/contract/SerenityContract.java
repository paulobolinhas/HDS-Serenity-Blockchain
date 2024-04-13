package pt.ulisboa.tecnico.hdsledger.service.contract;

public interface SerenityContract {

    int getBalance(String requesterID);

    void transfer(String senderID, String receiverID, int amount, int fee);
}
