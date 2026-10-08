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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses what a hunting bot does next, the way L2Solo's needs evaluator does: every possible goal is listed as a
 * candidate with a priority and, when it cannot be done yet, a blocker; the highest-priority candidate without a blocker
 * wins. The losing and blocked candidates are kept so the decision log can say what was considered and why.
 *
 * <p>Goals: go to town to restock or upgrade ({@link Goal#TOWN}), move to a zone that fits the bot's level
 * ({@link Goal#RELOCATE}, which also passes through town), or keep hunting ({@link Goal#HUNT}). Pure.
 */
public final class GoalPlanner
{
	private GoalPlanner()
	{
	}

	/** What a bot can decide to do. */
	public enum Goal
	{
		HUNT,
		TOWN,
		RELOCATE
	}

	/**
	 * One option the planner weighed.
	 * @param goal the goal
	 * @param priority higher wins
	 * @param reason why this option came up, in plain words
	 * @param blocker why it cannot be done yet, or null when it can
	 * @param key a stable key for the situation (for once-per-situation logging)
	 */
	public record Candidate(Goal goal, int priority, String reason, String blocker, String key)
	{
		/** @return whether this option can be acted on now */
		public boolean available()
		{
			return blocker == null;
		}
	}

	/**
	 * The planner's result.
	 * @param chosen the winning option
	 * @param considered every option, highest priority first (includes the chosen one)
	 */
	public record Plan(Candidate chosen, List<Candidate> considered)
	{
	}

	/**
	 * The bot as the planner sees it.
	 * @param level level
	 * @param adena adena
	 * @param potions potions carried
	 * @param soulshots soulshots carried
	 * @param escapes Scrolls of Escape carried
	 * @param gearTier gear tier
	 * @param rewardClaimed whether it uses soulshots yet
	 * @param zoneName the zone it hunts in, or null
	 * @param zoneMin that zone's lowest level (ignored when zoneName is null)
	 * @param zoneMax that zone's highest level (ignored when zoneName is null)
	 * @param betterZone the name of a zone that fits better and that it can reach, or null when there is none
	 * @param classErrand why it needs a class master (to take or to finish its class-change quest), or null
	 * @param classBlocker why it cannot go yet, or null
	 * @param skillErrand why it wants to see its trainer (skills waiting to be learned), or null
	 * @param skillBlocker why it cannot go yet, or null
	 * @param gearErrand a gear upgrade worth a trip to town (gear kept per slot), or null
	 * @param gearBlocker why it cannot go yet, or null
	 * @param gearKey a stable key for that upgrade, for once-per-situation logging
	 */
	public record View(int level, long adena, long potions, long soulshots, long escapes, int gearTier, boolean rewardClaimed, String zoneName, int zoneMin, int zoneMax, String betterZone, String classErrand, String classBlocker, String skillErrand, String skillBlocker, String gearErrand, String gearBlocker, String gearKey)
	{
		public View(int level, long adena, long potions, long soulshots, long escapes, int gearTier, boolean rewardClaimed, String zoneName, int zoneMin, int zoneMax, String betterZone, String classErrand, String classBlocker, String skillErrand, String skillBlocker)
		{
			this(level, adena, potions, soulshots, escapes, gearTier, rewardClaimed, zoneName, zoneMin, zoneMax, betterZone, classErrand, classBlocker, skillErrand, skillBlocker, null, null, null);
		}

		public View(int level, long adena, long potions, long soulshots, long escapes, int gearTier, boolean rewardClaimed, String zoneName, int zoneMin, int zoneMax, String betterZone, String classErrand, String classBlocker)
		{
			this(level, adena, potions, soulshots, escapes, gearTier, rewardClaimed, zoneName, zoneMin, zoneMax, betterZone, classErrand, classBlocker, null, null);
		}

		public View(int level, long adena, long potions, long soulshots, long escapes, int gearTier, boolean rewardClaimed, String zoneName, int zoneMin, int zoneMax, String betterZone)
		{
			this(level, adena, potions, soulshots, escapes, gearTier, rewardClaimed, zoneName, zoneMin, zoneMax, betterZone, null, null, null, null);
		}
	}

	/**
	 * Weighs the options and picks one.
	 * @param view the bot
	 * @param prices unit prices for its level
	 * @param params supply tuning
	 * @return the plan
	 */
	public static Plan plan(View view, SupplyPlanner.Prices prices, SupplyPlanner.Params params)
	{
		final List<Candidate> candidates = new ArrayList<>();
		final long reserve = SupplyPlanner.reserve(view.level(), view.adena(), params);
		final long spendable = SupplyPlanner.spendable(view.adena(), reserve);

		// What a town visit would actually buy right now, in the shop's own order (scrolls, potions, gear, soulshots). A trip
		// for supplies is only worth it when it brings back a proper amount: a player does not break off hunting to buy a
		// handful of potions or twenty soulshots, and keeps hunting to earn more instead.
		final SupplyPlanner.Purchase haul = SupplyPlanner.shop(view.level(), view.adena(), view.escapes(), view.potions(), view.soulshots(), view.gearTier(), view.rewardClaimed(), prices, params);

		// Potions: survival first. Worth a trip when it brings back at least half of what is missing.
		if (SupplyPlanner.potionsLow(view.potions(), params))
		{
			final String reason = "Potions low (" + DecisionLog.num(view.potions()) + " of " + params.potionStock() + ")";
			final long missing = Math.max(0L, params.potionStock() - view.potions());
			final long enough = Math.max(1L, (missing + 1) / 2);
			candidates.add(new Candidate(Goal.TOWN, (view.potions() == 0) ? 80 : 70, reason, (haul.potions() >= enough) ? null : "it could only buy " + DecisionLog.num(haul.potions()) + " of the " + DecisionLog.num(missing) + " it needs (" + DecisionLog.num(prices.potion()) + " each, and it keeps " + DecisionLog.num(reserve) + " of its " + DecisionLog.num(view.adena()) + " adena in reserve)", "potions"));
		}

		// Class change: taking the quest from a class master, or handing it in. A milestone players drop everything for.
		if (view.classErrand() != null)
		{
			candidates.add(new Candidate(Goal.TOWN, 78, view.classErrand(), view.classBlocker(), "class"));
		}

		// Skills: a batch of new skills waiting at the trainer is worth a trip, below supplies and gear.
		if (view.skillErrand() != null)
		{
			candidates.add(new Candidate(Goal.TOWN, 50, view.skillErrand(), view.skillBlocker(), "skills"));
		}

		// Gear: an unlocked, affordable tier outranks topping up soulshots.
		if (view.gearTier() < SupplyPlanner.tierCeiling(view.level(), params))
		{
			final long price = SupplyPlanner.nextTierPrice(view.gearTier(), prices, params);
			final String reason = "Gear tier " + (view.gearTier() + 1) + " unlocked at level " + view.level();
			candidates.add(new Candidate(Goal.TOWN, 75, reason, (spendable >= price) ? null : "it costs " + DecisionLog.num(price) + " and it can spend " + DecisionLog.num(spendable), "gear-" + (view.gearTier() + 1)));
		}

		// Gear kept per slot: a good upgrade it can afford is worth the trip, the same as a whole gear tier.
		if (view.gearErrand() != null)
		{
			candidates.add(new Candidate(Goal.TOWN, 75, view.gearErrand(), view.gearBlocker(), (view.gearKey() == null) ? "gear" : view.gearKey()));
		}

		// Soulshots: only once the bot uses them, and only for a real batch (at least half its usual stock). Without one it
		// keeps hunting, without shots if it runs out, and tops them up whenever it is in town for something else.
		if (SupplyPlanner.soulshotsLow(view.soulshots(), view.rewardClaimed(), params))
		{
			final long target = SupplyPlanner.soulshotTarget(view.rewardClaimed(), params);
			final long batch = Math.max(1L, target / 2);
			final String reason = "Soulshots low (" + DecisionLog.num(view.soulshots()) + " of " + DecisionLog.num(target) + ")";
			candidates.add(new Candidate(Goal.TOWN, 60, reason, (haul.soulshots() >= batch) ? null : "it could only buy " + DecisionLog.num(haul.soulshots()) + " and a trip is worth it from " + DecisionLog.num(batch) + " (" + DecisionLog.num(prices.soulshot()) + " each, it can spend " + DecisionLog.num(spendable) + ")", "soulshots"));
		}

		// Zone fit: too weak for the zone is urgent; outleveled means it is time to move on.
		if (view.zoneName() != null)
		{
			if (view.level() < view.zoneMin())
			{
				candidates.add(new Candidate(Goal.RELOCATE, 85, "Too low for " + view.zoneName() + " (level " + view.level() + ", zone " + view.zoneMin() + " to " + view.zoneMax() + ")", (view.betterZone() != null) ? null : "no better zone it can reach", "weak-" + view.zoneName()));
			}
			else if (view.level() > (view.zoneMax() + ZoneChooser.OUTLEVEL_SLACK))
			{
				candidates.add(new Candidate(Goal.RELOCATE, 65, "Outleveled " + view.zoneName() + " (level " + view.level() + ", zone " + view.zoneMin() + " to " + view.zoneMax() + ")", (view.betterZone() != null) ? null : "no better zone it can reach yet", "outleveled-" + view.zoneName()));
			}
		}
		else if (view.betterZone() != null)
		{
			candidates.add(new Candidate(Goal.RELOCATE, 65, "Has no hunting zone yet", null, "no-zone"));
		}

		candidates.add(new Candidate(Goal.HUNT, 35, "Hunting to level up and earn adena", null, "hunt"));
		candidates.sort(Comparator.comparingInt(Candidate::priority).reversed());

		Candidate chosen = candidates.get(candidates.size() - 1);
		for (Candidate candidate : candidates)
		{
			if (candidate.available())
			{
				chosen = candidate;
				break;
			}
		}
		return new Plan(chosen, List.copyOf(candidates));
	}

	/**
	 * Describes a plan for the decision log: the choice and, briefly, anything it had to put off.
	 * @param plan the plan
	 * @return the text
	 */
	public static String describe(Plan plan)
	{
		final StringBuilder sb = new StringBuilder();
		final Candidate chosen = plan.chosen();
		switch (chosen.goal())
		{
			case TOWN:
			{
				sb.append("Decided to go to town: ").append(chosen.reason());
				break;
			}
			case RELOCATE:
			{
				sb.append("Decided to change zones: ").append(chosen.reason());
				break;
			}
			default:
			{
				sb.append("Keeps hunting");
				break;
			}
		}
		final List<String> putOff = new ArrayList<>();
		for (Candidate candidate : plan.considered())
		{
			if (!candidate.available())
			{
				putOff.add(candidate.reason() + " but " + candidate.blocker());
			}
		}
		if (!putOff.isEmpty())
		{
			sb.append(". Put off: ").append(String.join("; ", putOff));
		}
		return sb.toString();
	}
}
