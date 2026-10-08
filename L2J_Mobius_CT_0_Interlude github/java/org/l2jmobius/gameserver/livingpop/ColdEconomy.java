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

import java.util.List;

/**
 * The pure Phase 4 cold economy: how a bot's abstract goal state advances over an elapsed span while no one is watching.
 * Like {@link ColdProgression} for experience, this is a deterministic function of the inputs with no game types, so its
 * behavior is locked down in the standalone test lane and the manager stays a thin caller.
 *
 * <p>Each resolve span it: accrues adena as if the bot sold loot while hunting; grants the one-time newbie soulshot reward
 * once the bot reaches the milestone level; consumes soulshots as if firing them at mobs; then acts on the single most
 * important {@link NeedsEvaluator.Need} - restocking soulshots or upgrading a gear tier when the bot can afford it, else
 * hunting. Restock and upgrade are instantaneous abstract transactions here; the <i>visible</i> town versions are Phase 6.
 *
 * <p>Only cold bots are advanced by this. A hot bot's economy is driven by its real phantom (which collects adena drops and
 * fires its own soulshot stack); the handoff seeds the character's adena from the row and captures it back on cooldown.
 */
public final class ColdEconomy
{
	// The game stores a character's adena as an int, so a hot bot's purse cannot exceed this. Cap the cold row at the
	// same ceiling: it keeps the handoff seed/capture lossless (a long row that overflowed int would be truncated to
	// garbage when captured back through Player.getAdena()), and it matches the in-game adena limit.
	public static final long MAX_ADENA = Integer.MAX_VALUE;

	private ColdEconomy()
	{
	}

	/**
	 * Economy tuning, injected from the module config. Kept here in the pure layer so {@link LivingPopulationConfig} can
	 * build it without a game dependency.
	 * @param adenaPerMobLevel adena a level-appropriate kill yields, per mob level (adena/min = level * this * killsPerMinute)
	 * @param killsPerMinute how many level-appropriate mobs the bot clears per minute (shared with the exp model)
	 * @param soulshotMilestoneLevel the level at which the one-time newbie soulshot reward is granted
	 * @param soulshotMilestoneGrant how many soulshots that reward grants
	 * @param soulshotsPerKill how many soulshots a kill consumes (consumption/min = killsPerMinute * this)
	 * @param soulshotRestockThreshold restock when the soulshot count falls below this
	 * @param soulshotRestockBatch how many soulshots a restock buys
	 * @param soulshotCost adena per soulshot bought
	 * @param potionRestockThreshold restock when the healing-potion count falls below this
	 * @param potionRestockBatch how many potions a restock buys
	 * @param potionCost adena per potion bought
	 * @param gearTierLevelStep levels per unlocked gear tier (tier ceiling = level / this); 0 disables upgrades
	 * @param gearUpgradeCost base adena cost of a tier upgrade (the next tier costs this * (tier + 1))
	 */
	public record Params(double adenaPerMobLevel, double killsPerMinute, int soulshotMilestoneLevel, long soulshotMilestoneGrant, double soulshotsPerKill, long soulshotRestockThreshold, long soulshotRestockBatch, int soulshotCost, long potionRestockThreshold, long potionRestockBatch, int potionCost, int gearTierLevelStep, long gearUpgradeCost)
	{
	}

	/**
	 * A bot's economic state, in and out of {@link #resolve(State, int, long, Params)}.
	 * @param adena current adena
	 * @param soulshots current soulshot count
	 * @param potions current healing-potion count
	 * @param gearTier current gear tier (0 = starting gear)
	 * @param rewardClaimed whether the one-time newbie soulshot reward has been granted
	 * @param goal the resulting high-level goal label ({@code hunting}/{@code restock}/{@code upgrade})
	 */
	public record State(long adena, long soulshots, long potions, int gearTier, boolean rewardClaimed, String goal)
	{
	}

	/**
	 * Advances the economy over an elapsed span. Pure: it returns a new {@link State} and mutates nothing.
	 *
	 * <p>Adena and soulshot amounts are computed at the post-progression {@code level} for the whole span rather than
	 * integrated level-by-level as experience is. This is a deliberate, bounded simplification: the manager anchors the
	 * resolve clock so a span is at most one resolve interval, so the only case it differs is a bot that gains several
	 * levels within a single tick (low levels only), where the extra credit is one tick's worth and self-correcting.
	 * @param state the current economic state
	 * @param level the bot's level for this span (already advanced by {@link ColdProgression})
	 * @param elapsedMs the elapsed time in milliseconds (negative or zero yields no accrual/consumption)
	 * @param params the economy tuning
	 * @return the advanced state
	 */
	public static State resolve(State state, int level, long elapsedMs, Params params)
	{
		return resolve(state, level, elapsedMs, params, null);
	}

