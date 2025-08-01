/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evmtool.benchmarks;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.precompile.AbstractAltBnPrecompiledContract;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

import org.apache.tuweni.bytes.Bytes;

/** Benchmark AltBN128 add, mul, and pairings */
public class AltBN128Benchmark extends BenchmarkExecutor {

  /**
   * The constructor. Use default math based warmup and interations.
   *
   * @param output where to write the stats.
   * @param benchmarkConfig benchmark configurations.
   */
  public AltBN128Benchmark(final PrintStream output, final BenchmarkConfig benchmarkConfig) {
    super(MATH_WARMUP, MATH_ITERATIONS, output, benchmarkConfig);
  }

  @Override
  public void runBenchmark(final Boolean attemptNative, final String fork) {

    EvmSpecVersion forkVersion = EvmSpecVersion.fromName(fork);

    if (attemptNative != null
        && (!attemptNative || !AbstractAltBnPrecompiledContract.maybeEnableNative())) {
      AbstractAltBnPrecompiledContract.disableNative();
    }
    output.println(
        AbstractAltBnPrecompiledContract.isNative() ? "Native AltBN128" : "Java AltBN128");

    benchmarkAdd(forkVersion);
    benchmarkMul(forkVersion);
    benchmarkPairings(forkVersion);
  }

