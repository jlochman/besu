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
package org.hyperledger.besu.config;

import java.math.BigInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Initializes, validates and provides access to custom config part in genesis.json. Parameters are
 * described in detail in WP.
 */
public class MarklarConfigOptions {

  /** initial value of desired totalSupply/circulatingSupply. In WP denoted as \phi_0 */
  public static double initRatio;
  /** final value of desired totalSupply/circulatingSupply. In WP denoted as \phi_\infty */
  public static double finalRatio;
  /**
   * factor of exponential decay of desiredRatio from initRatio -> finalRatio. Value of desiredRatio
   * is equal to finalRatio + (finalRatio-initRatio) * ratioDecay ^ epochNumber. In WP denoted as
   * \alpha
   */
  public static double ratioDecay;

  /** how many coins is pre-mined */
  public static long preMined;
  /** block rewards in coins in epochNumber=0 */
  public static long initBlockReward;
  /** difficulty in epochNumber=0 */
  public static BigInteger initDifficulty;
  /** minimal difficulty */
  public static BigInteger minDifficulty;
  /** difficulty set in Besu original genesis file */
  private static BigInteger besuDifficulty;
  /** how many blocks is in epoch - when epoch ends, difficulty can change */
  public static long epochLength;
  /** seconds needed to generate block (this is obtained from ibft2 part of genesis) */
  public static long blockPeriodSeconds;

  /** x_{n+1} = kp * x_n + ki * (x_n + x_{n-1} + ...) + kd * (x_n - x_{n-1}) */
  public static double kp;

  public static double ki;
  /** number of x_n in ki sum */
  public static int ki_period;

  public static double kd;

  /** 0.030 means minimal 3% inflation per year. 3 decimal places are taken into account */
  public static double minInflation;
  /** minimal block reward in GEN */
  public static long minBlockReward;

  /** account address which belongs to operator */
  public static String operatorAddress;
  /**
   * 0.100 means operator receives 10% fees of each block. 3 decimal places are taken into account
   */
  public static double operatorBlockFeePart;
  /**
   * 0.150 means operator receives 15% of blockReward (i.e. reward without fees) of each block. 3
   * decimal places are taken into account
   */
  public static double operatorBlockRewardPart;

  /** analogous to operatorAddress, operatorBlockFeePart, operatorBlockRewardPart */
  public static String treasuryAddress;

  public static double treasuryBlockFeePart;
  public static double treasuryBlockRewardPart;

  /**
   * initializes this configuration from genesis
   *
   * @param configRoot root node of genesis file
   */
  public static void init(final ObjectNode configRoot) {
    // reads difficulty from root node (difficulty is in hex)
    String difficultyText = configRoot.get("difficulty").asText("0x1").replace("0x", "");
    MarklarConfigOptions.besuDifficulty = new BigInteger(difficultyText, 16);

    // reads config part of json as separate root node
    final ObjectNode config =
        JsonUtil.getObjectNode(configRoot, "config").orElse(JsonUtil.createEmptyObjectNode());

    // reads ibft2 section
    final ObjectNode ibftConfig =
        JsonUtil.getObjectNode(config, "ibft2").orElse(JsonUtil.createEmptyObjectNode());
    MarklarConfigOptions.blockPeriodSeconds = ibftConfig.get("blockperiodseconds").asLong(10L);

    // reads marklar section
    final ObjectNode marklar =
        JsonUtil.getObjectNode(configRoot, "marklar").orElse(JsonUtil.createEmptyObjectNode());
    MarklarConfigOptions.initRatio = marklar.get("init_ratio").asDouble(2);
    MarklarConfigOptions.finalRatio = marklar.get("final_ratio").asDouble(1.1);
    MarklarConfigOptions.ratioDecay = marklar.get("ratio_decay").asDouble(0.95);

    MarklarConfigOptions.preMined = marklar.get("pre_mined").asLong();
    MarklarConfigOptions.initBlockReward = marklar.get("init_block_reward").asLong(100);
    // difficulties are decimal
    MarklarConfigOptions.initDifficulty =
        new BigInteger(marklar.get("init_difficulty").asText("1000000"));
    MarklarConfigOptions.minDifficulty =
        new BigInteger(marklar.get("min_difficulty").asText("1000"));
    MarklarConfigOptions.epochLength = marklar.get("epoch_length").asLong(3_600);

    MarklarConfigOptions.kp = marklar.get("kp").asDouble(1.);
    MarklarConfigOptions.ki = marklar.get("ki").asDouble(0.);
    MarklarConfigOptions.ki_period = marklar.get("ki_period").asInt(1);
    MarklarConfigOptions.kd = marklar.get("kd").asDouble(0.);

    MarklarConfigOptions.minInflation = marklar.get("min_inflation").asDouble(0.05);
    MarklarConfigOptions.minBlockReward = marklar.get("min_block_reward").asLong(1L);

    MarklarConfigOptions.operatorAddress = marklar.get("operator_address").asText("");
    MarklarConfigOptions.operatorBlockFeePart =
        marklar.get("operator_block_fee_part").asDouble(0.1);
    MarklarConfigOptions.operatorBlockRewardPart =
        marklar.get("operator_block_reward_part").asDouble(0.);

    MarklarConfigOptions.treasuryAddress = marklar.get("treasury_address").asText("");
    MarklarConfigOptions.treasuryBlockFeePart = marklar.get("treasury_block_fee_part").asDouble(0.);
    MarklarConfigOptions.treasuryBlockRewardPart =
        marklar.get("treasury_block_reward_part").asDouble(0.05);
  }

