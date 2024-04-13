package pt.ulisboa.tecnico.hdsledger.Client;


import java.security.PublicKey;

// TODO Contract to be shared between the gateway and the client, could be on a separate module
// TODO For stage one: <append, string>
public interface ClientContract {

    void transfer(
            PublicKey source,
            PublicKey destination,
            String senderID,
            String receiverID,
            int amount,
            int fee
    ) throws InterruptedException;

    void requestBalance(PublicKey publicKey) throws InterruptedException;
}
