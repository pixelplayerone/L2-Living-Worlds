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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntToLongFunction;

import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Town;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Zone;

/**
 * Phase 5: a cold bot's day, beyond leveling. While hunting it weighs its needs ({@link GoalPlanner}); when it decides to
 * go to town it uses a Scroll of Escape, or walks when it has none; in town it spends as long as its errands take (and now
 * and then goes AFK), shops ({@link SupplyPlanner}), then picks its next zone ({@link ZoneChooser}) and pays the gatekeeper
 * or walks there. Every step is logged in plain words.
 *
 * <p>Pure: it mutates only the {@link ColdBot} it is given and reads the world through {@link Context}. The manager calls
 * {@link #advance} for cold bots only; experience, adena and soulshot use accrue only while the bot is hunting.
 */
public final class ColdLife
{
	/** Activity labels stored on the row. */
	public static final String HUNTING = "hunting";
	public static final String ESCAPING = "escaping";
	public static final String WALKING_TO_TOWN = "walking_to_town";
	public static final String IN_TOWN = "in_town";
	public static final String AFK = "afk";
	public static final String TO_ZONE = "to_zone";
	public static final String RESTING = "resting";
	public static final String DEAD = "dead";
	public static final String CLASS_MASTER = "class_master";
	public static final String TO_TOWN = "to_town";
	public static final String TRAINER = "trainer";

	/** Skill levels waiting at the trainer that make a trip of their own worthwhile. */
	public static final int SKILL_TRIP_MIN = 3;
	/** Loot sold for less than this is still credited, but not written to the decision log. */
	public static final long LOOT_LOG_MIN = 100L;

	private ColdLife()
	{
	}

	/**
	 * Travel tuning, from the module config.
	 * @param moveSpeed walking speed in game units per second
	 * @param escapeCastMs how long a Scroll of Escape cast takes
	 * @param errandStopMs time spent at each shop or NPC
	 * @param afkChancePercent chance, per town visit, of an AFK break
	 * @param afkMinMs shortest AFK break
	 * @param afkMaxMs longest AFK break
	 * @param potionsPerHour potions drunk per hour of hunting while cold
	 * @param zoneCapacity bots per zone before it counts as full (0 = no limit)
	 * @param retryMs how long a bot that could not find an affordable way on waits before trying again
	 * @param escapeMinDistance a town closer than this is walked to even with a Scroll of Escape in the bag
	 * @param spiritshotsPerKill spiritshots a mystic fires per kill (fighters use the soulshot rate of the supply tuning)
	 */
	public record Params(double moveSpeed, long escapeCastMs, long errandStopMs, int afkChancePercent, long afkMinMs, long afkMaxMs, double potionsPerHour, int zoneCapacity, long retryMs, double escapeMinDistance, double spiritshotsPerKill)
	{
		/** The default spiritshot use: a caster fires one per spell, about two spells a kill. */
		public static final double DEFAULT_SPIRITSHOTS_PER_KILL = 2.0;

		public Params(double moveSpeed, long escapeCastMs, long errandStopMs, int afkChancePercent, long afkMinMs, long afkMaxMs, double potionsPerHour, int zoneCapacity, long retryMs, double escapeMinDistance)
		{
			this(moveSpeed, escapeCastMs, errandStopMs, afkChancePercent, afkMinMs, afkMaxMs, potionsPerHour, zoneCapacity, retryMs, escapeMinDistance, DEFAULT_SPIRITSHOTS_PER_KILL);
		}
	}

	/** Unit prices for a bot's level and gear, read from the real item data by the manager. */
	public interface PriceBook
	{
		/**
		 * @param level bot level
		 * @param gearTier bot gear tier (fixes the soulshot grade)
		 * @return the prices
		 */
		SupplyPlanner.Prices prices(int level, int gearTier);

		/**
		 * @param level bot level
		 * @param gearTier bot gear tier (fixes the shot grade)
		 * @param classId bot class (a mystic buys spiritshots instead of soulshots)
		 * @return the prices
		 */
		default SupplyPlanner.Prices prices(int level, int gearTier, int classId)
		{
			return prices(level, gearTier);
		}
	}

	/** Each class's skill tree, read from the real skill data by the manager. */
	public interface SkillBook
	{
		/**
		 * @param classId a class
		 * @return its complete skill tree (its own, its parents' and the common skills), trainer-taught and free ones
		 */
		List<SkillPlanner.Entry> tree(int classId);
	}

	/** Gear kept per slot: the item data, what each class wears and what each town sells, read by the manager. */
	public interface GearShop
	{
		/** @return item facts */
		LivingGear.Items items();

		/**
		 * @param classId a class
		 * @return what it wears
		 */
		LivingGear.Fit fit(int classId);

		/**
		 * @param town a town
		 * @return what a bot can buy there
		 */
		List<LivingGear.Offer> offers(Town town);

		/**
		 * @param itemId an item
		 * @return whether a bot may wear it when it drops
		 */
		boolean wearable(int itemId);

		/** @return levels per gear tier (the tier then follows the weapon's grade) */
		int tierStep();
	}

	/**
	 * What the life step reads.
	 * @param catalog towns and zones
	 * @param supply supply tuning
	 * @param travel travel tuning
	 * @param priceBook prices
	 * @param occupancy bots per zone (the manager counts them once per tick)
	 * @param random randomness
	 * @param risk death and rest tuning, or null for bots that never die or rest
	 * @param expToNext experience from a level to the next (for the death penalty)
	 * @param classQuestMs how long the quest hunt of the first, second and third class change takes, or null for bots
	 *            that never change class
	 * @param skills the skill trees, or null for bots that do not track their skills (a hot one then learns everything)
	 * @param gear gear kept per slot, or null for bots that buy whole gear tiers
	 * @param combat the zone combat model for kill and death rates, or null for the flat rates
	 */
	public record Context(ZoneCatalog catalog, SupplyPlanner.Params supply, Params travel, PriceBook priceBook, Map<String, Integer> occupancy, Random random, ColdRisk.Params risk, IntToLongFunction expToNext, long[] classQuestMs, SkillBook skills, GearShop gear, ZoneCombat combat)
	{
		public Context(ZoneCatalog catalog, SupplyPlanner.Params supply, Params travel, PriceBook priceBook, Map<String, Integer> occupancy, Random random, ColdRisk.Params risk, IntToLongFunction expToNext, long[] classQuestMs, SkillBook skills, GearShop gear)
		{
			this(catalog, supply, travel, priceBook, occupancy, random, risk, expToNext, classQuestMs, skills, gear, null);
		}

		public Context(ZoneCatalog catalog, SupplyPlanner.Params supply, Params travel, PriceBook priceBook, Map<String, Integer> occupancy, Random random, ColdRisk.Params risk, IntToLongFunction expToNext, long[] classQuestMs, SkillBook skills)
		{
			this(catalog, supply, travel, priceBook, occupancy, random, risk, expToNext, classQuestMs, skills, null);
		}

		public Context(ZoneCatalog catalog, SupplyPlanner.Params supply, Params travel, PriceBook priceBook, Map<String, Integer> occupancy, Random random, ColdRisk.Params risk, IntToLongFunction expToNext, long[] classQuestMs)
		{
			this(catalog, supply, travel, priceBook, occupancy, random, risk, expToNext, classQuestMs, null);
		}

		public Context(ZoneCatalog catalog, SupplyPlanner.Params supply, Params travel, PriceBook priceBook, Map<String, Integer> occupancy, Random random, ColdRisk.Params risk, IntToLongFunction expToNext)
		{
			this(catalog, supply, travel, priceBook, occupancy, random, risk, expToNext, null);
		}

		public Context(ZoneCatalog catalog, SupplyPlanner.Params supply, Params travel, PriceBook priceBook, Map<String, Integer> occupancy, Random random)
		{
			this(catalog, supply, travel, priceBook, occupancy, random, null, level -> 0L);
		}
	}

	/**
	 * @param activity an activity label
	 * @return whether the bot is hunting (so experience, adena and soulshot use accrue)
	 */
	public static boolean isHunting(String activity)
	{
		return (activity == null) || HUNTING.equals(activity);
	}

