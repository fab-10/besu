package org.hyperledger.besu.ethereum.eth.transactions;

import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;

import java.util.Optional;

public enum TransactionAddedStatus {
  ALREADY_KNOWN(TransactionInvalidReason.TRANSACTION_ALREADY_KNOWN),
  REJECTED_UNDERPRICED_REPLACEMENT(TransactionInvalidReason.TRANSACTION_REPLACEMENT_UNDERPRICED),
  NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER(),
  LOWER_NONCE_INVALID_TRANSACTION_KNOWN(),
  POSTPONED(),
  ADDED();

  private final Optional<TransactionInvalidReason> invalidReason;

  TransactionAddedStatus() {
    this.invalidReason = Optional.empty();
  }

  TransactionAddedStatus(final TransactionInvalidReason invalidReason) {
    this.invalidReason = Optional.of(invalidReason);
  }

  public Optional<TransactionInvalidReason> getInvalidReason() {
    return invalidReason;
  }
}
