package org.hyperledger.besu.plugin.data;

import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.TransactionType;

import java.util.List;

public interface BlockTransactionSelectionResult {

  String toTraceLog();

  void logSelectionStats();

  List<? extends TransactionReceipt> getReceipts();

  List<? extends Transaction> getTransactionsByType(TransactionType transactionType);

  List<? extends Transaction> getSelectedTransactions();
}
