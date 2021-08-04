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
package org.hyperledger.besu.ethereum.util;

import org.hyperledger.besu.config.MarklarConfigOptions;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableAccount;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.core.WorldUpdater;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Marklar business logic has been extracted into this util class */
public class MarklarUtils {

  private static final Logger LOG = LogManager.getLogger();
  private static final Cache<Long, BigInteger> nextBlockDifficultyCache =
      CacheBuilder.newBuilder().maximumSize(100).build();

  @FunctionalInterface
  public interface ValidatorDecoder {

    Collection<Address> decode(BlockHeader blockHeader);
  }

  private MarklarUtils() {}

  /**
   * Distributes given totalReward. Operator receives operatorPart, Treasury receives treasuryPart,
   * account defined by beneficiaryAddress receives the rest. Updater is used to update account
   * balances, but the changes are not committed.
   *
   * @param totalReward total reward to distribute
   * @param updater used to update world state
   * @param beneficiaryAddress address which receives the rest of the total reward
   * @param operatorPart how much of the total reward operator receives. 0.145 means operator
   *     receives 14.5%. Only 3 decimal places are taken into account
   * @param treasuryPart how much of the total reward receives treasury. Similar to operatorPart
   */
  public static void distributeReward(
      final Wei totalReward,
      final WorldUpdater updater,
      final Address beneficiaryAddress,
      final double operatorPart,
      final double treasuryPart) {
    final Wei operatorReward = getPart(totalReward, operatorPart);
    final Wei treasuryReward = getPart(totalReward, treasuryPart);
    final Wei beneficiaryReward = totalReward.subtract(operatorReward).subtract(treasuryReward);

    getOperator(updater).incrementBalance(operatorReward);
    getTreasury(updater).incrementBalance(treasuryReward);
    updater.getOrCreate(beneficiaryAddress).getMutable().incrementBalance(beneficiaryReward);
  }