	/**
	 * Gives a bot without a zone its starting zone when that needs no travel: newbies hunt in their race's newbie grounds
	 * where they were born. Used for new bots and for bots created before Phase 5.
	 * @param bot the bot
	 * @param catalog the catalog
	 * @return whether a zone was assigned
	 */
	public static boolean adoptStartingZone(ColdBot bot, ZoneCatalog catalog)
	{
		if (bot.getZone() != null)
		{
			return false;
		}
		for (Zone zone : catalog.zones())
		{
			if (zone.isStarter() && zone.starterRace().equals(bot.getRace()) && (bot.getLevel() <= zone.maxLevel()))
			{
				bot.setZone(zone.name());
				return true;
			}
		}
		return false;
	}

	/**
	 * Advances a cold bot's travel, town visit, or hunting decisions.
	 * @param bot the bot (cold, not hot-locked)
	 * @param now the time
	 * @param elapsedMs time since its last resolve
	 * @param context the world
	 * @param events receives the decisions made, for the decision log
	 */
	public static void advance(ColdBot bot, long now, long elapsedMs, Context context, List<DecisionLog.Event> events)
	{
		trackSkills(bot, context, events);
		final String activity = bot.getActivity();
		if (isHunting(activity))
		{
			hunt(bot, now, elapsedMs, context, events);
			return;
		}

		final TravelLeg leg = bot.getLeg();
		if (leg == null)
		{
			// A step without its timing (for example a hand-edited row): resume hunting rather than freeze.
			bot.setActivity(HUNTING);
			return;
		}
		final Town errandTown = ColdLife.IN_TOWN.equals(activity) ? context.catalog().town(bot.getTown()) : null;
		moveTo(bot, (errandTown != null) ? errandPosition(errandTown, leg.from(), leg.startAt(), now, context.travel()) : leg.positionAt(now));
		if (!leg.done(now))
		{
			return;
		}

		switch (activity)
		{
			case ESCAPING:
			{
				arriveInTown(bot, now, context, events, true);
				break;
			}
			case WALKING_TO_TOWN:
			{
				arriveInTown(bot, now, context, events, false);
				break;
			}
			case AFK:
			{
				events.add(new DecisionLog.Event(null, "Back from AFK"));
				leaveTown(bot, now, context, events);
				break;
			}
			case IN_TOWN:
			{
				finishErrands(bot, now, context, events);
				break;
			}
			case TO_ZONE:
			{
				bot.setLeg(null);
				bot.setActivity(HUNTING);
				events.add(new DecisionLog.Event(null, "Arrived at " + bot.getZone() + " and started hunting"));
				break;
			}
			case RESTING:
			{
				bot.setLeg(null);
				bot.setActivity(HUNTING); // rested: back to the fight, no need to log every break
				break;
			}
			case DEAD:
			{
				final Town town = context.catalog().town(bot.getTown());
				if (town == null)
				{
					bot.setLeg(null);
					bot.setActivity(HUNTING);
					break;
				}
				events.add(new DecisionLog.Event(null, "Back on its feet in " + town.name() + ". Heading to the shops"));
				startErrands(bot, now, context);
				break;
			}
			case TO_TOWN:
			{
				arriveInTown(bot, now, context, events, true);
				break;
			}
			case CLASS_MASTER:
			{
				finishClassMaster(bot, now, context, events);
				// Class masters also teach skills: learn what the (new) class can, when this one is its trainer.
				final Town town = context.catalog().town(bot.getTown());
				if ((town != null) && (town.master(ClassPath.trainer(bot.getClassId())) != null))
				{
					learnSkills(bot, town, context, events);
				}
				leaveTown(bot, now, context, events);
				break;
			}
			case TRAINER:
			{
				learnSkills(bot, context.catalog().town(bot.getTown()), context, events);
				leaveTown(bot, now, context, events);
				break;
			}
			default:
			{
				bot.setLeg(null);
				bot.setActivity(HUNTING);
				break;
			}
		}
	}

	private static void hunt(ColdBot bot, long now, long elapsedMs, Context context, List<DecisionLog.Event> events)
	{
		// Drink potions as if taking real damage while hunting. Fractional rates are rolled so the average is right.
		double perHour = context.travel().potionsPerHour() * LivingSupplies.potionUseFactor(bot.getClassId());
		final ZoneCombat model = context.combat();
		if ((model != null) && model.knows(bot.getZone()) && (bot.getPotions() > 0))
		{
			perHour = model.potionsPerHour(bot.getZone(), bot.getClassId(), bot.getLevel(), statsOf(bot, context.gear(), model), 1.0, perHour); // only what its hunting gives a use for
		}
		final double expected = (Math.max(0L, elapsedMs) / 3_600_000.0) * perHour;
		long drunk = (long) Math.floor(expected);
		if (context.random().nextDouble() < (expected - drunk))
		{
			drunk++;
		}
		if (drunk > 0)
		{
			bot.setPotions(Math.max(0L, bot.getPotions() - drunk));
		}

		final ZoneCatalog catalog = context.catalog();
		final Zone zone = catalog.zone(bot.getZone());

		// Only a cold bot's hunt is imagined: a hot one fights, rests and dies for real.
		if ((context.risk() != null) && !bot.isHotLock() && (zone != null) && (elapsedMs > 0))
		{
			if (dies(bot, zone, now, elapsedMs, context, events) || rests(bot, now, elapsedMs, context))
			{
				return;
			}
		}

		final Town town = townFor(bot, zone, catalog);
		progressClassQuest(bot, now, context, events);
		String betterZone = null;
		if (town != null)
		{
			final long budget = SupplyPlanner.spendable(purse(bot), context.supply().reserveFloor());
			final ZoneChooser.Choice choice = ZoneChooser.choose(situation(bot, zone, town, budget, now, context), catalog, null);
			if ((choice != null) && ((zone == null) || !choice.zone().name().equals(zone.name())))
			{
				betterZone = choice.zone().name();
			}
		}

		final SupplyPlanner.Prices prices = context.priceBook().prices(bot.getLevel(), bot.getGearTier(), bot.getClassId());
		// It plans with what it will have once its loot is sold, which it does first thing in town.
		final String[] classErrand = classErrand(bot, town, context);
		final String[] skillErrand = skillErrand(bot, town, context);
		final String[] gearErrand = gearErrand(bot, town, context, now);
		final GoalPlanner.View view = new GoalPlanner.View(bot.getLevel(), purse(bot), bot.getPotions(), bot.getSoulshots(), bot.getEscapes(), bot.getGearTier(), bot.isRewardClaimed(), (zone == null) ? null : zone.name(), (zone == null) ? 0 : zone.minLevel(), (zone == null) ? 0 : zone.maxLevel(), betterZone, classErrand[0], classErrand[1], skillErrand[0], skillErrand[1], gearErrand[0], gearErrand[1], gearErrand[2]);
		final GoalPlanner.Plan plan = GoalPlanner.plan(view, prices, supplyFor(bot, context), LivingSupplies.isMystic(bot.getClassId()));
		bot.setGoal(goalLabel(plan.chosen().goal()));
		events.add(new DecisionLog.Event(planKey(plan), GoalPlanner.describe(plan)));

		if ((plan.chosen().goal() == GoalPlanner.Goal.HUNT) || (town == null))
		{
			return;
		}

		final Point from = point(bot);
		bot.setTown(town.name());
		// On foot a bot heads straight for the shops; a scroll lands it at the town's arrival point.
		final Point shops = shopsOf(town);
		if (usesEscape(bot, town, context))
		{
			bot.setEscapes(bot.getEscapes() - 1);
			bot.setActivity(ESCAPING);
			// The bot stands still while the scroll is cast, then appears at the town's arrival point (arriveInTown).
			bot.setLeg(TravelLeg.stay(from, now, context.travel().escapeCastMs()));
			events.add(new DecisionLog.Event(null, "Used a Scroll of Escape to " + town.name() + " (" + DecisionLog.num(bot.getEscapes()) + " left)"));
		}
		else if (!catalog.sameLand(from, shops))
		{
			// No scroll, and water between it and the town (an island without a town): it cannot walk, so it goes back
			// the way it came, paying what the gatekeeper's way here cost (FPC-277).
			final ZoneCatalog.Teleport link = (zone == null) ? null : zone.teleportFrom(town.name());
			final long fee = (link == null) ? 0L : Math.min(Math.max(0L, bot.getAdena()), link.fee());
			bot.setAdena(bot.getAdena() - fee);
			bot.setActivity(TO_TOWN);
			bot.setLeg(TravelLeg.stay(from, now, context.travel().escapeCastMs()));
			events.add(new DecisionLog.Event(null, "Has no Scroll of Escape and water lies between it and " + town.name() + ", so takes the gatekeeper's way back" + ((fee > 0) ? (" for " + DecisionLog.num(fee) + " adena") : "")));
		}
		else
		{
			final TravelLeg walk = TravelLeg.walk(from, shops, now, context.travel().moveSpeed());
			bot.setActivity(WALKING_TO_TOWN);
			bot.setLeg(walk);
			events.add(new DecisionLog.Event(null, ((bot.getEscapes() > 0) ? (town.name() + " is close, so walks there") : ("Has no Scroll of Escape, so walks to " + town.name())) + " (about " + minutes(walk.durationMs()) + ")"));
		}
	}

