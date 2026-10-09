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
 * Immutable, dependency-free configuration for the Living Population simulation.
 *
 * <p>The values come from the module's own {@code config/module.ini}, read by the thin module entry point and handed to
 * {@link LivingPopulationManager#start(LivingPopulationConfig)}. Keeping this a plain record with no game dependencies
 * means the whole config surface is testable in the standalone lane and the core never reaches into a central config
 * class. This is the Phase 1 (cold foundation) surface; later phases add their own keys.
 *
 * @param enabled master switch; when false the manager starts nothing and the server is stock
 * @param populationSize the target number of cold bots the world holds (operator-adjustable)
 * @param resolveIntervalMs how often the cold resolver ticks, in milliseconds
 * @param resolveBatch the maximum number of due bots advanced per resolver tick
 * @param expPerMobLevel representative experience a level-appropriate mob gives, per mob level (a cold bot's per-kill experience is roughly {@code level * expPerMobLevel})
 * @param killsPerMinute how many level-appropriate mobs a cold bot clears per minute (time to kill plus recovery)
 * @param maxLevel the level at which cold progression stops (further clamped to the server's max level)
 * @param directorEnabled whether the population director adjusts leveling toward the online players' level
 * @param directorBandRadius levels within this distance of the target level are unaffected
 * @param directorMaxCatchUp the cap on the catch-up experience multiplier for bots below the band
 * @param directorSlowdown the experience multiplier applied to bots above the band
 * @param directorCatchUpSlope how sharply the catch-up multiplier grows per level below the band
 * @param handoffEnabled whether the Phase 3 hot/cold handoff materializes bots near real players (module still gated by {@link #enabled()})
 * @param handoffIntervalMs how often the handoff scan runs, in milliseconds (proximity responsiveness)
 * @param handoffActivationRadius how close (game units, planar) a real player must be to a cold bot for it to go hot
 * @param handoffDeactivationRadius the farther radius a player must leave before a hot bot is eligible to cool (hysteresis; must be >= activation)
 * @param handoffCooldownGraceMs how long no real player may be within the deactivation radius before a hot bot cools back
 * @param handoffMaxHotBots the ceiling on how many bots may be hot at once (0 or less: the population size)
 * @param economyEnabled whether the Phase 4 cold goal/needs economy advances (adena, soulshots, gear tier, goals)
 * @param adenaPerMobLevel adena a level-appropriate kill yields, per mob level
 * @param soulshotMilestoneLevel the level at which the one-time newbie soulshot reward is granted
 * @param soulshotMilestoneGrant how many soulshots that reward grants
 * @param soulshotsPerKill how many soulshots a kill consumes
 * @param soulshotRestockThreshold restock when the soulshot count falls below this
 * @param soulshotRestockBatch how many soulshots a restock buys
 * @param soulshotCost adena per soulshot bought
 * @param potionRestockThreshold restock when the healing-potion count falls below this
 * @param potionRestockBatch how many potions a restock buys
 * @param potionCost adena per potion bought
 * @param gearTierLevelStep levels per unlocked gear tier (0 disables upgrades)
 * @param gearUpgradeCost base adena cost of a tier upgrade
 * @param snapshotIntervalMs how often the monitoring JSON snapshot is written, in milliseconds
 * @param snapshotFile the path the monitoring JSON snapshot is written to, relative to the server working directory
 * @param birthBatch the fewest new bots born per wave while the population grows (0 or less: all at once); larger
 *            populations get bigger waves, see {@link #birthWave}
 * @param birthIntervalMs the time between waves of new bots, in milliseconds
 * @param levelGoal the level the director pulls the population toward (0 or less: the online players' level)
 */
public record LivingPopulationConfig(boolean enabled, int populationSize, long resolveIntervalMs, int resolveBatch, double expPerMobLevel, double killsPerMinute, int maxLevel, boolean directorEnabled, int directorBandRadius, double directorMaxCatchUp, double directorSlowdown, double directorCatchUpSlope, boolean handoffEnabled, long handoffIntervalMs, double handoffActivationRadius, double handoffDeactivationRadius, long handoffCooldownGraceMs, int handoffMaxHotBots, boolean economyEnabled, double adenaPerMobLevel, int soulshotMilestoneLevel, long soulshotMilestoneGrant, double soulshotsPerKill, long soulshotRestockThreshold, long soulshotRestockBatch, int soulshotCost, long potionRestockThreshold, long potionRestockBatch, int potionCost, int gearTierLevelStep, long gearUpgradeCost, long snapshotIntervalMs, String snapshotFile, int birthBatch, long birthIntervalMs, int levelGoal)
{
	/** Waves of new bots grow with the population so that every bot is born within about this long. */
	public static final long BIRTH_SPREAD_MS = 3L * 60L * 60L * 1000L;

	/**
	 * How many bots one wave of births creates. The configured batch is the floor; a large population gets bigger waves
	 * so that the whole population still arrives within about {@link #BIRTH_SPREAD_MS} instead of a day or more.
	 * @param batch the configured batch (0 or less: everything missing at once)
	 * @param intervalMs the time between waves, in milliseconds
	 * @param target the population size aimed for
	 * @param missing how many bots are still missing
	 * @return the number of bots to create now (never more than missing)
	 */
	public static int birthWave(int batch, long intervalMs, int target, int missing)
	{
		if (missing <= 0)
		{
			return 0;
		}
		if (batch <= 0)
		{
			return missing;
		}
		final long waves = Math.max(1L, BIRTH_SPREAD_MS / Math.max(1L, intervalMs));
		final long scaled = (Math.max(0L, target) + waves - 1L) / waves;
		return (int) Math.min(missing, Math.max(batch, scaled));
	}

	/** @return how many bots may be hot at once: the configured ceiling, or the population size when it is 0 or less */
	public int effectiveMaxHotBots()
	{
		return (handoffMaxHotBots > 0) ? handoffMaxHotBots : Math.max(0, populationSize);
	}

	/** A conservative default used when the module ships with no ini present. */
	public static LivingPopulationConfig defaults()
	{
		return new LivingPopulationConfig(false, 100, 30_000L, 64, 13.0, 12.0, 80, true, 2, 1.35, 0.85, 0.06, true, 5_000L, 3000.0, 4000.0, 30_000L, 0, true, 5.0, 6, 1000L, 6.0, 500L, 1000L, 12, 100L, 500L, 60, 10, 50_000L, 5_000L, "log/LivingPopulation.json", 10, 1_200_000L, 0);
	}

	/** @return the director tuning as a {@link PopulationDirector.Params} */
	public PopulationDirector.Params directorParams()
	{
		return new PopulationDirector.Params(directorBandRadius, directorMaxCatchUp, directorSlowdown, directorCatchUpSlope);
	}

	/** @return the handoff tuning as a {@link HandoffPolicy.Params} (deactivation is clamped to at least activation) */
	public HandoffPolicy.Params handoffParams()
	{
		return new HandoffPolicy.Params(handoffActivationRadius, Math.max(handoffActivationRadius, handoffDeactivationRadius), handoffCooldownGraceMs, effectiveMaxHotBots());
	}

	/** @return the economy tuning as a {@link ColdEconomy.Params} (shares the exp model's killsPerMinute) */
	public ColdEconomy.Params economyParams()
	{
		return new ColdEconomy.Params(adenaPerMobLevel, killsPerMinute, soulshotMilestoneLevel, soulshotMilestoneGrant, soulshotsPerKill, soulshotRestockThreshold, soulshotRestockBatch, soulshotCost, potionRestockThreshold, potionRestockBatch, potionCost, gearTierLevelStep, gearUpgradeCost);
	}
}
