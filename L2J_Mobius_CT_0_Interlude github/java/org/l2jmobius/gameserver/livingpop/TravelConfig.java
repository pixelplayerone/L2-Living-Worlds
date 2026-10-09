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
 * Phase 5 configuration: travel, town visits and player-like supplies. Read from the module's {@code config/module.ini}
 * next to {@link LivingPopulationConfig} and kept separate so the earlier phases' surface is unchanged. Dependency-free.
 *
 * @param enabled whether bots travel between zones and towns and shop only in town; off keeps the Phase 4 behavior
 *            (instant restocking in place, no movement)
 * @param zonesFile the travel catalog, relative to the server working directory
 * @param potionStock healing potions a melee bot likes to carry (tanks carry half again as many, casters, archers and
 *            healers half as many)
 * @param potionRestockFraction restock potions at or below this fraction of the stock
 * @param soulshotStockMinutes minutes of hunting worth of soulshots a bot likes to carry
 * @param soulshotRestockFraction restock soulshots below this fraction of the stock
 * @param soulshotMinPurchase smallest batch of soulshots a bot buys; it buys none when it cannot or need not buy this many
 *            (never more than half its stock, so a small stock still restocks)
 * @param spiritshotsPerKill spiritshots a mystic fires per kill (fighters fire {@code SoulshotsPerKill} soulshots)
 * @param escapeStock Scrolls of Escape a bot likes to carry
 * @param reserveFloor smallest operating reserve of adena
 * @param reservePerLevel operating reserve per level
 * @param reserveFraction operating reserve as a fraction of the bot's adena
 * @param moveSpeed walking speed, game units per second
 * @param escapeCastMs Scroll of Escape cast time
 * @param errandStopMs time spent at each shop or NPC in town
 * @param afkChancePercent chance of an AFK break per town visit
 * @param afkMinMs shortest AFK break
 * @param afkMaxMs longest AFK break
 * @param potionsPerHour potions drunk per hour of cold hunting
 * @param zoneCapacity bots per zone before it counts as full (0 = no limit)
 * @param retryMs wait before retrying when no affordable way on exists
 * @param escapeMinDistance a town closer than this is walked to even with a Scroll of Escape
 * @param dropIncome whether cold kills pay from the zone monsters' real drop lists (adena, plus loot sold in town)
 *            instead of the flat {@code AdenaPerMobLevel} formula
 * @param deathsPerHour cold deaths per hour of hunting for a bot in the middle of a fitting zone (0 = cold bots never
 *            die)
 * @param deathRecoverMs how long a bot that died takes to get back on its feet in town
 * @param restEveryMs cold hunting between rests (0 = no rests)
 * @param restMs how long a rest lasts
 * @param avoidZoneMs how long a zone is avoided after dying there twice within that time
 * @param classChanges whether bots change class through the class-change quest errand
 * @param classQuestMs how long the quest hunt of the first, second and third class change takes
 * @param skillTraining whether bots earn SP and learn their skills at the trainer, buying spellbooks, instead of knowing
 *            every skill of their level
 * @param gearSlots whether bots keep their gear per slot, buy it piece by piece and wear the better drops, instead of
 *            buying whole gear tiers
 * @param gearTrade whether gear no shop in the bot's town sells can be bought as if from another player, at the item's
 *            reference price (a stand-in until bots trade with each other; see {@code GearCatalog.tradePrice})
 */
public record TravelConfig(boolean enabled, String zonesFile, int potionStock, double potionRestockFraction, int soulshotStockMinutes, double soulshotRestockFraction, long soulshotMinPurchase, double spiritshotsPerKill, int escapeStock, long reserveFloor, long reservePerLevel, double reserveFraction, double moveSpeed, long escapeCastMs, long errandStopMs, int afkChancePercent, long afkMinMs, long afkMaxMs, double potionsPerHour, int zoneCapacity, long retryMs, double escapeMinDistance, boolean dropIncome, double deathsPerHour, long deathRecoverMs, long restEveryMs, long restMs, long avoidZoneMs, boolean classChanges, long[] classQuestMs, boolean skillTraining, boolean gearSlots, boolean gearTrade)
{
	/** @return the shipped defaults */
	public static TravelConfig defaults()
	{
		return new TravelConfig(true, "modules/living-population/data/zones.xml", 30, 0.25, 30, 0.2, 200L, ColdLife.Params.DEFAULT_SPIRITSHOTS_PER_KILL, 2, 500L, 250L, 0.10, 120.0, 20_000L, 15_000L, 15, 120_000L, 600_000L, 4.0, 16, 300_000L, 2500.0, true, 0.3, 90_000L, 600_000L, 60_000L, 3_600_000L, true, new long[]
		{
			3_600_000L,
			10_800_000L,
			14_400_000L
		}, true, true, true);
	}

	/**
	 * @param base the main config (shares the kill rate, soulshot use and gear tier tuning)
	 * @return the supply tuning (with gear kept per slot no whole tiers are bought, so the tier step is 0)
	 */
	public SupplyPlanner.Params supplyParams(LivingPopulationConfig base)
	{
		return new SupplyPlanner.Params(potionStock, potionRestockFraction, soulshotStockMinutes, soulshotRestockFraction, escapeStock, reserveFloor, reservePerLevel, reserveFraction, base.killsPerMinute(), base.soulshotsPerKill(), base.gearUpgradeCost(), gearSlots ? 0 : base.gearTierLevelStep(), soulshotMinPurchase);
	}

	/** @return the death and rest tuning, or null when cold bots neither die nor rest */
	public ColdRisk.Params riskParams()
	{
		if ((deathsPerHour <= 0) && ((restEveryMs <= 0) || (restMs <= 0)))
		{
			return null;
		}
		return new ColdRisk.Params(Math.max(0.0, deathsPerHour), deathRecoverMs, restEveryMs, restMs, avoidZoneMs);
	}

	/** @return the class-change quest hunt lengths, or null when bots do not change class */
	public long[] classQuestParams()
	{
		return classChanges ? classQuestMs.clone() : null;
	}

	/** @return the travel tuning */
	public ColdLife.Params travelParams()
	{
		return new ColdLife.Params(moveSpeed, escapeCastMs, errandStopMs, afkChancePercent, afkMinMs, afkMaxMs, potionsPerHour, zoneCapacity, retryMs, escapeMinDistance, spiritshotsPerKill);
	}
}