	private static void arriveInTown(ColdBot bot, long now, Context context, List<DecisionLog.Event> events, boolean escaped)
	{
		final Town town = context.catalog().town(bot.getTown());
		if (town == null)
		{
			bot.setLeg(null);
			bot.setActivity(HUNTING);
			return;
		}
		if (escaped)
		{
			moveTo(bot, town.arrival()); // a walker is already where its walk ended, at the shops
		}
		events.add(new DecisionLog.Event(null, "Arrived in " + town.name() + ". Heading to the shops"));
		startErrands(bot, now, context);
	}

	/**
	 * Where a bot running its errands is: walking from where it started to the grocer, stopping there, walking on to the
	 * gatekeeper and stopping there, the same timing {@link #startErrands} gives the whole visit. Keeps a cold bot in the
	 * middle of town where players see it, rather than parked at the town's arrival point.
	 * @param town the town
	 * @param from where the errands started
	 * @param startAt when they started
	 * @param now the time
	 * @param travel travel tuning
	 * @return the position
	 */
	public static Point errandPosition(Town town, Point from, long startAt, long now, Params travel)
	{
		long elapsed = Math.max(0L, now - startAt);
		Point cursor = from;
		for (Point stop : new Point[]
		{
			town.grocer(),
			town.gatekeeper()
		})
		{
			if (stop == null)
			{
				continue;
			}
			final TravelLeg walk = TravelLeg.walk(cursor, stop, 0L, travel.moveSpeed());
			if (elapsed < walk.durationMs())
			{
				return walk.positionAt(elapsed);
			}
			elapsed -= walk.durationMs();
			if (elapsed < travel.errandStopMs())
			{
				return stop;
			}
			elapsed -= travel.errandStopMs();
			cursor = stop;
		}
		return cursor;
	}

	/** Starts the town errands: walk to the grocer, shop, walk to the gatekeeper. */
	private static void startErrands(ColdBot bot, long now, Context context)
	{
		final Town town = context.catalog().town(bot.getTown());
		final Point at = point(bot);
		long duration = 0L;
		if (town != null)
		{
			final Params travel = context.travel();
			Point cursor = at;
			if (town.grocer() != null)
			{
				duration += TravelLeg.walk(cursor, town.grocer(), 0L, travel.moveSpeed()).durationMs() + travel.errandStopMs();
				cursor = town.grocer();
			}
			if (town.gatekeeper() != null)
			{
				duration += TravelLeg.walk(cursor, town.gatekeeper(), 0L, travel.moveSpeed()).durationMs() + travel.errandStopMs();
			}
		}
		bot.setActivity(IN_TOWN);
		bot.setLeg(TravelLeg.stay(at, now, duration));
	}

	/** Adena plus what its loot will sell for: its purse once it has sold up in town. */
	static long purse(ColdBot bot)
	{
		return Math.min(ColdEconomy.MAX_ADENA, bot.getAdena() + Math.max(0L, bot.getLoot()));
	}

	/**
	 * Rolls whether this span of hunting went wrong. A dead bot loses experience, goes back to the nearest town to recover
	 * and then runs its errands like any visit (selling loot and restocking), and after its second death in a zone within
	 * the avoidance window it stays away from that zone for a while.
	 * @return whether it died
	 */
	private static boolean dies(ColdBot bot, Zone zone, long now, long elapsedMs, Context context, List<DecisionLog.Event> events)
	{
		final ColdRisk.Params risk = context.risk();
		final GearShop shop = context.gear();
		// Gear behind its level: tiers not bought yet, or with gear kept per slot, grades its weapon or chest armor lag.
		final int gearHave = (shop == null) ? bot.getGearTier() : 0;
		final int gearWant = (shop == null) ? SupplyPlanner.tierCeiling(bot.getLevel(), context.supply()) : LivingGear.behind(gearOf(bot), bot.getLevel(), shop.items());
		final ZoneCombat combat = context.combat();
		// The HP model's deaths an hour, in the party or alone, are the answer as they are; without rest data for the zone the old factor times the base rate applies.
		final boolean zoned = (combat != null) && combat.knows(zone.name());
		final double modelRate = (bot.getPartyDeathsPerHour() >= 0.0) ? bot.getPartyDeathsPerHour() : ((bot.getPartyDeathFactor() <= 0.0) && zoned) ? combat.deathsPerHour(zone.name(), bot.getClassId(), bot.getLevel(), statsOf(bot, shop, combat)) : -1.0;
		final double zoneFactor = (bot.getPartyDeathFactor() > 0.0) ? bot.getPartyDeathFactor() : zoned ? combat.deathFactor(zone.name(), bot.getClassId(), bot.getLevel(), statsOf(bot, shop, combat)) : 0.0;
		final ColdRisk.Danger danger = (modelRate >= 0.0) ? ColdRisk.fromRate(modelRate, bot.getPotions()) : ColdRisk.danger(risk, bot.getLevel(), zone.minLevel(), zone.maxLevel(), bot.getPotions(), gearHave, gearWant, bot.getClassId(), zoneFactor);
		if (context.random().nextDouble() >= ColdRisk.deathChance(danger.deathsPerHour(), elapsedMs))
		{
			return false;
		}
		final Town town = townFor(bot, zone, context.catalog());
		if (town == null)
		{
			return false;
		}

		final long lost = Math.min(bot.getExpIntoLevel(), Math.round(context.expToNext().applyAsLong(bot.getLevel()) * (ColdRisk.expLossPercent(bot.getLevel()) / 100.0)));
		bot.setExpIntoLevel(bot.getExpIntoLevel() - lost);
		bot.setDeaths(bot.getDeaths() + 1);
		final ColdRisk.State after = ColdRisk.died(bot.getRisk(), zone.name(), now, risk);
		final boolean avoidsNow = after.avoids(zone.name(), now) && !bot.getRisk().avoids(zone.name(), now);
		bot.setRisk(after);

		final String why = danger.reasons().isEmpty() ? "" : (" (" + String.join(", ", danger.reasons()) + ")");
		events.add(new DecisionLog.Event(null, "Died in " + zone.name() + why + " and lost " + DecisionLog.num(lost) + " exp. Went back to " + town.name() + " to recover"));
		if (avoidsNow)
		{
			events.add(new DecisionLog.Event(null, "Died twice in " + zone.name() + ": stays away from it for " + minutes(risk.avoidMs()) + " and picks easier zones meanwhile"));
		}
		moveTo(bot, town.arrival());
		bot.setTown(town.name());
		bot.setActivity(DEAD);
		bot.setLeg(TravelLeg.stay(town.arrival(), now, risk.recoverMs()));
		return true;
	}

	/**
	 * A hot bot died for real and its character went back to a town, the way a player does (the experience loss was the
	 * game's own). The row follows: it recovers in the nearest town with shops, then runs its errands like any visit, and
	 * a second death in the same zone keeps it away from that zone for a while, as for a cold death.
	 * @param bot the row
	 * @param where where the character stands up again
	 * @param now when
	 * @param context the shared context
	 * @param events where the decision is logged
	 * @return the town it recovers in, or {@code null} if no town is known (the row is left as it was)
	 */
	public static Town diedHot(ColdBot bot, Point where, long now, Context context, List<DecisionLog.Event> events)
	{
		final Town town = context.catalog().nearestShoppingTown(where);
		if (town == null)
		{
			return null;
		}
		final ColdRisk.Params risk = context.risk();
		final String zone = bot.getZone();
		bot.setDeaths(bot.getDeaths() + 1);
		final ColdRisk.State after = ColdRisk.died(bot.getRisk(), zone, now, risk);
		final boolean avoidsNow = (zone != null) && after.avoids(zone, now) && !bot.getRisk().avoids(zone, now);
		bot.setRisk(after);
		events.add(new DecisionLog.Event(null, "Died" + ((zone == null) ? "" : (" in " + zone)) + " while hot and went back to " + town.name() + " to recover"));
		if (avoidsNow)
		{
			events.add(new DecisionLog.Event(null, "Died twice in " + zone + ": stays away from it for " + minutes(risk.avoidMs()) + " and picks easier zones meanwhile"));
		}
		moveTo(bot, where);
		bot.setTown(town.name());
		bot.setActivity(DEAD);
		bot.setLeg(TravelLeg.stay(where, now, risk.recoverMs()));
		return town;
	}

