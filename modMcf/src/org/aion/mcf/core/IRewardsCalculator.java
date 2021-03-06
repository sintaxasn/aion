package org.aion.mcf.core;

import java.math.BigInteger;
import org.aion.mcf.types.AbstractBlockHeader;

/**
 * Calculates the rewards given for sealing a particular block, depending on the implementation we
 * may be able to swap different difficulty implementations.
 *
 * @author yao
 */
@FunctionalInterface
public interface IRewardsCalculator {
    BigInteger calculateReward(AbstractBlockHeader header);
}
