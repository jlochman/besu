/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.flat;

import org.hyperledger.besu.config.MarklarConfigOptions;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.Trace;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Gas;
import org.hyperledger.besu.ethereum.core.GasAndAccessedState;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.mainnet.MiningBeneficiaryCalculator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.util.MarklarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class RewardTraceGenerator {

  private static final String REWARD_LABEL = "reward";
  private static final String BLOCK_LABEL = "block";
  private static final String UNCLE_LABEL = "uncle";

  /**
   * Generates a stream of reward {@link Trace} from the passed {@link Block} data.
   *
   * @param protocolSchedule the {@link ProtocolSchedule} to use
   * @param block the current {@link Block} to use
   * @return a stream of generated reward traces {@link Trace}
   */
  public static Stream<Trace> generateFromBlock(
      final ProtocolSchedule protocolSchedule, final Block block) {

    final List<Trace> flatTraces = new ArrayList<>();

    final BlockHeader blockHeader = block.getHeader();
    final ProtocolSpec protocolSpec = protocolSchedule.getByBlockNumber(blockHeader.getNumber());
    final MiningBeneficiaryCalculator miningBeneficiaryCalculator =
        protocolSpec.getMiningBeneficiaryCalculator();

    final Wei blockReward = MarklarUtils.getBlockReward(blockHeader.getDifficulty());
    Wei feeReward = Wei.ZERO;
    // this calculation isn't exact, needs to be fixed
    for (Transaction tx : block.getBody().getTransactions()) {
      GasAndAccessedState state =
          protocolSpec.getGasCalculator().transactionIntrinsicGasCostAndAccessedState(tx);
      Gas gasUsed = state.getGas();
      Wei txFee = gasUsed.priceFor(tx.getGasPrice().orElse(Wei.ZERO));
      feeReward = feeReward.plus(txFee);
    }

    Wei treasuryReward =
        Wei.ZERO
            .plus(MarklarUtils.getPart(blockReward, MarklarConfigOptions.treasuryBlockRewardPart))
            .plus(MarklarUtils.getPart(feeReward, MarklarConfigOptions.treasuryBlockFeePart));

    Wei operatorReward =
        Wei.ZERO
            .plus(MarklarUtils.getPart(blockReward, MarklarConfigOptions.operatorBlockRewardPart))
            .plus(MarklarUtils.getPart(feeReward, MarklarConfigOptions.operatorBlockFeePart));

    Wei validatorReward =
        blockReward.plus(feeReward).subtract(treasuryReward).subtract(operatorReward);

    // treasury reward = Uncle Reward
    if (!treasuryReward.isZero()) {
      final Action.Builder treasuryActionBuilder =
          Action.builder()
              .author(MarklarConfigOptions.treasuryAddress)
              .rewardType(UNCLE_LABEL)
              .value(treasuryReward.toShortHexString());
      flatTraces.add(
          RewardTrace.builder()
              .actionBuilder(treasuryActionBuilder)
              .blockHash(block.getHash().toHexString())
              .blockNumber(blockHeader.getNumber())
              .type(REWARD_LABEL)
              .build());
    }

    // treasury reward = Emission Reward
    if (!operatorReward.isZero()) {
      final Action.Builder operatorActionBuilder =
          Action.builder()
              .author(MarklarConfigOptions.operatorAddress)
              .rewardType("external")
              .value(operatorReward.toShortHexString());
      flatTraces.add(
          RewardTrace.builder()
              .actionBuilder(operatorActionBuilder)
              .blockHash(block.getHash().toHexString())
              .blockNumber(blockHeader.getNumber())
              .type(REWARD_LABEL)
              .build());
    }

    // validator reward = Miner Reward
    final Action.Builder blockActionBuilder =
        Action.builder()
            .author(miningBeneficiaryCalculator.calculateBeneficiary(blockHeader).toHexString())
            .rewardType(BLOCK_LABEL)
            .value(validatorReward.toShortHexString());
    flatTraces.add(
        0,
        RewardTrace.builder()
            .actionBuilder(blockActionBuilder)
            .blockHash(block.getHash().toHexString())
            .blockNumber(blockHeader.getNumber())
            .type(REWARD_LABEL)
            .build());

    return flatTraces.stream();
  }
}
