package pt.ulisboa.tecnico.hdsledger.service.comparator;

import pt.ulisboa.tecnico.hdsledger.communication.MessageSigWrapper;
import pt.ulisboa.tecnico.hdsledger.communication.Transaction;

import java.util.Comparator;

public class PriorityComparator implements Comparator<MessageSigWrapper> {
    @Override
    public int compare(MessageSigWrapper t1, MessageSigWrapper t2) {
        Transaction firstTransaction = t1.getInternalMessage().deserializeTransaction();
        Transaction secondTransaction = t2.getInternalMessage().deserializeTransaction();

        return Integer.compare(secondTransaction.getFee(), firstTransaction.getFee());
    }
}