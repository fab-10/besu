package org.hyperledger.besu.ethereum.mainnet;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionFilter;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.evm.account.Account;

import java.util.Optional;

public interface TransactionValidator {

  /**
   * Asserts whether a transaction is valid.
   *
   * @param transaction the transaction to validate
   * @param baseFee optional baseFee
   * @param transactionValidationParams Validation parameters that will be used
   * @return the result of the validation, in case of invalid transaction the invalid reason is present
   */
  ValidationResult<TransactionInvalidReason> validate(
      Transaction transaction,
      Optional<Wei> baseFee,
      TransactionValidationParams transactionValidationParams);

  /**
   * Asserts whether a transaction is valid for the sender account's current state.
   *
   * <p>Note: {@code validate} should be called before getting the sender {@link Account} used in
   * this method to ensure that a sender can be extracted from the {@link Transaction}.
   *
   * @param transaction the transaction to validate
   * @param sender the account of the sender of the transaction
   * @param validationParams to customize the validation according to different scenarios, like processing block, adding to the txpool, etc...
   * @return the result of the validation, in case of invalid transaction the invalid reason is present
   */
  ValidationResult<TransactionInvalidReason> validateForSender(
      Transaction transaction, Account sender, TransactionValidationParams validationParams);

//  void setTransactionFilter(TransactionFilter transactionFilter);
}
