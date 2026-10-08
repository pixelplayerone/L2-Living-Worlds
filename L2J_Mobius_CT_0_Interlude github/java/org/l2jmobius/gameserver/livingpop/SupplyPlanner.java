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
 * How a living bot keeps its consumables, modeled on how players (and L2Solo's bots) do it rather than on fixed batches:
 * a small stock target per item, restocked in town with whatever the bot can afford while keeping an operating reserve
 * of adena, with partial purchases allowed. Soulshots are only wanted once the bot actually uses them (after the newbie
 * soulshot reward). Pure: every method is a function of its inputs.
 */
public final class SupplyPlanner
{
	private SupplyPlanner()
	{
	}

	/**
	 * Supply tuning, from the module config.
	 * @param potionStock how many healing potions a bot likes to carry (see {@link #withPotionStock}, which sets it per role)
	 * @param potionRestockFraction restock when the potion count is at or below this fraction of the stock
	 * @param soulshotStockMinutes how many minutes of hunting worth of soulshots a bot likes to carry
	 * @param soulshotRestockFraction restock when soulshots fall below this fraction of the stock target
	 * @param escapeStock how many Scrolls of Escape a bot likes to carry
	 * @param reserveFloor the smallest operating reserve of adena a bot keeps
	 * @param reservePerLevel operating reserve per bot level
	 * @param reserveFraction operating reserve as a fraction of the bot's adena
	 * @param killsPerMinute kills per minute while hunting (shared with the experience model)
	 * @param soulshotsPerKill soulshots fired per kill
	 * @param gearUpgradeCost fallback adena cost of a gear tier upgrade when no real price is known (the next tier then
	 *            costs this * (tier + 1))
	 * @param gearTierLevelStep levels per unlocked gear tier; 0 disables upgrades
	 */
	public record Params(int potionStock, double potionRestockFraction, int soulshotStockMinutes, double soulshotRestockFraction, int escapeStock, long reserveFloor, long reservePerLevel, double reserveFraction, double killsPerMinute, double soulshotsPerKill, long gearUpgradeCost, int gearTierLevelStep)
	{
		/**
		 * @param stock the potion stock for this bot's role
		 * @return the same tuning with that potion stock
		 */
		public Params withPotionStock(int stock)
		{
			return new Params(Math.max(0, stock), potionRestockFraction, soulshotStockMinutes, soulshotRestockFraction, escapeStock, reserveFloor, reservePerLevel, reserveFraction, killsPerMinute, soulshotsPerKill, gearUpgradeCost, gearTierLevelStep);
		}

		/** @return the potion count at or below which the bot restocks (at least 1 when it carries any) */
		public long potionRestockAt()
		{
			return (potionStock <= 0) ? 0L : Math.max(1L, (long) Math.floor(potionStock * potionRestockFraction));
		}
	}

	/**
	 * Unit prices for the items this bot would buy now (the manager reads them from the real item data for the bot's
	 * level and weapon grade).
	 * @param potion one healing potion
	 * @param soulshot one soulshot of the bot's grade
	 * @param escape one Scroll of Escape
	 * @param nextTier the bot's next gear tier (its real kit price from the item data), or 0 when unknown so the
	 *            configured fallback applies
	 */
	public record Prices(long potion, long soulshot, long escape, long nextTier)
	{
	}

	/**
	 * What a shopping visit bought.
	 * @param escapes Scrolls of Escape bought
	 * @param potions potions bought
	 * @param soulshots soulshots bought
	 * @param upgraded whether a gear tier was bought
	 * @param cost the total adena spent
	 */
	public record Purchase(long escapes, long potions, long soulshots, boolean upgraded, long cost)
	{
		/** @return whether anything was bought */
		public boolean any()
		{
			return (escapes > 0) || (potions > 0) || (soulshots > 0) || upgraded;
		}
	}

	/**
	 * The adena a bot keeps aside and never spends on supplies: the largest of the floor, level times the per-level
	 * amount, and a fraction of what it has.
	 * @param level the bot level
	 * @param adena the bot's adena
	 * @param params the tuning
	 * @return the reserve
	 */
	public static long reserve(int level, long adena, Params params)
	{
		return Math.max(params.reserveFloor(), Math.max(level * params.reservePerLevel(), (long) Math.ceil(Math.max(0L, adena) * params.reserveFraction())));
	}

	/**
	 * @param adena the bot's adena
	 * @param reserve its operating reserve
	 * @return the adena it may spend
	 */
	public static long spendable(long adena, long reserve)
	{
		return Math.max(0L, adena - reserve);
	}

	/**
	 * @param rewardClaimed whether the bot has its newbie soulshots (only then does it use soulshots)
	 * @param params the tuning
	 * @return how many soulshots the bot likes to carry (0 before it uses them)
	 */
	public static long soulshotTarget(boolean rewardClaimed, Params params)
	{
		if (!rewardClaimed)
		{
			return 0L;
		}
		return Math.max(0L, Math.round(params.killsPerMinute() * params.soulshotsPerKill() * params.soulshotStockMinutes()));
	}

	/**
	 * @param potions the bot's potion count
	 * @param params the tuning
	 * @return whether it is time to restock potions
	 */
	public static boolean potionsLow(long potions, Params params)
	{
		return (params.potionStock() > 0) && (potions <= params.potionRestockAt());
	}

	/**
	 * @param soulshots the bot's soulshot count
	 * @param rewardClaimed whether the bot uses soulshots yet
	 * @param params the tuning
	 * @return whether it is time to restock soulshots
	 */
	public static boolean soulshotsLow(long soulshots, boolean rewardClaimed, Params params)
	{
		final long target = soulshotTarget(rewardClaimed, params);
		return (target > 0) && (soulshots < Math.round(target * params.soulshotRestockFraction()));
	}

	/**
	 * @param level the bot level
	 * @param params the tuning
	 * @return the highest gear tier the level unlocks
	 */
	public static int tierCeiling(int level, Params params)
	{
		return (params.gearTierLevelStep() > 0) ? (level / params.gearTierLevelStep()) : 0;
	}

	/**
	 * @param gearTier the bot's current tier
	 * @param prices the prices (a real next-tier price wins)
	 * @param params the tuning
	 * @return the price of the next tier
	 */
	public static long nextTierPrice(int gearTier, Prices prices, Params params)
	{
		return (prices.nextTier() > 0) ? prices.nextTier() : (params.gearUpgradeCost() * (gearTier + 1L));
	}

	/**
	 * What a bot buys on a town visit, in the order a player would: a spare Scroll of Escape or two (cheap, and the way
	 * home), then potions up to its stock, then the next gear tier if it can afford it after that, then soulshots with
	 * what is left. Every purchase respects the operating reserve; partial amounts are fine.
	 * @param level the bot level
	 * @param adena the bot's adena
	 * @param escapes Scrolls of Escape carried
	 * @param potions potions carried
	 * @param soulshots soulshots carried
	 * @param gearTier current gear tier
	 * @param rewardClaimed whether the bot uses soulshots yet
	 * @param prices unit prices
	 * @param params the tuning
	 * @return what was bought
	 */
	public static Purchase shop(int level, long adena, long escapes, long potions, long soulshots, int gearTier, boolean rewardClaimed, Prices prices, Params params)
	{
		long budget = spendable(adena, reserve(level, adena, params));
		final long escapesBought = buy(Math.max(0L, params.escapeStock() - escapes), prices.escape(), budget);
		budget -= escapesBought * prices.escape();
		final long potionsBought = buy(Math.max(0L, params.potionStock() - potions), prices.potion(), budget);
		budget -= potionsBought * prices.potion();
		boolean upgraded = false;
		long upgradeCost = 0L;
		if ((gearTier < tierCeiling(level, params)) && (budget >= nextTierPrice(gearTier, prices, params)))
		{
			upgradeCost = nextTierPrice(gearTier, prices, params);
			budget -= upgradeCost;
			upgraded = true;
		}
		final long soulshotsBought = buy(Math.max(0L, soulshotTarget(rewardClaimed, params) - soulshots), prices.soulshot(), budget);
		final long cost = (escapesBought * prices.escape()) + (potionsBought * prices.potion()) + upgradeCost + (soulshotsBought * prices.soulshot());
		return new Purchase(escapesBought, potionsBought, soulshotsBought, upgraded, cost);
	}

	private static long buy(long wanted, long unitPrice, long budget)
	{
		if ((wanted <= 0) || (budget <= 0))
		{
			return 0L;
		}
		if (unitPrice <= 0)
		{
			return wanted;
		}
		return Math.min(wanted, budget / unitPrice);
	}
}