  /**
   * validates input parameters
   *
   * @throws IllegalArgumentException if any parameter is not valid
   */
  public static void validate() throws IllegalArgumentException {
    // this ensures genesis block has the same difficulty as block=1
    if (!initDifficulty.equals(besuDifficulty)) {
      throw new IllegalArgumentException(
          "genesis.difficulty and marklar.init_difficulty must be the same");
    }
    if (MarklarConfigOptions.initRatio < 1) {
      throw new IllegalArgumentException("marklar.init_ratio must be ≥ 1");
    }
    if (MarklarConfigOptions.finalRatio < 1) {
      throw new IllegalArgumentException("marklar.final_ratio must be ≥ 1");
    }
    if (MarklarConfigOptions.ratioDecay > 1 || MarklarConfigOptions.ratioDecay <= 0) {
      throw new IllegalArgumentException("marklar.ratio_decay must be ≤ 1 and > 0");
    }

    if (MarklarConfigOptions.preMined < 0) {
      throw new IllegalArgumentException("marklar.pre_mined must be ≥ 0");
    }
    if (MarklarConfigOptions.initBlockReward <= 0) {
      throw new IllegalArgumentException("marklar.init_block_reward must be > 0");
    }
    if (MarklarConfigOptions.initDifficulty.compareTo(BigInteger.valueOf(1_000_000)) < 0) {
      throw new IllegalArgumentException("marklar.init_difficulty must be ≥ 1_000_000");
    }
    if (MarklarConfigOptions.minDifficulty.compareTo(BigInteger.ONE) < 0) {
      throw new IllegalArgumentException("marklar.min_difficulty must be ≥ 1");
    }
    if (MarklarConfigOptions.minDifficulty.compareTo(MarklarConfigOptions.initDifficulty) > 0) {
      throw new IllegalArgumentException("marklar.minDifficulty must be ≤ marklar.init_difficulty");
    }
    if (MarklarConfigOptions.epochLength < 10) {
      throw new IllegalArgumentException("marklar.epoch_length must be ≥ 10");
    }

    if (MarklarConfigOptions.kp < 0) {
      throw new IllegalArgumentException("marklar.kp must be ≥ 0");
    }
    if (MarklarConfigOptions.ki < 0) {
      throw new IllegalArgumentException("marklar.ki must be ≥ 0");
    }
    if (MarklarConfigOptions.ki_period < 0) {
      throw new IllegalArgumentException("marklar.ki_period must be ≥ 0");
    }
    if (MarklarConfigOptions.kd < 0) {
      throw new IllegalArgumentException("marklar.kd must be ≥ 0");
    }

    if (MarklarConfigOptions.minInflation < 0.001) {
      throw new IllegalArgumentException("marklar.min_inflation must be ≥ 0.001");
    }
    if (MarklarConfigOptions.minBlockReward < 1L) {
      throw new IllegalArgumentException("gbitconfig.min_block_reward must be ≥ 1");
    }

    if (MarklarConfigOptions.operatorBlockFeePart < 0
        || MarklarConfigOptions.operatorBlockFeePart > 1) {
      throw new IllegalArgumentException("marklar.operator_block_fee_part must be ≥ 0 and ≤ 1");
    }
    if (MarklarConfigOptions.operatorBlockRewardPart < 0
        || MarklarConfigOptions.operatorBlockRewardPart > 1) {
      throw new IllegalArgumentException("marklar.operator_block_reward_part must be ≥ 0 and ≤ 1");
    }

    if (MarklarConfigOptions.treasuryBlockFeePart < 0
        || MarklarConfigOptions.treasuryBlockFeePart > 1) {
      throw new IllegalArgumentException("marklar.treasury_block_fee_part must be ≥ 0 and ≤ 1");
    }
    if (MarklarConfigOptions.treasuryBlockRewardPart < 0
        || MarklarConfigOptions.treasuryBlockRewardPart > 1) {
      throw new IllegalArgumentException("marklar.treasury_block_reward_part must be ≥ 0 and ≤ 1");
    }

    if (MarklarConfigOptions.operatorBlockFeePart + MarklarConfigOptions.treasuryBlockFeePart > 1) {
      throw new IllegalArgumentException(
          "marklar.operator_block_fee_part + marklar.treasury_block_fee_part must be ≤ 1");
    }
    if (MarklarConfigOptions.operatorBlockRewardPart + MarklarConfigOptions.treasuryBlockRewardPart
        > 1) {
      throw new IllegalArgumentException(
          "marklar.operator_block_reward_part + marklar.treasury_block_reward_part must be ≤ 1");
    }
  }
}