	/**
	 * Sits down for a rest once it has hunted long enough since the last one, as a player recovers HP and MP between
	 * fights. Casters and archers rest longer. Only without the zone model's rest estimate, which replaces it.
	 * @return whether it sat down
	 */
	private static boolean rests(ColdBot bot, long now, long elapsedMs, Context context)
	{
		final ColdRisk.Params risk = context.risk();
		if ((context.combat() != null) && context.combat().restModeled(bot.getZone()))
		{
			// The zone model already takes the sitting out of the kill rate; a fixed rest on top would count it twice.
			bot.setRisk(bot.getRisk().withHunted(0L));
			return false;
		}
		final long hunted = bot.getRisk().huntedSinceRestMs() + elapsedMs;
		if ((risk.restEveryMs() <= 0) || (risk.restMs() <= 0) || (hunted < risk.restEveryMs()))
		{
			bot.setRisk(bot.getRisk().withHunted(hunted));
			return false;
		}
		bot.setRisk(bot.getRisk().withHunted(0L));
		bot.setActivity(RESTING);
		bot.setLeg(TravelLeg.stay(point(bot), now, ColdRisk.restMs(risk, bot.getClassId())));
		return true;
	}

	private static void finishErrands(ColdBot bot, long now, Context context, List<DecisionLog.Event> events)
	{
		final ZoneCatalog catalog = context.catalog();
		final Town town = catalog.town(bot.getTown());
		if (town == null)
		{
			bot.setLeg(null);
			bot.setActivity(HUNTING);
			return;
		}

		// Sell the loot gathered while hunting, then shop at the grocer with the proceeds.
		if (bot.getLoot() > 0)
		{
			final long sold = bot.getLoot();
			bot.setAdena(Math.min(ColdEconomy.MAX_ADENA, bot.getAdena() + sold));
			bot.setLoot(0L);
			if (sold >= LOOT_LOG_MIN)
			{
				events.add(new DecisionLog.Event(null, "Sold its loot in " + town.name() + " for " + DecisionLog.num(sold) + " adena"));
			}
		}
		if (town.hasGrocer())
		{
			final SupplyPlanner.Prices prices = context.priceBook().prices(bot.getLevel(), bot.getGearTier(), bot.getClassId());
			final SupplyPlanner.Params supply = supplyFor(bot, context);
			final SupplyPlanner.Purchase bought = SupplyPlanner.shop(bot.getLevel(), bot.getAdena(), bot.getEscapes(), bot.getPotions(), bot.getSoulshots(), bot.getGearTier(), bot.isRewardClaimed(), prices, supply);
			if (bought.any())
			{
				bot.setAdena(Math.max(0L, bot.getAdena() - bought.cost()));
				bot.setEscapes(bot.getEscapes() + bought.escapes());
				bot.setPotions(bot.getPotions() + bought.potions());
				bot.setSoulshots(bot.getSoulshots() + bought.soulshots());
				if (bought.upgraded())
				{
					bot.setGearTier(bot.getGearTier() + 1);
				}
				events.add(new DecisionLog.Event(null, "Shopped in " + town.name() + ": " + describe(bought, bot.getGearTier(), LivingSupplies.isMystic(bot.getClassId())) + " for " + DecisionLog.num(bought.cost()) + " adena (keeps " + DecisionLog.num(SupplyPlanner.reserve(bot.getLevel(), bot.getAdena() + bought.cost(), supply)) + " in reserve)"));
			}
			else
			{
				events.add(new DecisionLog.Event(null, "Nothing it can afford in " + town.name() + " right now"));
			}
		}

		// Gear, piece by piece, with what is left after supplies.
		shopGear(bot, town, context, events);
		bot.setShoppedAt(now);

		// Business with a class master or its trainer comes before any break.
		if (visitClassMaster(bot, town, now, context, events) || visitTrainer(bot, town, now, context, events))
		{
			return;
		}

		// Now and then a break before heading out, like a player stepping away from the keyboard. Not every visit.
		final Params travel = context.travel();
		if ((travel.afkChancePercent() > 0) && (context.random().nextInt(100) < travel.afkChancePercent()))
		{
			final long span = Math.max(0L, travel.afkMaxMs() - travel.afkMinMs());
			final long afk = travel.afkMinMs() + ((span > 0) ? (long) (context.random().nextDouble() * span) : 0L);
			bot.setActivity(AFK);
			bot.setLeg(TravelLeg.stay(point(bot), now, afk));
			events.add(new DecisionLog.Event(null, "Went AFK in " + town.name() + " for " + minutes(afk)));
			return;
		}
		leaveTown(bot, now, context, events);
	}

	/**
	 * Moves the class-change quest along while hunting: a bot that reaches the level of its next class change sets out to
	 * take the quest, and one whose quest hunt is over heads back to hand it in.
	 */
	private static void progressClassQuest(ColdBot bot, long now, Context context, List<DecisionLog.Event> events)
	{
		if (context.classQuestMs() == null)
		{
			return;
		}
		final ClassPath.Quest quest = bot.getQuest();
		if (quest == null)
		{
			if (!ClassPath.due(bot.getClassId(), bot.getLevel()))
			{
				return;
			}
			final int target = ClassPath.next(bot.getClassId(), bot.getId());
			if ((target < 0) || (ClassPath.master(bot.getClassId(), target) == null))
			{
				return;
			}
			bot.setQuest(new ClassPath.Quest(ClassPath.Stage.TAKE, target, 0L));
			events.add(new DecisionLog.Event(null, "Reached level " + bot.getLevel() + ": time to become a " + ClassPath.name(target) + ". Needs the quest from a class master"));
		}
		else if ((quest.stage() == ClassPath.Stage.HUNT) && (now >= quest.endAt()))
		{
			bot.setQuest(new ClassPath.Quest(ClassPath.Stage.RETURN, quest.target(), 0L));
			events.add(new DecisionLog.Event(null, "Finished the quest hunt for " + ClassPath.name(quest.target()) + ". Needs to hand it in to a class master"));
		}
	}

	/**
	 * Why this bot needs a class master, for the planner, and what stops it going.
	 * @return the reason (null when it has no business with one) and the blocker (null when it can go)
	 */
	private static String[] classErrand(ColdBot bot, Town town, Context context)
	{
		final ClassPath.Quest quest = bot.getQuest();
		if ((context.classQuestMs() == null) || (quest == null) || !quest.needsMaster())
		{
			return new String[2];
		}
		final String name = ClassPath.name(quest.target());
		final String reason = (quest.stage() == ClassPath.Stage.TAKE) ? ("take the class change quest for " + name) : ("hand in its quest and become a " + name);
		return new String[]
		{
			reason,
			masterBlocker(ClassPath.master(bot.getClassId(), quest.target()), "class master", bot, town, context)
		};
	}

	/**
	 * Why this bot wants to see its trainer, for the planner, and what stops it going: a batch of skills it can pay for.
	 * @return the reason (null when too few skills wait) and the blocker (null when it can go)
	 */
	private static String[] skillErrand(ColdBot bot, Town town, Context context)
	{
		final SkillPlanner.Lesson lesson = previewLesson(bot, trainingBudget(bot, purse(bot), context), context);
		if ((lesson == null) || (lesson.learned().size() < SKILL_TRIP_MIN))
		{
			return new String[2];
		}
		final String reason = "learn " + lesson.learned().size() + " skill levels at its trainer (" + DecisionLog.num(lesson.sp()) + " SP" + ((lesson.books() > 0) ? (", " + lesson.books() + ((lesson.books() == 1) ? " spellbook" : " spellbooks") + " for " + DecisionLog.num(lesson.adena()) + " adena") : "") + ")";
		return new String[]
		{
			reason,
			masterBlocker(ClassPath.trainer(bot.getClassId()), "trainer", bot, town, context)
		};
	}

	/** @return why the bot cannot reach this master from its town, or null when it can */
	private static String masterBlocker(String script, String what, ColdBot bot, Town town, Context context)
	{
		final Town masterTown = context.catalog().masterTown(script, town);
		if ((town == null) || (masterTown == null))
		{
			return "no " + what + " it can reach";
		}
		// The fee leaves at least the reserve floor, the money it leaves the master's town with for the way back out.
		if ((masterTown != town) && ((town.feeTo(masterTown.name()) + context.supply().reserveFloor()) > purse(bot)))
		{
			return "cannot afford the gatekeeper to " + masterTown.name();
		}
		return null;
	}