	/**
	 * Same as {@link #resolve(State, int, long, Params)}, and also reports each decision it makes, in plain words, for the
	 * bot's {@link DecisionLog}: the newbie reward, each purchase with its reason, and any need it has to put off because
	 * the bot cannot afford it yet (keyed, so the caller logs it once per situation rather than every tick).
	 * @param state the current economic state
	 * @param level the bot's level for this span
	 * @param elapsedMs the elapsed time in milliseconds
	 * @param params the economy tuning
	 * @param events receives the decisions made; may be null when the caller does not log
	 * @return the advanced state
	 */
	public static State resolve(State state, int level, long elapsedMs, Params params, List<DecisionLog.Event> events)
	{
		final double minutes = Math.max(0L, elapsedMs) / 60_000.0;

		long adena = state.adena();
		long soulshots = state.soulshots();
		long potions = state.potions();
		int gearTier = state.gearTier();
		boolean rewardClaimed = state.rewardClaimed();

		// Accrue adena as if selling loot while hunting.
		adena += Math.round(Math.max(0.0, level * params.adenaPerMobLevel() * params.killsPerMinute() * minutes));

		// One-time newbie soulshot reward at the milestone level.
		if (!rewardClaimed && (level >= params.soulshotMilestoneLevel()))
		{
			soulshots += Math.max(0L, params.soulshotMilestoneGrant());
			rewardClaimed = true;
			report(events, null, "Received the newbie reward at level " + level + ": " + DecisionLog.num(params.soulshotMilestoneGrant()) + " soulshots");
		}

		// Consume soulshots as if firing them at mobs. Potions are not consumed in cold (abstract hunting takes no real
		// damage); a hot bot drinks real ones and the handoff captures the remainder back, then cold refills below.
		soulshots = Math.max(0L, soulshots - Math.round(Math.max(0.0, params.killsPerMinute() * params.soulshotsPerKill() * minutes)));

		// Act on the single most important need.
		final String goal;
		switch (NeedsEvaluator.evaluate(level, adena, soulshots, potions, gearTier, params))
		{
			case RESTOCK:
			{
				// Buy whichever consumables are low and affordable this tick (both may be bought).
				if ((soulshots < params.soulshotRestockThreshold()) && (adena >= ((long) params.soulshotRestockBatch() * params.soulshotCost())))
				{
					final long price = (long) params.soulshotRestockBatch() * params.soulshotCost();
					report(events, null, "Restock: soulshots " + DecisionLog.num(soulshots) + " below " + DecisionLog.num(params.soulshotRestockThreshold()) + ". Bought " + DecisionLog.num(params.soulshotRestockBatch()) + " for " + DecisionLog.num(price) + " adena");
					adena -= price;
					soulshots += Math.max(0L, params.soulshotRestockBatch());
				}
				if ((potions < params.potionRestockThreshold()) && (adena >= ((long) params.potionRestockBatch() * params.potionCost())))
				{
					final long price = (long) params.potionRestockBatch() * params.potionCost();
					report(events, null, "Restock: potions " + DecisionLog.num(potions) + " below " + DecisionLog.num(params.potionRestockThreshold()) + ". Bought " + DecisionLog.num(params.potionRestockBatch()) + " for " + DecisionLog.num(price) + " adena");
					adena -= price;
					potions += Math.max(0L, params.potionRestockBatch());
				}
				goal = "restock";
				break;
			}
			case UPGRADE:
			{
				final long price = params.gearUpgradeCost() * (gearTier + 1L);
				report(events, null, "Upgrade: level " + level + " unlocks gear tier " + (gearTier + 1) + ". Bought it for " + DecisionLog.num(price) + " adena");
				adena -= price;
				gearTier += 1;
				goal = "upgrade";
				break;
			}
			default:
			{
				goal = "hunting";
				break;
			}
		}

		// Needs the bot has but cannot afford yet. Checked on the final state, so a need met by a purchase above is not
		// reported. Keyed: the caller logs each once when it starts, not every tick while it lasts.
		if (events != null)
		{
			final long shotPrice = (long) params.soulshotRestockBatch() * params.soulshotCost();
			if ((soulshots < params.soulshotRestockThreshold()) && (adena < shotPrice))
			{
				report(events, "wait-soulshots", "Soulshots low (" + DecisionLog.num(soulshots) + " below " + DecisionLog.num(params.soulshotRestockThreshold()) + ") but a batch costs " + DecisionLog.num(shotPrice) + " adena and it has " + DecisionLog.num(adena) + ". Keeps hunting to earn it");
			}
			final long potionPrice = (long) params.potionRestockBatch() * params.potionCost();
			if ((potions < params.potionRestockThreshold()) && (adena < potionPrice))
			{
				report(events, "wait-potions", "Potions low (" + DecisionLog.num(potions) + " below " + DecisionLog.num(params.potionRestockThreshold()) + ") but a batch costs " + DecisionLog.num(potionPrice) + " adena and it has " + DecisionLog.num(adena) + ". Keeps hunting to earn it");
			}
			final int tierCeiling = (params.gearTierLevelStep() > 0) ? (level / params.gearTierLevelStep()) : 0;
			final long gearPrice = params.gearUpgradeCost() * (gearTier + 1L);
			if ((gearTier < tierCeiling) && (adena < gearPrice))
			{
				report(events, "wait-gear-" + (gearTier + 1), "Gear tier " + (gearTier + 1) + " is unlocked but costs " + DecisionLog.num(gearPrice) + " adena and it has " + DecisionLog.num(adena) + ". Saving up");
			}
		}

		return new State(Math.min(MAX_ADENA, Math.max(0L, adena)), Math.max(0L, soulshots), Math.max(0L, potions), gearTier, rewardClaimed, goal);
	}