  private void benchmarkAdd(final EvmSpecVersion forkVersion) {
    final SequencedMap<String, Bytes> testCases = new LinkedHashMap<>();
    testCases.put(
        "EcAdd",
        Bytes.fromHexString(
            "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "01e0559bacb160664764a357af8a9fe70baa9258e0b959273ffc5718c6d4cc7c"
                + "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "2e83f8d734803fc370eba25ed1f6b8768bd6d83887b87165fc2434fe11a830cb"));
    testCases.put(
        "EcAddMarius",
        Bytes.fromHexString(
            "1581d9d4d7eb0e3cdc75739f7098479097d579573f23e70b07cbd40a"
                + "5d97bbc118da69b1d2cb7b89fdb3bba2524bf135453ce828faea190d8f4d48d572cbe3fe20"
                + "34b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f"
                + "5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d460"));
    testCases.put(
        "EcAddAmez1",
        Bytes.fromHexString(
            "1f69350cfea1bdf51ecec3a0bdf57732411418502904eb86c10901af310b9ca0291547261fc8bbcf961534f3f3d282d4ab3bfa0b9f3b5ecb3e575d42fd13e25c"
                + "2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d4"));
    testCases.put(
        "EcAddAmez2",
        Bytes.fromHexString(
            "03adc0948243eac87e40fd1d4a3b5693c651abd30a88b93f3dbe3f571791c4120e9f7fa4c9ae503399c7a4f0ee0e91961b759d94e49c8ea0d04b448dbc6f3d16"
                + "2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d4"));
    testCases.put(
        "EcAddAmez3",
        Bytes.fromHexString(
            "012b7638324563dc328b870d414807ed27426354bc83f22c42690f717ae7137e19dffc74cd0183d631cb39f5f58d2846744119913519373ebb95f2654a989390"
                + "2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d4"));

    PrecompiledContract contract =
        EvmSpec.evmSpec(forkVersion).getPrecompileContractRegistry().get(Address.ALTBN128_ADD);

    precompile(testCases, contract, forkVersion);
  }

  private void benchmarkMul(final EvmSpecVersion forkVersion) {
    final SequencedMap<String, Bytes> testCases = new LinkedHashMap<>();
    testCases.put(
        "mul1",
        Bytes.fromHexString(
            "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "01e0559bacb160664764a357af8a9fe70baa9258e0b959273ffc5718c6d4cc7c"
                + "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "2e83f8d734803fc370eba25ed1f6b8768bd6d83887b87165fc2434fe11a830cb"));
    testCases.put(
        "mul2",
        Bytes.fromHexString(
            "0x00000000000000000000000000000000000000000000000000000000000000"
                + "0130644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd"
                + "45ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));

    PrecompiledContract contract =
        EvmSpec.evmSpec(forkVersion).getPrecompileContractRegistry().get(Address.ALTBN128_MUL);

    precompile(testCases, contract, forkVersion);
  }

  private void benchmarkPairings(final EvmSpecVersion forkVersion) {
    final SequencedMap<String, Bytes> testCases = new LinkedHashMap<>();
    testCases.put(
        "2 pairings",
        Bytes.fromHexString(
            "0x0fc6ebd1758207e311a99674dc77d28128643c057fb9ca2c92b4205b6bf57ed2"
                + "1e50042f97b7a1f2768fa15f6683eca9ee7fa8ee655d94246ab85fb1da3f0b90"
                + "198e9393920d483a7260bfb731fb5d25f1aa493335a9e71297e485b7aef312c2"
                + "1800deef121f1e76426a00665e5c4479674322d4f75edadd46debd5cd992f6ed"
                + "090689d0585ff075ec9e99ad690c3395bc4b313370b38ef355acdadcd122975b"
                + "12c85ea5db8c6deb4aab71808dcb408fe3d1e7690c43d37b4ce6cc0166fa7daa"));
    testCases.put(
        "4 pairings",
        Bytes.fromHexString(
            "0x2b101be01b2f064cba109e065dc0b5e5bf6b64ed4054b82af3a7e6e34c1e2005"
                + "1a4d9ceecf9115a98efd147c4abb2684102d3e925938989153b9ff330523cdb4"
                + "08d554bf59102bbb961ba81107ec71785ef9ce6638e5332b6c1a58b87447d181"
                + "01cf7cc93bfbf7b2c5f04a3bc9cb8b72bbcf2defcabdceb09860c493bdf1588d"
                + "02cb2a424885c9e412b94c40905b359e3043275cd29f5b557f008cd0a3e0c0dc"
                + "204e5d81d86c561f9344ad5f122a625f259996b065b80cbbe74a9ad97b6d7cc2"
                + "07402fdc3bc28a434909f24695adea3e9418d9857efc8c71f67a470a17f3cf12"
                + "255dbc3a8b5c2c1a7a3f8c59e2f5b6e04bc4d7b7bb82fcbe18b2294305c8473b"
                + "19156e854972d656d1020003e5781972d84081309cdf71baacf6c6e29272f5ff"
                + "2acded377df8902b7a75de6c0f53c161f3a2ff3f374470b78d5b3c4d826d84d5"
                + "1731ef3b84913296c30a649461b2ca35e3fcc2e3031ea2386d32f885ff096559"
                + "0919e7685f6ea605db14f311dede6e83f21937f05cfc53ac1dbe45891c47bf2a"));
    testCases.put(
        "6 pairings",
        Bytes.fromHexString(
            "0x1a3fabea802788c8aa88741c6a68f271b221eb75838bb1079381f3f1ae414f40"
                + "126308d6cdb6b7efceb1ec0016b99cf7a1e5780f5a9a775d43bc7f2b6fd510e2"
                + "11b35cf2c85531eab64b96eb2eef487e0eb60fb9207fe4763e7f6e02dcead646"
                + "2cbea52f3417b398aed9e355ed16934a81b72d2646e3bf90dbc2dcba294b631d"
                + "2c6518cd26310e541a799357d1ae8bc477b162f2040407b965ecd777e26d31f7"
                + "125170b5860fb8f8da2c43e00ea4a83bcc1a974e47e59fcd657851d2b0dd1655"
                + "130a2183533392b5fd031857eb4c199a19382f39fcb666d6133b3a6e5784d6a5"
                + "2cca76f2bc625d2e61a41b5f382eadf1df1756dd392f639c3d9f3513099e63f9"
                + "07ecba8131b3fb354272c86d01577e228c5bd5fb6404bbaf106d7f4858dc2996"
                + "1c5d49a9ae291a2a2213da57a76653391fa1fc0fa7c534afa124ad71b7fdd719"
                + "10f1a73f94a8f077f478d069d7cf1c49444f64cd20ed75d4f6de3d8986147cf8"
                + "0d5816f2f116c5cc0be7dfc4c0b4c592204864acb70ad5f789013389a0092ce4"
                + "2650b89e5540eea1375b27dfd9081a0622e03352e5c6a7593df72e2113328e64"
                + "21991b3e5100845cd9b8f0fa16c7fe5f40152e702e61f4cdf0d98e7f213b1a47"
                + "10520008be7609bdb92145596ac6bf37da0269f7460e04e8e4701c3afbae0e52"
                + "0664e736b2af7bf9125f69fe5c3706cd893cd769b1dae8a6e3d639e2d76e66e2"
                + "1cacce8776f5ada6b35036f9343faab26c91b9aea83d3cb59cf5628ffe18ab1b"
                + "03b48ca7e6d84fca619aaf81745fbf9c30e5a78ed4766cc62b0f12aea5044f56"));

    final PrecompiledContract contract =
        EvmSpec.evmSpec(forkVersion).getPrecompileContractRegistry().get(Address.ALTBN128_PAIRING);

    precompile(testCases, contract, forkVersion);
  }

  @Override
  public boolean isPrecompile() {
    return true;
  }
}