	/** @return what a trainer visit would teach it now, on a copy of its skills, or null when skills are not tracked */
	private static SkillPlanner.Lesson previewLesson(ColdBot bot, long budget, Context context)
	{
		if ((context.skills() == null) || (bot.getSkills() == null))
		{
			return null;
		}
		return SkillPlanner.learn(context.skills().tree(bot.getClassId()), SkillPlanner.decode(bot.getSkills()), bot.getLevel(), bot.getSp(), budget);
	}

	/**
	 * A row from before skills were tracked starts with every skill its level allows, as the character it was given, so
	 * nothing is taken away; from then on it learns at the trainer.
	 */
	private static void trackSkills(ColdBot bot, Context context, List<DecisionLog.Event> events)
	{
		if ((context.skills() == null) || (bot.getSkills() != null))
		{
			return;
		}
		final Map<Integer, Integer> known = new TreeMap<>();
		SkillPlanner.grantAll(context.skills().tree(bot.getClassId()), known, bot.getLevel());
		bot.setSkills(SkillPlanner.encode(known));
		if (bot.getLevel() > 1)
		{
			events.add(new DecisionLog.Event(null, "Starts learning at the trainer, knowing the " + known.size() + " skills of a level " + bot.getLevel() + " " + ClassPath.name(bot.getClassId())));
		}
	}

	/** At its trainer: learns every skill it can pay for in SP and spellbooks, keeping its adena reserve. */
	private static void learnSkills(ColdBot bot, Town town, Context context, List<DecisionLog.Event> events)
	{
		if ((town == null) || (context.skills() == null) || (bot.getSkills() == null))
		{
			return;
		}
		final Map<Integer, Integer> known = SkillPlanner.decode(bot.getSkills());
		final SkillPlanner.Lesson lesson = SkillPlanner.learn(context.skills().tree(bot.getClassId()), known, bot.getLevel(), bot.getSp(), trainingBudget(bot, bot.getAdena(), context));
		if (!lesson.any())
		{
			return;
		}
		bot.setSp(Math.max(0L, bot.getSp() - lesson.sp()));
		bot.setAdena(Math.max(0L, bot.getAdena() - lesson.adena()));
		bot.setSkills(SkillPlanner.encode(known));
		final StringBuilder text = new StringBuilder("Learned ").append(lesson.learned().size()).append(" skill levels from its trainer in ").append(town.name()).append(" for ").append(DecisionLog.num(lesson.sp())).append(" SP");
		if (lesson.books() > 0)
		{
			text.append(", buying ").append(lesson.books()).append((lesson.books() == 1) ? " spellbook" : " spellbooks").append(" for ").append(DecisionLog.num(lesson.adena())).append(" adena");
		}
		if (lesson.shortSp() > 0)
		{
			text.append(". ").append(lesson.shortSp()).append(" more wait for SP");
		}
		if (lesson.shortAdena() > 0)
		{
			text.append((lesson.shortSp() > 0) ? ", " : ". ").append(lesson.shortAdena()).append(" wait for spellbook money");
		}
		events.add(new DecisionLog.Event(null, text.toString()));
	}

	/**
	 * After its errands, a bot with business at a class master walks to one in this town, or pays the gatekeeper to the
	 * nearest town that has one and runs its errands there.
	 * @return whether it set off
	 */
	private static boolean visitClassMaster(ColdBot bot, Town town, long now, Context context, List<DecisionLog.Event> events)
	{
		final ClassPath.Quest quest = bot.getQuest();
		if ((context.classQuestMs() == null) || (quest == null) || !quest.needsMaster())
		{
			return false;
		}
		return visitMaster(bot, town, ClassPath.master(bot.getClassId(), quest.target()), CLASS_MASTER, "class master", now, context, events);
	}

	/**
	 * After its errands, a bot with skills waiting walks to its trainer in this town; for a batch worth a trip of its own
	 * it also pays the gatekeeper to the nearest town that has one.
	 * @return whether it set off
	 */
	private static boolean visitTrainer(ColdBot bot, Town town, long now, Context context, List<DecisionLog.Event> events)
	{
		final SkillPlanner.Lesson lesson = previewLesson(bot, trainingBudget(bot, bot.getAdena(), context), context);
		if ((lesson == null) || !lesson.any())
		{
			return false;
		}
		final String trainer = ClassPath.trainer(bot.getClassId());
		if ((town.master(trainer) == null) && (lesson.learned().size() < SKILL_TRIP_MIN))
		{
			return false; // a skill or two can wait for a visit to a town with its trainer
		}
		return visitMaster(bot, town, trainer, TRAINER, "trainer", now, context, events);
	}

	/**
	 * Walks to a class master in this town (as {@code activity}), or pays the gatekeeper to the nearest town that has one
	 * and runs its errands there first.
	 * @return whether it set off
	 */
	private static boolean visitMaster(ColdBot bot, Town town, String script, String activity, String what, long now, Context context, List<DecisionLog.Event> events)
	{
		final Point master = town.master(script);
		if (master != null)
		{
			final Point from = point(bot);
			final long walk = TravelLeg.walk(from, master, 0L, context.travel().moveSpeed()).durationMs();
			bot.setActivity(activity);
			bot.setLeg(new TravelLeg(from, master, now, now + walk + context.travel().errandStopMs()));
			return true;
		}
		final Town other = context.catalog().masterTown(script, town);
		if ((other == null) || (other == town))
		{
			events.add(new DecisionLog.Event("nomaster-" + script, "No " + what + " it can reach from " + town.name()));
			return false;
		}
		final long fee = town.feeTo(other.name());
		if ((fee + context.supply().reserveFloor()) > bot.getAdena())
		{
			events.add(new DecisionLog.Event("masterfee-" + other.name(), "Cannot afford the gatekeeper to " + other.name() + " for its " + what + " yet"));
			return false;
		}
		bot.setAdena(bot.getAdena() - fee);
		bot.setTown(other.name());
		bot.setActivity(TO_TOWN);
		bot.setLeg(TravelLeg.stay(other.arrival(), now, context.travel().escapeCastMs()));
		events.add(new DecisionLog.Event(null, "Paid the " + town.name() + " gatekeeper " + DecisionLog.num(fee) + " adena to go to the " + what + " in " + other.name()));
		return true;
	}

	/** At the class master: takes the quest, or hands it in and changes class. */
	private static void finishClassMaster(ColdBot bot, long now, Context context, List<DecisionLog.Event> events)
	{
		final ClassPath.Quest quest = bot.getQuest();
		if ((quest == null) || !quest.needsMaster())
		{
			return;
		}
		final String where = (bot.getTown() == null) ? "town" : bot.getTown();
		if (quest.stage() == ClassPath.Stage.TAKE)
		{
			final long[] questMs = context.classQuestMs();
			final int tier = Math.max(0, Math.min(questMs.length - 1, ClassPath.tier(bot.getClassId())));
			final long huntMs = questMs[tier];
			if (huntMs > 0)
			{
				bot.setQuest(new ClassPath.Quest(ClassPath.Stage.HUNT, quest.target(), now + huntMs));
				events.add(new DecisionLog.Event(null, "Took the quest for " + ClassPath.name(quest.target()) + " from the class master in " + where + ". Hunts for it for about " + minutes(huntMs)));
				return;
			}
		}
		bot.setClassId(quest.target());
		bot.setQuest(null);
		events.add(new DecisionLog.Event(null, "Became a " + ClassPath.name(quest.target()) + " at the class master in " + where));
	}