  /**
   * Calculates part from total reward. If total reward = 1000 wei, part = 0.123, result is 123 wei.
   * Part is rounded down to three decimal places
   *
   * @param totalReward total reward
   * @param part decimal rounded down to 3 decimal places, 0.123
   * @return part of totalReward in wei
   */
  public static Wei getPart(final Wei totalReward, final double part) {
    Wei reward1000 = totalReward.divide(1_000);
    BigDecimal part1000 =
        BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(1_000)).setScale(0, RoundingMode.DOWN);
    return reward1000.multiply(part1000.longValue());
  }

  /**
   * Gets difficulty for a new block which should be appended after parent. If the new block is in
   * the same epoch as parent, parent difficulty is returned. If the new block starts a new epoch,
   * difficulty is calculated. There is a simple cache to prevent repeating calculation.
   *
   * @param parent header of parent block
   * @param protocolContext protocol context
   * @param validatorDecoder used to decode list of validators from header
   * @return difficulty of block appended after parent
   */
  public static synchronized BigInteger getNextBlockDifficulty(
      final BlockHeader parent,
      final ProtocolContext protocolContext,
      final ValidatorDecoder validatorDecoder) {

    // check we are in the same epoch
    final long newBlockNumber = parent.getNumber() + 1;
    if (newBlockNumber % MarklarConfigOptions.epochLength != 0) {
      return parent.getDifficulty().toBigInteger();
    }

    // check cache
    BigInteger resultDifficulty = nextBlockDifficultyCache.getIfPresent(parent.getNumber());
    if (resultDifficulty != null) {
      return resultDifficulty;
    }

    // calculate required blockchain stats
    List<BlockStats> blockStatsList = new ArrayList<>();
    for (long blockNumber = protocolContext.getBlockchain().getChainHeadBlockNumber();
        blockNumber >= 0 && blockStatsList.size() < getPidIntervalLength();
        blockNumber -= MarklarConfigOptions.epochLength) {
      BlockStats blockStats = getBlockStats(blockNumber, protocolContext, validatorDecoder);
      logBlockStats(blockStats);
      blockStatsList.add(blockStats);
    }

    // if no stats are present, return previous difficulty
    if (blockStatsList.isEmpty()) {
      LOG.warn("blockStatsList is empty for block number: {}", parent.getNumber());
      return parent.getDifficulty().toBigInteger();
    }

    // do rest of calculations given blockStatsList
    final PidResult ratioPidResult =
        getRatioPid(blockStatsList.stream().map(BlockStats::getRatio).collect(Collectors.toList()));
    logRatioPid(ratioPidResult);

    resultDifficulty =
        adjustDifficulty(parent.getDifficulty(), ratioPidResult.pidNorm, blockStatsList);
    logChanges(parent.getDifficulty().toBigInteger(), resultDifficulty);

    // cache calculated value
    nextBlockDifficultyCache.put(parent.getNumber(), resultDifficulty);

    return resultDifficulty;
  }

  /**
   * Calculates stats for blockchain's world state in blockNumber.
   *
   * @param blockNumber defines blockchain's world state
   * @param protocolContext context allowing access to blockchain
   * @param validatorDecoder used to decode list of validators from header
   * @return stats
   */
  private static BlockStats getBlockStats(
      final long blockNumber,
      final ProtocolContext protocolContext,
      final ValidatorDecoder validatorDecoder) {
    BlockStats blockStats = new BlockStats();
    blockStats.blockNumber = blockNumber;

    blockStats.totalSupply = getTotalSupply(protocolContext.getBlockchain(), blockNumber);
    blockStats.validatorSupply = getValidatorSupply(protocolContext, validatorDecoder, blockNumber);

    blockStats.actualRatio = getActualRatio(blockStats.totalSupply, blockStats.validatorSupply);
    blockStats.desiredRatio = getDesiredRatio(blockNumber);
    blockStats.ratio = getRatio(blockStats.actualRatio, blockStats.desiredRatio);
    return blockStats;
  }

  /**
   * Adjusts difficulty of parent block based on pidNorm. pidNorm is always between [-1,1]. If
   * pidNorm = 0, parent difficulty is returned. If pidNorm > 0, parent difficulty is multiplied by
   * (1+pid). If pidNorm < 0, parent difficulty is divided by (1-pid). Note, that resulting
   * difficulty should change in range [-50%, +100%].
   *
   * @param parentDifficulty difficulty of parent block
   * @param pidNorm normalized factor of PID regulation
   * @param blockStatsList list containing stats calculated from blockchain in given blockNumbers.
   *     First item must correspond to a block with the highest number. Used to extract totalSupply.
   * @return adjusted difficulty
   */
  private static BigInteger adjustDifficulty(
      final Difficulty parentDifficulty,
      final BigDecimal pidNorm,
      final List<BlockStats> blockStatsList) {
    BigInteger resultDifficulty;
    if (pidNorm.signum() == 0) {
      resultDifficulty = parentDifficulty.toBigInteger();
    } else if (pidNorm.signum() > 0) {
      BigDecimal difficulty = new BigDecimal(parentDifficulty.toBigInteger(), 0);
      BigDecimal multiplier = BigDecimal.ONE.add(pidNorm);
      resultDifficulty =
          difficulty.multiply(multiplier).setScale(0, RoundingMode.DOWN).toBigInteger();
    } else {
      BigDecimal difficulty = new BigDecimal(parentDifficulty.toBigInteger(), 0);
      BigDecimal divisor = BigDecimal.ONE.subtract(pidNorm);
      resultDifficulty = difficulty.divide(divisor, 0, RoundingMode.DOWN).toBigInteger();
    }

    return resultDifficulty
        .min(getMaxDifficulty(blockStatsList.get(0).totalSupply).toBigInteger())
        .max(getMinDifficulty().toBigInteger());
  }

  /**
   * Calculates total supply of coins in given block. Calculation uses difficulties in blocks which
   * are the first block in given epoch.
   *
   * @param blockchain actual blockchain
   * @param inBlockNumber number of block in which we want to know total supply
   * @return total supply in wei
   */
  private static Wei getTotalSupply(final MutableBlockchain blockchain, final long inBlockNumber) {
    Wei totalSupply =
        Wei.of(BigInteger.valueOf(MarklarConfigOptions.preMined).multiply(BigInteger.TEN.pow(18)));
    // iterate over epochs
    for (int i = 0; i <= inBlockNumber / MarklarConfigOptions.epochLength; i++) {
      long blockNumber = i * MarklarConfigOptions.epochLength;
      long blockCount = Math.min(MarklarConfigOptions.epochLength, inBlockNumber - blockNumber + 1);
      if (i == 0) {
        // subtract genesis in which nothing is generated
        blockCount--;
      }
      Optional<Block> maybeBlock = blockchain.getBlockByNumber(blockNumber);
      if (maybeBlock.isEmpty()) {
        LOG.warn("block for blockNumber {} not found", blockNumber);
        continue;
      }
      Wei blockReward = getBlockReward(maybeBlock.get().getHeader().getDifficulty());
      totalSupply = totalSupply.add(blockReward.multiply(blockCount));
    }
    return totalSupply;
  }

  /**
   * Calculates supply of coins on validator addresses. Calculation uses world state in defined
   * block number.
   *
   * @param protocolContext the protocol context
   * @param validatorDecoder decodes list of validators from block header
   * @param inBlockNumber number of block in which we want to know validator supply
   * @return validator supply in wei
   */
  private static Wei getValidatorSupply(
      final ProtocolContext protocolContext,
      final ValidatorDecoder validatorDecoder,
      final long inBlockNumber) {
    Optional<BlockHeader> maybeBlockHeader =
        protocolContext.getBlockchain().getBlockHeader(inBlockNumber);
    if (maybeBlockHeader.isEmpty()) {
      LOG.info("Unable to obtain validator supply because block header is not available");
      throw new IllegalStateException("Block header not available for block " + inBlockNumber);
    }
    BlockHeader blockHeader = maybeBlockHeader.get();

    final Hash parentStateRoot = blockHeader.getStateRoot();
    final MutableWorldState worldState =
        protocolContext
            .getWorldStateArchive()
            .getMutable(parentStateRoot, blockHeader.getHash())
            .orElseThrow(
                () -> {
                  LOG.info(
                      "Unable to obtain validator supply because world state is not available");
                  return new IllegalStateException(
                      "World state not available for block "
                          + blockHeader.getNumber()
                          + " with state root "
                          + parentStateRoot);
                });

    Wei validatorSupply = Wei.ZERO;
    if (worldState == null) {
      LOG.error("null updater");
      return validatorSupply;
    }

    for (Address validator : validatorDecoder.decode(blockHeader)) {
      Account valAccount = worldState.get(validator);
      if (valAccount == null) {
        if (blockHeader.getNumber() > 0) {
          LOG.info(
              "null account for address: {} on blockNumber: {}",
              validator,
              blockHeader.getNumber());
        }
        continue;
      }
      validatorSupply = validatorSupply.add(valAccount.getBalance());
    }

    return validatorSupply;
  }

  /**
   * Calculates actualRatio = totalSupply / circulatingSupply, where circulatingSupply = totalSupply
   * - validatorSupply. If validatorSupply is zero, returned ration == 1. Result is rounded down to
   * 3 decimal places.
   *
   * @param totalSupply total coin supply
   * @param validatorSupply validator coin supply of
   * @return decimal rounded down to 3 decimal places
   */
  private static BigDecimal getActualRatio(final Wei totalSupply, final Wei validatorSupply) {
    if (validatorSupply.isZero()) {
      return BigDecimal.ONE;
    }
    final Wei circulatingSupply = totalSupply.subtract(validatorSupply);
    return new BigDecimal(totalSupply.toBigInteger(), 0)
        .divide(new BigDecimal(circulatingSupply.toBigInteger(), 0), 3, RoundingMode.DOWN);
  }

  /**
   * For given block calculates desiredRatio = finalRatio + (finalRatio-initRatio) * ratioDecay ^
   * epochNumber. Result is rounded down to 3 decimal places.
   *
   * @param inBlockNumber defines epochNumber
   * @return decimal rounded down to 3 decimal places
   */
  private static BigDecimal getDesiredRatio(final long inBlockNumber) {
    long epoch = getEpoch(inBlockNumber);

    BigDecimal decayMultiplier = BigDecimal.valueOf(MarklarConfigOptions.ratioDecay);
    decayMultiplier = decayMultiplier.pow((int) epoch);

    BigDecimal result = BigDecimal.valueOf(MarklarConfigOptions.initRatio);
    result = result.subtract(BigDecimal.valueOf(MarklarConfigOptions.finalRatio));
    result = result.multiply(decayMultiplier);
    result = result.add(BigDecimal.valueOf(MarklarConfigOptions.finalRatio));

    return result.setScale(3, RoundingMode.DOWN);
  }

  /**
   * Calculates ratio of actualRatio and desiredRatio. Ratio is divided by sqrt(max(0.001,
   * desiredRatio - finalRatio)) to ensure bigger ratio values in later epochs of blockchain. Result
   * is rounded down to 3 decimal places. ratio > 1 is reduced to 1, ratio < -1 is increased to -1.
   *
   * @param actualRatio represents dividend
   * @param desiredRatio represents divisor
   * @return decimal rounded down to 3 decimal places
   */
  private static BigDecimal getRatio(final BigDecimal actualRatio, final BigDecimal desiredRatio) {
    BigDecimal dividend = actualRatio.subtract(desiredRatio);
    BigDecimal divisor = desiredRatio.subtract(BigDecimal.valueOf(MarklarConfigOptions.finalRatio));
    divisor = divisor.max(BigDecimal.valueOf(0.001)).sqrt(MathContext.DECIMAL64);

    BigDecimal ratio = dividend.divide(divisor, 3, RoundingMode.DOWN);
    return ratio.max(BigDecimal.valueOf(-1L)).min(BigDecimal.valueOf(1L));
  }

  /**
   * Calculates PID for given ratios. pid calculates as x_{n+1} = kp * x_n + ki * (x_n + x_{n-1} +
   * ...) + kd * (x_n - x_{n-1}). Result pidNorm is obtained by normalizing x_{n+1} by hyperbolic
   * tangent and rounding it down to 3 decimal places.
   *
   * @param ratios Ordered list containing ratio history. First item contains the most recent ratio.
   * @return resulting pid values of ratio
   */
  private static PidResult getRatioPid(final List<BigDecimal> ratios) {
    PidResult pidResult = new PidResult();

    pidResult.p = BigDecimal.valueOf(MarklarConfigOptions.kp).multiply(ratios.get(0));

    for (BigDecimal ratio : ratios) {
      pidResult.i = pidResult.i.add(ratio);
    }
    pidResult.i = pidResult.i.multiply(BigDecimal.valueOf(MarklarConfigOptions.ki));

    if (ratios.size() >= 2 && MarklarConfigOptions.kd != 0) {
      pidResult.d =
          ratios
              .get(0)
              .subtract(ratios.get(1))
              .multiply(BigDecimal.valueOf(MarklarConfigOptions.kd));
    }

    pidResult.pid = pidResult.p.add(pidResult.i).add(pidResult.d);

    double normalized = Math.tanh(pidResult.pid.doubleValue());
    pidResult.pidNorm = BigDecimal.valueOf(normalized).setScale(3, RoundingMode.DOWN);
    return pidResult;
  }

  /**
   * Calculates epoch from block number.
   *
   * @param inBlockNumber number of block for which the epoch should be obtained
   * @return epoch
   */
  private static long getEpoch(final long inBlockNumber) {
    return inBlockNumber / MarklarConfigOptions.epochLength;
  }

  /**
   * Calculates interval needed for pid regulation.
   *
   * @return interval assumed by pid regulation
   */
  private static int getPidIntervalLength() {
    int intervalLength = MarklarConfigOptions.kd == 0 ? 1 : 2;
    return Math.max(intervalLength, MarklarConfigOptions.ki_period);
  }

  /**
   * Calculates number of blocks generated per year.
   *
   * @return number of blocks generated per year
   */
  private static long getNumBlocksPerYear() {
    return Math.max(1, TimeUnit.DAYS.toSeconds(365L) / MarklarConfigOptions.blockPeriodSeconds);
  }

  /**
   * Calculates blockReward given block difficulty. blockReward = initBlockReward * 10^18 *
   * initDifficulty / difficulty
   *
   * @param blockDifficulty difficulty for which blockReward should be calculated
   * @return block reward in wei
   */
  public static Wei getBlockReward(final Difficulty blockDifficulty) {
    assert blockDifficulty.getValue().longValue() != 0 : "blockDifficulty cannot be zero";
    BigInteger initRewardWei =
        BigInteger.valueOf(MarklarConfigOptions.initBlockReward).multiply(BigInteger.TEN.pow(18));
    BigInteger currentReward =
        initRewardWei
            .multiply(MarklarConfigOptions.initDifficulty)
            .divide(blockDifficulty.toBigInteger());
    return Wei.of(currentReward);
  }

  /**
   * Inverse function for {@link #getBlockReward(Difficulty)}
   *
   * @param blockReward block reward in wei
   * @return block difficulty
   */
  public static Difficulty getDifficulty(final Wei blockReward) {
    assert blockReward.getValue().longValue() != 0 : "blockReward cannot be zero";
    BigInteger initRewardWei =
        BigInteger.valueOf(MarklarConfigOptions.initBlockReward).multiply(BigInteger.TEN.pow(18));
    BigInteger currentDifficulty =
        initRewardWei
            .multiply(MarklarConfigOptions.initDifficulty)
            .divide(blockReward.toBigInteger());
    if (currentDifficulty.compareTo(BigInteger.ONE) < 0) {
      LOG.warn(
          "difficulty: {} from blockReward: {} is < 1",
          currentDifficulty,
          blockReward.toBigInteger());
      currentDifficulty = BigInteger.ONE;
    }
    return Difficulty.of(currentDifficulty);
  }

  /**
   * Calculates maximal difficulty based on totalSupply. Blockchain running on maximal difficulty
   * generates such amount of coins per year, that minInflation of coins occurs. The amount of coins
   * generated per block cannot be lower than minBlockReward.
   *
   * @param totalSupply total supply of coins
   * @return maximal difficulty
   */
  private static Difficulty getMaxDifficulty(final Wei totalSupply) {
    if (totalSupply.isZero()) {
      return Difficulty.of(MarklarConfigOptions.initDifficulty);
    }
    Wei generatePerYear = getPart(totalSupply, MarklarConfigOptions.minInflation);
    Wei rewardPerBlock = generatePerYear.divide(getNumBlocksPerYear());

    Wei minReward = Wei.fromEth(MarklarConfigOptions.minBlockReward);
    if (minReward.compareTo(rewardPerBlock) > 0) {
      rewardPerBlock = minReward;
    }

    return getDifficulty(rewardPerBlock);
  }

  /**
   * Gets minimal allowed difficulty
   *
   * @return minimal difficulty
   */
  private static Difficulty getMinDifficulty() {
    return Difficulty.of(MarklarConfigOptions.minDifficulty);
  }

  /**
   * Gets or creates operator address.
   *
   * @param updater updater of relevant world state
   * @return operator address as mutable account
   */
  private static MutableAccount getOperator(final WorldUpdater updater) {
    return updater
        .getOrCreate(Address.fromHexString(MarklarConfigOptions.operatorAddress))
        .getMutable();
  }

  /**
   * Gets or creates treasury address.
   *
   * @param updater updater of relevant world state
   * @return treasury address as mutable account
   */
  private static MutableAccount getTreasury(final WorldUpdater updater) {
    return updater
        .getOrCreate(Address.fromHexString(MarklarConfigOptions.treasuryAddress))
        .getMutable();
  }

  /**
   * Helper to log stats on relevant blocks where epoch changes.
   *
   * @param blockStats block stats to log
   */
  private static void logBlockStats(final BlockStats blockStats) {
    LOG.info(
        "blockNumber: {}, totalSupply: {}, validatorSupply: {}, actualRatio: {}, desiredRatio: {}, ratio: {}",
        String.format("%,d", blockStats.blockNumber),
        String.format("%,.2f", blockStats.totalSupply.divide((long) 1e18).getValue().doubleValue()),
        String.format(
            "%,.2f", blockStats.validatorSupply.divide((long) 1e18).getValue().doubleValue()),
        String.format("%,.3f", blockStats.actualRatio),
        String.format("%,.3f", blockStats.desiredRatio),
        String.format("%,.3f", blockStats.ratio));
  }

  /**
   * Helper to log relevant information of pid.
   *
   * @param pidResult pid results
   */
  private static void logRatioPid(final PidResult pidResult) {
    LOG.info(
        "ratioPidNorm: {}, p: {}, i: {}, d: {}, pid: {}",
        String.format("%,.3f", pidResult.pidNorm),
        String.format("%,.3f", pidResult.p),
        String.format("%,.3f", pidResult.i),
        String.format("%,.3f", pidResult.d),
        String.format("%,.3f", pidResult.pid));
  }

  /**
   * Helper to log changes in difficulty and block reward
   *
   * @param parentDifficulty difficulty of parent block
   * @param resultDifficulty difficulty of block to be added
   */
  private static void logChanges(
      final BigInteger parentDifficulty, final BigInteger resultDifficulty) {
    LOG.info(
        String.format(
            "difficulty: %,d -> %,d [%s, %+,d]",
            parentDifficulty,
            resultDifficulty,
            getPercentChangeString(parentDifficulty, resultDifficulty),
            resultDifficulty.subtract(parentDifficulty)));

    Wei parentBlockReward = getBlockReward(Difficulty.of(parentDifficulty));
    BigDecimal parentReward = new BigDecimal(parentBlockReward.toBigInteger(), 18);

    Wei resultBlockReward = getBlockReward(Difficulty.of(resultDifficulty));
    BigDecimal resultReward = new BigDecimal(resultBlockReward.toBigInteger(), 18);

    LOG.info(
        String.format(
            "blockReward: %,.3f -> %,.3f [%s, %+,.4f]",
            parentReward,
            resultReward,
            getPercentChangeString(parentReward, resultReward),
            resultReward.subtract(parentReward)));
  }

  /**
   * Creates string showing percentage difference of (toValue-fromValue)/fromValue.
   *
   * @param fromValue one of the values
   * @param toValue the second of the values
   * @return decimal percentage rounded down to 3 decimal places
   */
  private static String getPercentChangeString(
      final BigInteger fromValue, final BigInteger toValue) {
    return getPercentChangeString(new BigDecimal(fromValue, 0), new BigDecimal(toValue, 0));
  }

  /**
   * Creates string showing percentage difference of (toValue-fromValue)/fromValue.
   *
   * @param fromValue one of the values
   * @param toValue the second of the values
   * @return decimal percentage rounded down to 3 decimal places
   */
  private static String getPercentChangeString(
      final BigDecimal fromValue, final BigDecimal toValue) {
    BigDecimal result = toValue.subtract(fromValue).divide(fromValue, 10, RoundingMode.DOWN);
    result = result.multiply(BigDecimal.valueOf(100L));
    return String.format("%+.3f%%", result.doubleValue());
  }

  /** Data wrapper holding relevant blockchain stats in given block number */
  private static class BlockStats {

    private long blockNumber = 0L;

    private Wei totalSupply = Wei.ZERO;
    private Wei validatorSupply = Wei.ZERO;

    private BigDecimal actualRatio = BigDecimal.ONE;
    private BigDecimal desiredRatio = BigDecimal.ONE;
    private BigDecimal ratio = BigDecimal.ONE;

    public BigDecimal getRatio() {
      return ratio;
    }
  }

  /** Data wrapper holding relevant data of pid */
  private static class PidResult {

    private BigDecimal p = BigDecimal.ZERO;
    private BigDecimal i = BigDecimal.ZERO;
    private BigDecimal d = BigDecimal.ZERO;
    private BigDecimal pid = BigDecimal.ZERO;
    private BigDecimal pidNorm = BigDecimal.ZERO;
  }
}