	/**
	 * Phase 5: only the hunting half of the economy - adena earned, the one-time newbie soulshot reward, and soulshots
	 * fired. Buying happens in town ({@link ColdLife}), so nothing is bought here. Pure.
	 * @param state the current economic state
	 * @param level the bot's level for this span
	 * @param elapsedMs the elapsed hunting time in milliseconds
	 * @param params the economy tuning (only the earning and firing values are used)
	 * @param events receives the reward decision; may be null
	 * @return the advanced state (goal unchanged)
	 */
	public static State accrue(State state, int level, long elapsedMs, Params params, List<DecisionLog.Event> events)
	{
		return accrue(state, level, elapsedMs, params, -1.0, events);
	}

	/**
	 * {@link #accrue(State, int, long, Params, List)} with the adena a kill drops taken from the zone's real drop lists
	 * ({@link DropYield}) instead of the flat per-level formula.
	 * @param state the current economic state
	 * @param level the bot's level for this span
	 * @param elapsedMs the elapsed hunting time in milliseconds
	 * @param params the economy tuning
	 * @param adenaPerKill adena one kill drops; negative uses {@code level * adenaPerMobLevel}
	 * @param events receives the reward decision; may be null
	 * @return the advanced state (goal unchanged)
	 */
	public static State accrue(State state, int level, long elapsedMs, Params params, double adenaPerKill, List<DecisionLog.Event> events)
	{
		final double minutes = Math.max(0L, elapsedMs) / 60_000.0;
		final double perKill = (adenaPerKill >= 0) ? adenaPerKill : (level * params.adenaPerMobLevel());
		long adena = state.adena() + Math.round(Math.max(0.0, perKill * params.killsPerMinute() * minutes));
		long soulshots = state.soulshots();
		boolean rewardClaimed = state.rewardClaimed();
		if (!rewardClaimed && (level >= params.soulshotMilestoneLevel()))
		{
			soulshots += Math.max(0L, params.soulshotMilestoneGrant());
			rewardClaimed = true;
			report(events, null, "Received the newbie reward at level " + level + ": " + DecisionLog.num(params.soulshotMilestoneGrant()) + " soulshots");
		}
		soulshots = Math.max(0L, soulshots - Math.round(Math.max(0.0, params.killsPerMinute() * params.soulshotsPerKill() * minutes)));
		return new State(Math.min(MAX_ADENA, Math.max(0L, adena)), soulshots, state.potions(), state.gearTier(), rewardClaimed, state.goal());
	}

	private static void report(List<DecisionLog.Event> events, String key, String text)
	{
		if (events != null)
		{
			events.add(new DecisionLog.Event(key, text));
		}
	}
}