	/** Picks the next zone and the way there, pays for it and sets off; or waits in town when it cannot afford one. */
	private static void leaveTown(ColdBot bot, long now, Context context, List<DecisionLog.Event> events)
	{
		final ZoneCatalog catalog = context.catalog();
		final Town town = catalog.town(bot.getTown());
		if (town == null)
		{
			bot.setLeg(null);
			bot.setActivity(HUNTING);
			return;
		}
		final Zone current = catalog.zone(bot.getZone());
		final long budget = SupplyPlanner.spendable(bot.getAdena(), context.supply().reserveFloor());
		final ZoneChooser.Situation situation = situation(bot, current, town, budget, now, context);
		ZoneChooser.Choice choice = ZoneChooser.choose(situation, catalog, context.random());
		if ((choice == null) && (current != null))
		{
			// Nothing better it can afford: go back to where it was, even if it no longer fits perfectly.
			final ZoneChooser.Choice back = ZoneChooser.route(current, town, catalog);
			if ((back != null) && (back.fee() <= bot.getAdena()))
			{
				choice = back;
			}
		}
		boolean broke = false;
		if (choice == null)
		{
			// Too poor for any gatekeeper: it walks to the nearest zone that fits, however far, as a broke player does,
			// rather than wait in a town where it earns nothing.
			choice = ZoneChooser.walkFallback(situation, catalog);
			broke = choice != null;
		}
		if (choice == null)
		{
			bot.setActivity(IN_TOWN);
			bot.setLeg(TravelLeg.stay(point(bot), now, context.travel().retryMs()));
			events.add(new DecisionLog.Event("stuck-" + town.name(), "Cannot afford the way to a zone that fits from " + town.name() + " yet. Waits in town"));
			return;
		}

		bot.setAdena(Math.max(0L, bot.getAdena() - choice.fee()));
		final Zone zone = choice.zone();
		final Point spot = zone.spots().get(context.random().nextInt(zone.spots().size()));
		final String change = ((current == null) || !current.name().equals(zone.name())) ? "Moving on to " : "Back to ";
		final Point start;
		switch (choice.way())
		{
			case GATEKEEPER:
			{
				start = choice.arrival();
				events.add(new DecisionLog.Event(null, change + zone.name() + " (levels " + zone.minLevel() + " to " + zone.maxLevel() + "). Paid the " + town.name() + " gatekeeper " + DecisionLog.num(choice.fee()) + " adena"));
				break;
			}
			case HOP:
			{
				start = choice.arrival();
				events.add(new DecisionLog.Event(null, change + zone.name() + " (levels " + zone.minLevel() + " to " + zone.maxLevel() + ") by gatekeeper through " + choice.viaTown() + ", " + DecisionLog.num(choice.fee()) + " adena in fees"));
				break;
			}
			default:
			{
				start = point(bot); // sets off on foot from where it finished its errands
				events.add(new DecisionLog.Event(null, change + zone.name() + " (levels " + zone.minLevel() + " to " + zone.maxLevel() + ") on foot" + (broke ? ", as it cannot afford a gatekeeper" : "")));
				break;
			}
		}
		moveTo(bot, start);
		countMove(context.occupancy(), bot.getZone(), zone.name());
		bot.setZone(zone.name());
		bot.setTown(null);
		bot.setActivity(TO_ZONE);
		bot.setLeg(TravelLeg.walk(start, spot, now, context.travel().moveSpeed()));
	}

	/**
	 * Moves a bot from one zone to another in this tick's head counts at once, so bots leaving town in the same tick see
	 * it and do not all pick the same zone past its limit.
	 */
	private static void countMove(Map<String, Integer> occupancy, String from, String to)
	{
		if ((occupancy == null) || to.equals(from))
		{
			return;
		}
		if (from != null)
		{
			occupancy.computeIfPresent(from, (name, count) -> (count > 1) ? (count - 1) : null);
		}
		occupancy.merge(to, 1, Integer::sum);
	}

	// After a visit, an upgrade alone does not send it back to town before it has hunted this long.
	public static final long GEAR_TRIP_GAP_MS = 20 * 60_000L;
	// A piece for an empty slot is worth a trip of its own only when it costs at least this much more than the trip.
	public static final long EMPTY_SLOT_TRIP_MIN = 1000L;
	// Shopping rounds per visit: each spends what the pieces it replaced sold for in the round before.
	private static final int GEAR_SHOP_PASSES = 4;

	/**
	 * Why this bot wants to go to town for gear, for the planner: a good upgrade it can afford there (the first in shop
	 * order), or, when it cannot afford one yet, what it is saving up for.
	 * @return the reason (null when there is nothing good to buy), the blocker (null when it can go) and a key
	 */
	private static String[] gearErrand(ColdBot bot, Town town, Context context, long now)
	{
		final String[] errand = gearErrand(bot, town, context);
		// Gear alone is not worth a trip right after one: it hunts a while first, like a player who just shopped. (Any
		// other errand still takes it to town, and it buys the gear then.)
		final long since = now - bot.getShoppedAt();
		if ((errand[0] != null) && (errand[1] == null) && (bot.getShoppedAt() > 0) && (since >= 0) && (since < GEAR_TRIP_GAP_MS))
		{
			errand[1] = "it shopped " + minutes(since) + " ago and hunts " + minutes(GEAR_TRIP_GAP_MS) + " between gear trips";
		}
		return errand;
	}

	/** @return the gatekeeper fee from this town back to the bot's zone (0 on foot or when it has no zone) */
	private static long feeBack(ColdBot bot, Town town, Context context)
	{
		final Zone zone = context.catalog().zone(bot.getZone());
		final ZoneChooser.Choice back = (zone == null) ? null : ZoneChooser.route(zone, town, context.catalog());
		return (back == null) ? 0L : Math.max(0L, back.fee());
	}

	private static String[] gearErrand(ColdBot bot, Town town, Context context)
	{
		final GearShop shop = context.gear();
		if ((shop == null) || (town == null))
		{
			return new String[3];
		}
		final LivingGear.Fit fit = shop.fit(bot.getClassId());
		final List<LivingGear.Offer> offers = offersIn(shop, town);
		// What it will have for gear in town: its purse once the loot is sold, less the supplies it buys first (counting
		// the Scroll of Escape the trip itself uses, when it uses one rather than walking).
		final SupplyPlanner.Params supply = supplyFor(bot, context);
		final SupplyPlanner.Prices prices = context.priceBook().prices(bot.getLevel(), bot.getGearTier(), bot.getClassId());
		final long escapesLeft = usesEscape(bot, town, context) ? (bot.getEscapes() - 1) : bot.getEscapes();
		final SupplyPlanner.Purchase supplies = SupplyPlanner.shop(bot.getLevel(), purse(bot), Math.max(0L, escapesLeft), bot.getPotions(), bot.getSoulshots(), bot.getGearTier(), bot.isRewardClaimed(), prices, supply);
		final long adena = Math.max(0L, purse(bot) - supplies.cost());
		final long budget = SupplyPlanner.spendable(adena, SupplyPlanner.reserve(bot.getLevel(), adena, supply));
		final List<LivingGear.Change> affordable = LivingGear.shop(gearOf(bot), fit, bot.getLevel(), budget, offers, shop.items());
		// Filling an empty slot is always an upgrade, so on its own it is only worth the trip for a piece that costs more
		// than the trip itself (a cheap no-grade ring waits for a visit made for something else).
		final long tripCost = EMPTY_SLOT_TRIP_MIN + (usesEscape(bot, town, context) ? prices.escape() : 0L) + feeBack(bot, town, context);
		for (LivingGear.Change change : affordable)
		{
			if (!change.removed().isEmpty() || (change.price() >= tripCost))
			{
				return new String[]
				{
					"Can afford a better " + change.slot().label() + ": " + change.piece().name() + " for " + DecisionLog.num(change.price()) + " adena",
					null,
					"gear-" + change.piece().itemId()
				};
			}
		}
		if (!affordable.isEmpty())
		{
			return new String[3]; // only small pieces it can afford: it buys them on its next visit, no trip of their own
		}
		final LivingGear.Change wish = LivingGear.wish(gearOf(bot), fit, bot.getLevel(), offers, shop.items());
		if (wish == null)
		{
			return new String[3];
		}
		return new String[]
		{
			"Wants a better " + wish.slot().label() + ": " + wish.piece().name(),
			"it costs " + DecisionLog.num(wish.price()) + " and it can spend " + DecisionLog.num(budget),
			"gear-" + wish.piece().itemId()
		};
	}

