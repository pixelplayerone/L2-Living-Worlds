/*
 * Copyright (c) 2013 L2jMobius
 *
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingpop;

/**
 * The pure Phase 4 needs decision: given a bot's economic state, which goal it should pursue right now. It is the
 * counterpart to {@link ColdEconomy} (which accrues and applies the transactions) and mirrors L2Solo's {@code NeedsEvaluator}
 * in the {@code Bot/Goals} layer, adapted to our Interlude economy. Kept free of game types so the thresholds are locked
 * down in the standalone test lane.
 *
 * <p>Priority is survival before shopping: a bot that is out of soulshots and can afford them restocks before it spends on
 * a gear upgrade, and everything falls back to hunting when no purchase is warranted or affordable.
 */
public final class NeedsEvaluator
{
	private NeedsEvaluator()
	{
	}

	/** The high-level goal a bot pursues. */
	public enum Need
	{
		HUNT,
		RESTOCK,
		UPGRADE
	}

	/**
	 * Decides the current need from the bot's state (a pure function of the inputs; it changes nothing).
	 * @param level the bot's level
	 * @param adena the bot's adena
	 * @param soulshots the bot's soulshot count
	 * @param potions the bot's healing-potion count
	 * @param gearTier the bot's current gear tier
	 * @param params the economy tuning
	 * @return the need to act on this tick
	 */
	public static Need evaluate(int level, long adena, long soulshots, long potions, int gearTier, ColdEconomy.Params params)
	{
		// Restock first: a bot below a consumable floor (soulshots or potions) that can afford it buys before anything else.
		final boolean needSoulshots = (soulshots < params.soulshotRestockThreshold()) && (adena >= ((long) params.soulshotRestockBatch() * params.soulshotCost()));
		final boolean needPotions = (potions < params.potionRestockThreshold()) && (adena >= ((long) params.potionRestockBatch() * params.potionCost()));
		if (needSoulshots || needPotions)
		{
			return Need.RESTOCK;
		}

		// Then gear: upgrade while a higher tier is unlocked by level and affordable.
		final int tierCeiling = (params.gearTierLevelStep() > 0) ? (level / params.gearTierLevelStep()) : 0;
		if ((gearTier < tierCeiling) && (adena >= (params.gearUpgradeCost() * (gearTier + 1L))))
		{
			return Need.UPGRADE;
		}

		return Need.HUNT;
	}
}
