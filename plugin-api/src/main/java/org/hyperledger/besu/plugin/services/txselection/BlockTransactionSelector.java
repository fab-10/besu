package org.hyperledger.besu.plugin.services.txselection;

import org.hyperledger.besu.plugin.data.BlockTransactionSelectionResult;

public interface BlockTransactionSelector {

  BlockTransactionSelectionResult selectTransactionsForBlock();
}