	/**
	 * Buys gear piece by piece on a town visit (see {@link LivingGear#shop}) and sells what it replaces.
	 */
	private static void shopGear(ColdBot bot, Town town, Context context, List<DecisionLog.Event> events)
	{
		final GearShop shop = context.gear();
		if (shop == null)
		{
			return;
		}
		final LivingGear.Fit fit = shop.fit(bot.getClassId());
		final Map<LivingGear.Slot, Integer> gear = gearOf(bot);
		long adena = bot.getAdena();
		final List<String> old = new ArrayList<>();
		// A slot filled on this visit is not shopped again, so it never buys a piece and sells it a round later.
		final Set<LivingGear.Slot> locked = EnumSet.noneOf(LivingGear.Slot.class);
		long sold = 0;
		// The old pieces are sold in the same visit, so their money can buy the next piece now rather than pull it back to
		// town a minute later: shop again with it until nothing more is affordable.
		for (int pass = 0; pass < GEAR_SHOP_PASSES; pass++)
		{
			final long cash = Math.min(ColdEconomy.MAX_ADENA, adena + sold);
			final long budget = SupplyPlanner.spendable(cash, SupplyPlanner.reserve(bot.getLevel(), cash, supplyFor(bot, context)));
			final List<LivingGear.Change> bought = LivingGear.shop(gear, fit, bot.getLevel(), budget, offersIn(shop, town), shop.items(), locked);
			if (bought.isEmpty())
			{
				break;
			}
			for (LivingGear.Change change : bought)
			{
				adena -= change.price();
				locked.add(change.slot());
				events.add(new DecisionLog.Event(null, "Bought " + LivingGear.describe(change, fit) + (change.shop() ? (" in " + town.name()) : " from another player") + " for " + DecisionLog.num(change.price()) + " adena"));
				for (int itemId : change.removed())
				{
					final LivingGear.Piece piece = shop.items().piece(itemId);
					if (piece != null)
					{
						old.add(piece.name());
						sold += piece.sellPrice();
					}
				}
			}
		}
		if (old.isEmpty() && (adena == bot.getAdena()))
		{
			return;
		}
		if (!old.isEmpty())
		{
			events.add(new DecisionLog.Event(null, "Sold its old " + String.join(", ", old) + " for " + DecisionLog.num(sold) + " adena"));
		}
		bot.setAdena(Math.min(ColdEconomy.MAX_ADENA, Math.max(0L, adena + sold)));
		setGear(bot, gear, shop);
	}

	/**
	 * A cold bot looks at the gear that dropped while it hunted: it puts on what is better and fits its class, and keeps
	 * the rest, with the pieces it took off, as loot to sell in town.
	 * @param bot the bot
	 * @param dropped the items that dropped
	 * @param context the world (does nothing without gear kept per slot)
	 * @param events receives what it put on
	 * @return what the loot it keeps sells for
	 */
	public static long findDrops(ColdBot bot, List<Integer> dropped, Context context, List<DecisionLog.Event> events)
	{
		final GearShop shop = context.gear();
		if ((shop == null) || dropped.isEmpty())
		{
			return 0L;
		}
		final LivingGear.Items items = shop.items();
		final LivingGear.Fit fit = shop.fit(bot.getClassId());
		final List<LivingGear.Piece> pieces = new ArrayList<>();
		final List<String> kept = new ArrayList<>();
		long loot = 0;
		for (int itemId : dropped)
		{
			final LivingGear.Piece piece = items.piece(itemId);
			if (piece == null)
			{
				continue;
			}
			if (shop.wearable(itemId))
			{
				pieces.add(piece);
			}
			kept.add(piece.name());
			loot += piece.sellPrice();
		}
		final Map<LivingGear.Slot, Integer> gear = gearOf(bot);
		final List<LivingGear.Change> worn = LivingGear.wearDrops(gear, pieces, fit, bot.getLevel(), items);
		for (LivingGear.Change change : worn)
		{
			loot -= change.piece().sellPrice(); // worn, not sold
			kept.remove(change.piece().name());
			for (int itemId : change.removed())
			{
				final LivingGear.Piece old = items.piece(itemId);
				if (old != null)
				{
					loot += old.sellPrice();
				}
			}
			events.add(new DecisionLog.Event(null, "Found " + LivingGear.describe(change, fit) + " while hunting and put it on"));
		}
		if (!worn.isEmpty())
		{
			setGear(bot, gear, shop);
		}
		if (!kept.isEmpty())
		{
			events.add(new DecisionLog.Event(null, "Picked up " + String.join(", ", kept) + " while hunting, to sell in town"));
		}
		return Math.max(0L, loot);
	}

	/**
	 * @param bot a bot
	 * @param shop the gear shop, or null for bots that buy whole tiers
	 * @param tierStep levels per whole gear tier
	 * @return its weapon grade and its armor grade (0 no grade to 5 S)
	 */
	static int[] gradesOf(ColdBot bot, GearShop shop, int tierStep)
	{
		if ((shop != null) && (bot.getGear() != null))
		{
			final Map<LivingGear.Slot, Integer> gear = gearOf(bot);
			final int weapon = LivingGear.weaponGrade(gear, shop.items());
			final int armor = Math.max(0, LivingGear.allowedGrade(bot.getLevel()) - LivingGear.behind(gear, bot.getLevel(), shop.items())); // the lower of weapon and chest
			return new int[] { weapon, armor };
		}
		final int grade = ZoneCombat.gradeOfTier(bot.getGearTier(), tierStep);
		return new int[] { grade, grade };
	}

	/**
	 * What the bot's worn gear gives: the weapon's P.Atk (M.Atk for a mage), the P.Def of its armor and shield, the M.Def of its jewelry.
	 * Empty slots count at their naked values (underwear 4; fighter chest 31, legs 18, head 12, gloves 8, feet 7; mystic chest 15, legs 8; jewelry 13, 9, 9, 5, 5);
	 * a full-body chest covers the legs. Set bonuses and enchants are not counted. A bot without a gear record gets the curves of its grade.
	 */
	/**
	 * @param skills the share of its skills it has learned
	 * @param blessed whether it fires blessed spiritshots
	 * @return the shots a kill of its zone takes it at its level, from its hits per kill; negative when the zone model does not give it
	 */
	static double shotsPerKill(ColdBot bot, GearShop shop, ZoneCombat combat, double skills, boolean blessed)
	{
		if ((combat == null) || !combat.shotModel() || !combat.knows(bot.getZone()))
		{
			return -1.0;
		}
		return combat.shotsPerKill(bot.getZone(), bot.getClassId(), bot.getLevel(), statsOf(bot, shop, combat), skills, blessed);
	}

	static ZoneCombat.Stats statsOf(ColdBot bot, GearShop shop, ZoneCombat combat)
	{
		if ((shop == null) || (bot.getGear() == null))
		{
			final int[] grades = gradesOf(bot, shop, combat.tierStep());
			return combat.curveStats(ZoneCombat.roleOf(bot.getClassId()), grades[0], grades[1]);
		}
		final Map<LivingGear.Slot, Integer> gear = gearOf(bot);
		final LivingGear.Items items = shop.items();
		final boolean mystic = LivingSupplies.isMystic(bot.getClassId());
		final boolean mage = ZoneCombat.roleOf(bot.getClassId()) == ZoneCombat.Role.MAGE;
		final LivingGear.Piece weapon = piece(gear, LivingGear.Slot.WEAPON, items);
		final double attack = (weapon == null) ? 4.0 : (mage ? weapon.mAtk() : weapon.pAtk());
		final LivingGear.Piece chest = piece(gear, LivingGear.Slot.CHEST, items);
		double pDef = 4.0;
		pDef += (chest != null) ? chest.pDef() : (mystic ? 15 : 31);
		final boolean fullBody = (chest != null) && chest.fullBody();
		final LivingGear.Piece legs = piece(gear, LivingGear.Slot.LEGS, items);
		pDef += fullBody ? 0.0 : ((legs != null) ? legs.pDef() : (mystic ? 8 : 18));
		pDef += slotDef(gear, LivingGear.Slot.HEAD, items, 12);
		pDef += slotDef(gear, LivingGear.Slot.GLOVES, items, 8);
		pDef += slotDef(gear, LivingGear.Slot.FEET, items, 7);
		final LivingGear.Piece shield = piece(gear, LivingGear.Slot.SHIELD, items);
		pDef += (shield != null) ? shield.pDef() : 0.0;
		double mDef = 0.0;
		mDef += jewel(gear, LivingGear.Slot.NECK, items, 13);
		mDef += jewel(gear, LivingGear.Slot.EAR1, items, 9);
		mDef += jewel(gear, LivingGear.Slot.EAR2, items, 9);
		mDef += jewel(gear, LivingGear.Slot.RING1, items, 5);
		mDef += jewel(gear, LivingGear.Slot.RING2, items, 5);
		return new ZoneCombat.Stats(attack, pDef, mDef, selfBuffShare(bot, combat));
	}

	/** The share of the class's damage and defence self buffs the bot has bought, or -1 when its skills are not tracked or the class has none to compare. */
	private static double selfBuffShare(ColdBot bot, ZoneCombat combat)
	{
		final int[] ids = combat.selfBuffIds(bot.getClassId(), bot.getLevel());
		if ((ids.length == 0) || (bot.getSkills() == null))
		{
			return -1.0;
		}
		final Map<Integer, Integer> known = SkillPlanner.decode(bot.getSkills());
		int have = 0;
		for (int id : ids)
		{
			if (known.containsKey(id))
			{
				have++;
			}
		}
		return (double) have / ids.length;
	}

	private static LivingGear.Piece piece(Map<LivingGear.Slot, Integer> gear, LivingGear.Slot slot, LivingGear.Items items)
	{
		final Integer id = gear.get(slot);
		return ((id == null) || (id <= 0)) ? null : items.piece(id);
	}

	private static double slotDef(Map<LivingGear.Slot, Integer> gear, LivingGear.Slot slot, LivingGear.Items items, double naked)
	{
		final LivingGear.Piece piece = piece(gear, slot, items);
		return (piece != null) ? piece.pDef() : naked;
	}

	private static double jewel(Map<LivingGear.Slot, Integer> gear, LivingGear.Slot slot, LivingGear.Items items, double naked)
	{
		final LivingGear.Piece piece = piece(gear, slot, items);
		return (piece != null) ? piece.mDef() : naked;
	}

	/**
	 * @param bot a bot
	 * @return the gear it wears (empty when not recorded)
	 */
	static Map<LivingGear.Slot, Integer> gearOf(ColdBot bot)
	{
		final Map<LivingGear.Slot, Integer> gear = LivingGear.decode(bot.getGear());
		return (gear == null) ? new EnumMap<>(LivingGear.Slot.class) : gear;
	}

	/**
	 * Records the gear a bot wears, and the gear tier that matches its weapon (for its soulshot grade and the monitor).
	 * @param bot the bot
	 * @param gear its gear
	 * @param shop the gear shop
	 */
	public static void setGear(ColdBot bot, Map<LivingGear.Slot, Integer> gear, GearShop shop)
	{
		bot.setGear(LivingGear.encode(gear));
		bot.setGearTier(LivingGear.tierFor(LivingGear.weaponGrade(gear, shop.items()), shop.tierStep()));
	}

	private static SupplyPlanner.Params supplyFor(ColdBot bot, Context context)
	{
		final SupplyPlanner.Params supply = context.supply().withPotionStock(LivingSupplies.potionStockFor(bot.getClassId(), context.supply().potionStock()));
		final double hits = shotsPerKill(bot, context.gear(), context.combat(), 1.0, false); // its hits per kill, when the zone model has them
		if (hits > 0.0)
		{
			return supply.withSoulshotsPerKill(hits);
		}
		return LivingSupplies.isMystic(bot.getClassId()) ? supply.withSoulshotsPerKill(context.travel().spiritshotsPerKill()) : supply;
	}

	/**
	 * @return what it may spend at its trainer: what is left above its full operating reserve, as for supplies and gear,
	 *         so spellbooks never leave it too poor to travel back out to a zone
	 */
	private static long trainingBudget(ColdBot bot, long adena, Context context)
	{
		return SupplyPlanner.spendable(adena, SupplyPlanner.reserve(bot.getLevel(), adena, supplyFor(bot, context)));
	}

	private static ZoneChooser.Situation situation(ColdBot bot, Zone current, Town town, long budget, long now, Context context)
	{
		// The occupancy counts every bot, this one included; take it out of its own zone so it never crowds itself out.
		Map<String, Integer> occupancy = context.occupancy();
		if ((occupancy != null) && (bot.getZone() != null) && occupancy.containsKey(bot.getZone()))
		{
			occupancy = new HashMap<>(occupancy);
			occupancy.merge(bot.getZone(), -1, Integer::sum);
		}
		// After dying twice in a zone it stays away from it, and hunts a little below its level meanwhile.
		final ColdRisk.State risk = bot.getRisk();
		final boolean avoiding = risk.avoiding(now);
		final int level = avoiding ? Math.max(1, bot.getLevel() - ColdRisk.AVOID_LEVEL_PENALTY) : bot.getLevel();
		return new ZoneChooser.Situation(level, bot.getRace(), current, town, budget, occupancy, context.travel().zoneCapacity(), avoiding ? risk.avoidZone() : null);
	}

	private static String planKey(GoalPlanner.Plan plan)
	{
		final StringBuilder key = new StringBuilder("plan:").append(plan.chosen().key());
		for (GoalPlanner.Candidate candidate : plan.considered())
		{
			if (!candidate.available())
			{
				key.append('|').append(candidate.key());
			}
		}
		return key.toString();
	}

	private static String goalLabel(GoalPlanner.Goal goal)
	{
		switch (goal)
		{
			case TOWN:
			{
				return "town";
			}
			case RELOCATE:
			{
				return "relocate";
			}
			default:
			{
				return "hunting";
			}
		}
	}

	private static String describe(SupplyPlanner.Purchase bought, int tierAfter, boolean spiritshots)
	{
		final StringBuilder sb = new StringBuilder();
		if (bought.escapes() > 0)
		{
			sb.append(DecisionLog.num(bought.escapes())).append(bought.escapes() == 1 ? " Scroll of Escape" : " Scrolls of Escape");
		}
		if (bought.potions() > 0)
		{
			sb.append((sb.length() > 0) ? ", " : "").append(DecisionLog.num(bought.potions())).append(bought.potions() == 1 ? " potion" : " potions");
		}
		if (bought.soulshots() > 0)
		{
			sb.append((sb.length() > 0) ? ", " : "").append(DecisionLog.num(bought.soulshots())).append(spiritshots ? " spiritshots" : " soulshots");
		}
		if (bought.upgraded())
		{
			sb.append((sb.length() > 0) ? ", " : "").append("gear tier ").append(tierAfter);
		}
		return sb.toString();
	}

	private static String minutes(long ms)
	{
		final long seconds = Math.max(0L, ms) / 1000L;
		if (seconds < 90)
		{
			return seconds + " s";
		}
		return Math.round(seconds / 60.0) + " min";
	}

	/**
	 * @return where a bot walking into this town heads: the grocer, else the arrival point
	 */
	private static Point shopsOf(Town town)
	{
		return (town.grocer() != null) ? town.grocer() : town.arrival();
	}

	/**
	 * @return whether a trip to this town uses a Scroll of Escape: the bot has one and the town is not close enough to
	 *         walk, or is across the water (the same rule the trip itself follows)
	 */
	private static boolean usesEscape(ColdBot bot, Town town, Context context)
	{
		final Point here = point(bot);
		return (bot.getEscapes() > 0) && ((here.distance(shopsOf(town)) > context.travel().escapeMinDistance()) || !context.catalog().sameLand(here, shopsOf(town)));
	}

	/**
	 * @return what a bot may buy in this town: only pieces it is allowed to wear, so a shop list can never put gear on a
	 *         bot that the gear filter keeps off players
	 */
	private static List<LivingGear.Offer> offersIn(GearShop shop, Town town)
	{
		final List<LivingGear.Offer> offers = new ArrayList<>();
		for (LivingGear.Offer offer : shop.offers(town))
		{
			if (shop.wearable(offer.piece().itemId()))
			{
				offers.add(offer);
			}
		}
		return offers;
	}

	/**
	 * The town a bot hunting in a zone goes to: the nearest one with shops on its own land. On an island without such a
	 * town, the cheapest town whose gatekeeper goes to the zone, as the way it came (FPC-277).
	 */
	private static Town townFor(ColdBot bot, Zone zone, ZoneCatalog catalog)
	{
		final Point here = point(bot);
		final Town town = catalog.nearestShoppingTown(here);
		if ((town == null) || (zone == null) || catalog.sameLand(here, town.arrival()))
		{
			return town;
		}
		Town best = null;
		long bestFee = Long.MAX_VALUE;
		for (ZoneCatalog.Teleport teleport : zone.teleports())
		{
			final Town candidate = catalog.town(teleport.town());
			if ((candidate != null) && candidate.hasGrocer() && (teleport.fee() < bestFee))
			{
				best = candidate;
				bestFee = teleport.fee();
			}
		}
		return (best != null) ? best : town;
	}

	private static Point point(ColdBot bot)
	{
		return new Point(bot.getX(), bot.getY(), bot.getZ());
	}

	private static void moveTo(ColdBot bot, Point point)
	{
		bot.setX(point.x());
		bot.setY(point.y());
		bot.setZ(point.z());
	}
}
