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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Town;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.zone.type.WaterZone;

/**
 * Phase 3, the hot/cold handoff. This is the orchestration seam: on a scan (driven by {@link LivingPopulationManager} on
 * its own thread) it materializes cold bots that a real player has walked up to into real, persistent characters through
 * {@link PhantomManager}, and cools them back into the cold row when the player leaves. The pure geometry/time decisions
 * live in {@link HandoffPolicy}; this class supplies the world state and the side effects.
 *
 * <p><b>Threading.</b> {@link #tick(List, long)} runs on the module's single resolver thread, so every read and write of a
 * {@link ColdBot} decision field is serialized there. The only game-thread work - creating and removing {@link Player}
 * objects, which is never safe off the game thread - is dispatched to {@link ThreadPool}. Those dispatched tasks touch a
 * bot only while it is hot-locked (the resolver skips locked bots) and never clear the lock themselves: they hand the
 * end of the transition (unlock, phase, row write) back to the resolver thread through a queue it drains before it
 * resolves or scans any bot. So only the resolver thread ever turns a locked bot cold, and it cannot race its own writes.
 * Every cross-thread field on {@link ColdBot} is {@code volatile}.
 *
 * <p><b>Duplicate, relog and teleport safety.</b> A bot can be in exactly one of three states: cold (no entry), pending
 * (spawn dispatched, in {@code _pending}), or hot (in {@code _hot}). Activation requires the bot be in none of the
 * transient sets and not hot-locked, and {@link PhantomManager} additionally refuses a second live instance of the same
 * character id, so no player, tick, or relog can double-spawn a bot. A player relog or teleport simply moves where the
 * observers are: bots the player left cool after the grace window, bots the player arrives next to activate.
 */
public class HotColdHandoff
{
	private static final Logger LOGGER = Logger.getLogger(HotColdHandoff.class.getName());

	/** After a failed materialization, do not retry the same bot for this long, so a persistent failure cannot spin. */
	private static final long SPAWN_RETRY_BACKOFF_MS = 60_000L;
	// How often the handoff looks for a character it no longer owns (see adoptOrphans). One is taken back only when two
	// looks in a row find it, so a spawn or a cooldown that is still finishing is never mistaken for one.
	private static final long ORPHAN_SWEEP_MS = 30_000L;

	// Phase 5 hot travel. A walking leg ends when the live character is this close to its target.
	private static final double ARRIVE_RANGE = 250.0;
	// Each move order covers at most this much of a long walk, so pathfinding only ever solves a short hop. A target
	// within DIRECT_WALK_RANGE is walked to in one order. When the straight heading is blocked, headings turned to either
	// side and shorter hops are tried.
	private static final double WAYPOINT_STEP = 1500.0;
	private static final double DIRECT_WALK_RANGE = 2500.0; // the engine skips pathfinding for a player's move over 3000
	private static final double[] WAYPOINT_STEPS =
	{
		WAYPOINT_STEP,
		800.0
	};
	private static final double[] WAYPOINT_TURNS =
	{
		0.0,
		Math.toRadians(30),
		Math.toRadians(-30),
		Math.toRadians(60),
		Math.toRadians(-60),
		Math.toRadians(90),
		Math.toRadians(-90)
	};
	// A route waypoint counts as reached this close, and the walk turns to the next one.
	private static final double ROUTE_POINT_RANGE = 100.0;
	// Within this of a town errand stop (grocer, gatekeeper), the stop counts as reached.
	private static final double ERRAND_RANGE = 150.0;
	// Each bot heads for its own spot this far around an errand stop, so several in town at once do not stack on one
	// point in front of the grocer, and counts it reached within ERRAND_SPOT_RANGE of it.
	private static final double ERRAND_SPREAD_MIN = 50.0;
	private static final double ERRAND_SPREAD_MAX = 110.0;
	private static final double ERRAND_SPOT_RANGE = 40.0;
	// How long past its estimated errand time a live bot may still be walking to the shops before it moves on anyway.
	private static final long ERRAND_OVERTIME_MS = 180_000L;
	// A walk that gains no ground for this long is stuck on terrain: the character is placed at its next waypoint.
	private static final long STUCK_MS = 30_000L;
	// A bot attacked on the road fights back, but returns to its route after this long even if something keeps aggroing it.
	private static final long FIGHT_LIMIT_MS = 90_000L;
	// A dead live bot lies this long before it stands up in a town, like a player pressing "to village".
	private static final long REVIVE_DELAY_MS = 15_000L;
	// The server's "to village" point is used when a town with shops is this close to it; otherwise the bot wakes at that town.
	private static final double REVIVE_TOWN_RANGE = 5000.0;
	// A leg that starts this far from the character begins with a teleport (a gatekeeper), not a walk.
	private static final double TELEPORT_START_RANGE = 3000.0;
	// A player further above or below than this (one world region's height) is on another floor or level and cannot see
	// the bot, however close it is on the map.
	private static final int OBSERVER_Z_RANGE = World.Z_REGION_SIZE;

	private static class HotEntry
	{
		private final ColdBot _bot; // the cold row this hot character belongs to (for shutdown capture)
		private volatile Player _player; // null while the spawn is still in flight
		private volatile long _lastNearAt;
		// Hot travel state, touched only by the life step on the game thread (under the bot's monitor).
		private String _applied; // the activity the live character was last put into (null until the first life step)
		private int _errandStep; // in town: 0 heading to the grocer, 1 to the gatekeeper, 2 done
		private double _bestDistance = Double.MAX_VALUE; // closest it has come to the current walk target
		private long _progressAt; // when it last gained ground
		private long _fightingSince; // when it started fighting back on the road (0 = not fighting)
		private long _deadSince; // when the live character died (0 = alive)
		// The planned route of the current walk (FPC-278): its waypoints, the next one, and what it leads to.
		private List<Point> _route;
		private int _routeIndex;
		private Point _routeTarget;
		private boolean _routeFailed; // no route could be planned from here: the straight walk is used until it moves on
		private boolean _replanned; // stuck once on this route and planned again; stuck again places it at its next waypoint

		private HotEntry(ColdBot bot)
		{
			_bot = bot;
		}
	}

	private final ColdBotDao _dao;
	private final ConcurrentHashMap<Long, HotEntry> _hot = new ConcurrentHashMap<>();
	// Bots serving in a player's party: they stay live however far from players, and the party drives them (no life step).
	private final Set<Long> _partied = ConcurrentHashMap.newKeySet();
	private final Set<Long> _pending = ConcurrentHashMap.newKeySet();
	// Transitions back to cold finished by the game thread, handed to the resolver thread to apply. The resolver is the
	// only thread that changes a bot from hot-locked to cold, so it never races its own writes to that bot.
	private final ConcurrentLinkedQueue<Runnable> _completions = new ConcurrentLinkedQueue<>();
	private final ConcurrentHashMap<Long, Long> _retryAfter = new ConcurrentHashMap<>();
	// Bumped on every stop, so an activation dispatched in a previous run cannot register its (now stale) ColdBot into a
	// fresh run's tracking. A boolean flag is not enough because a restart resets it before the old async spawn runs.
	private final AtomicLong _generation = new AtomicLong();
	// Held while a run starts or stops, and while a task checks it still belongs to the current run and writes, so a
	// restart can never slip in between that check and the write (see inRun).
	private final Object _runLock = new Object();
	// Characters taken back from an earlier run or a failed stop, whose bots stay hot-locked until they are gone.
	private final Set<Long> _orphans = ConcurrentHashMap.newKeySet();
	private volatile Set<Long> _orphanSeen = new HashSet<>(); // found by the last look (resolver thread; reset on stop)
	private volatile long _nextOrphanSweepAt;

	private volatile HandoffPolicy.Params _params;
	private volatile int _gearTierLevelStep;
	private volatile boolean _stopped;
	private volatile ColdLife.Context _life; // Phase 5 travel for hot bots; null when travel is off
	private final AtomicBoolean _refreshing = new AtomicBoolean(); // one hot refresh at a time, never overlapping

	public HotColdHandoff(ColdBotDao dao)
	{
		_dao = dao;
	}

	/**
	 * Opens a new run before its rows are loaded. This is deliberately separate from {@link #configure}: once startup
	 * begins, callbacks from an older generation must no longer be allowed to persist stale rows while this run is
	 * loading and repairing them.
	 */
	public void beginRun()
	{
		synchronized (_runLock)
		{
			_stopped = false;
		}
	}

	/**
	 * Sets (or updates) the tuning. Until this is called the handoff is inert. {@link #beginRun()} owns the lifecycle
	 * boundary; configuration must not reopen a stopped generation after row loading has already begun.
	 * @param params the handoff tuning
	 * @param gearTierLevelStep levels per owned gear tier, used to gate a materialized bot's gear to what it has bought
	 */
	public void configure(HandoffPolicy.Params params, int gearTierLevelStep)
	{
		_params = params;
		_gearTierLevelStep = gearTierLevelStep;
	}

	/**
	 * Sets the travel world the hot bots live in (refreshed by the manager every resolver tick, so zone occupancy stays
	 * current). With travel on, a hot bot decides, travels and shops exactly like a cold one, but visibly.
	 * @param life the travel context, or null when travel is off (hot bots then only hunt)
	 */
	public void setLife(ColdLife.Context life)
	{
		_life = life;
	}

	/**
	 * @return the number of bots currently hot (materialized in the world)
	 */
	public int hotCount()
	{
		return _hot.size();
	}

	/**
	 * What each hot bot's live character is wearing, for the monitoring snapshot. Read-only; a bot whose character is
	 * still spawning is left out.
	 * @return by bot id, the equipped items as {slot, item name with enchant}
	 */
	public Map<Long, List<String[]>> hotEquipment()
	{
		final Map<Long, List<String[]>> result = new HashMap<>();
		for (Map.Entry<Long, HotEntry> entry : _hot.entrySet())
		{
			final Player player = entry.getValue()._player;
			if (player == null)
			{
				continue;
			}
			final List<String[]> items = new ArrayList<>();
			for (Item item : player.getInventory().getPaperdollItems())
			{
				final String name = item.getTemplate().getName() + ((item.getEnchantLevel() > 0) ? (" +" + item.getEnchantLevel()) : "");
				items.add(new String[]
				{
					slotName(item.getTemplate().getBodyPart()),
					name
				});
			}
			result.put(entry.getKey(), items);
		}
		return result;
	}

	private static String slotName(BodyPart part)
	{
		switch (part)
		{
			case R_HAND:
			case LR_HAND:
			{
				return "weapon";
			}
			case L_HAND:
			{
				return "shield";
			}
			case HEAD:
			{
				return "head";
			}
			case CHEST:
			{
				return "chest";
			}
			case FULL_ARMOR:
			{
				return "full armor";
			}
			case LEGS:
			{
				return "legs";
			}
			case GLOVES:
			{
				return "gloves";
			}
			case FEET:
			{
				return "feet";
			}
			case NECK:
			{
				return "necklace";
			}
			case R_EAR:
			case L_EAR:
			case LR_EAR:
			{
				return "earring";
			}
			case R_FINGER:
			case L_FINGER:
			case LR_FINGER:
			{
				return "ring";
			}
			case BACK:
			{
				return "cloak";
			}
			case UNDERWEAR:
			{
				return "underwear";
			}
			default:
			{
				return "other";
			}
		}
	}

	/**
	 * One handoff scan. Cools hot bots whose watching player has left (or gone) and activates cold bots a player has
	 * approached. Runs on the module resolver thread.
	 * @param bots the current cold population
	 * @param now the scan time in epoch milliseconds
	 */
	public void tick(List<ColdBot> bots, long now)
	{
		drainCompletions();
		final HandoffPolicy.Params params = _params;
		if (params == null)
		{
			return;
		}

		final Observers observers = observers();

		// 1) Cool bots no longer watched, or whose character has left the world for good.
		for (ColdBot bot : bots)
		{
			final HotEntry entry = _hot.get(bot.getId());
			if (entry == null)
			{
				continue;
			}

			final Player player = entry._player;
			if (player == null)
			{
				continue; // spawn still in flight; decided next tick
			}

			if (World.getInstance().findObject(player.getObjectId()) == null)
			{
				// The character left the world outside our control (e.g. a hard cleanup). Capture what we can from the
				// retained object and release the lock so the cold resolver resumes; the persistent row is kept.
				releaseGone(bot, player);
				continue;
			}

			final double nearest = observers.nearest(player.getX(), player.getY(), player.getZ());
			if (_partied.contains(bot.getId()) || HandoffPolicy.isStillWatched(nearest, params.deactivationRadius()))
			{
				entry._lastNearAt = now;
				continue;
			}

			if (HandoffPolicy.shouldCool(entry._lastNearAt, now, params.cooldownGraceMs()))
			{
				cool(bot, player, now);
			}
		}

		// 2) Take back characters no run owns any more (an earlier run's failed cooldown, a stop that could not remove one).
		adoptOrphans(bots, now);

		// 3) Activate cold bots a real player has approached (respecting the hot ceiling). Count in-flight spawns
		// (_pending) as well as live ones (_hot): activation is asynchronous, so within a single scan _hot has not grown
		// yet and gating on it alone would let one tick dispatch far more than maxHotBots.
		for (ColdBot bot : bots)
		{
			if ((_hot.size() + _pending.size()) >= params.maxHotBots())
			{
				break;
			}
			if (bot.isHotLock() || _hot.containsKey(bot.getId()) || _pending.contains(bot.getId()) || _orphans.contains(bot.getId()))
			{
				continue;
			}

			final Long retry = _retryAfter.get(bot.getId());
			if ((retry != null) && (now < retry.longValue()))
			{
				continue;
			}

			final double nearest = observers.nearest(bot.getX(), bot.getY(), bot.getZ());
			if (HandoffPolicy.shouldActivate(nearest, params.activationRadius()))
			{
				bot.getDecisions().add(now, null, "Going hot: a player is " + DecisionLog.num(Math.round(nearest)) + " units away (within " + DecisionLog.num(Math.round(params.activationRadius())) + "). Becomes a live character");
				activate(bot, now);
			}
		}

		// Keep hot bots' monitoring rows roughly current so the .livingpop status, the JSON snapshot and the monitor tab
		// reflect a watched bot's leveling while it is hot, not only after it cools.
		refreshHotRows();
	}

	/**
	 * Refreshes hot bots from their live characters and, with travel on, runs their life step. Dispatched to the game
	 * thread (it reads and drives {@link Player} state). Without travel it only keeps the monitoring fields (level,
	 * sub-level exp, adena) current and writes a row only when a value changed. It never touches the hot lock: the
	 * authoritative capture still happens on cooldown. Runs are never allowed to overlap.
	 */
	private void refreshHotRows()
	{
		if (_hot.isEmpty() || !_refreshing.compareAndSet(false, true))
		{
			return;
		}
		ThreadPool.execute(() ->
		{
			try
			{
				final long now = System.currentTimeMillis();
				final ColdLife.Context life = _life;
				for (HotEntry entry : _hot.values())
				{
					final Player player = entry._player;
					final ColdBot bot = entry._bot;
					if ((player == null) || (bot == null) || (World.getInstance().findObject(player.getObjectId()) == null))
					{
						continue;
					}
					try
					{
						synchronized (bot)
						{
							if (_hot.get(bot.getId()) != entry)
							{
								continue; // cooled or re-tracked since this run began
							}
							final boolean leveled = player.getLevel() > bot.getLevel();
							if (leveled)
							{
								bot.getDecisions().add(now, null, "Reached level " + player.getLevel() + " while hot (fighting for real)");
							}
							if (life != null)
							{
								capture(bot, player, now);
								if (!_partied.contains(bot.getId()))
								{
									live(entry, player, bot, life, now);
								}
								bot.setUpdatedAt(now);
								_dao.update(bot);
								continue;
							}
							final int level = player.getLevel();
							final long into = Math.max(0L, player.getExp() - ExperienceData.getInstance().getExpForLevel(level));
							final long adena = player.getAdena();
							if ((level != bot.getLevel()) || (into != bot.getExpIntoLevel()) || (adena != bot.getAdena()))
							{
								bot.setLevel(level);
								bot.setExpIntoLevel(into);
								bot.setAdena(adena);
								bot.setUpdatedAt(now);
								_dao.update(bot);
							}
						}
					}
					catch (Exception e)
					{
						LOGGER.log(Level.FINE, "LivingPopulation: hot-row refresh failed for bot " + bot.getId() + ": " + e.getMessage(), e);
					}
				}
			}
			finally
			{
				_refreshing.set(false);
			}
		});
	}

	/**
	 * Phase 5 for a hot bot: the same decisions a cold bot makes ({@link ColdLife}), acted out by the live character. It
	 * hunts until it decides to go to town, then casts a Scroll of Escape (or walks), runs its errands at the grocer and
	 * the gatekeeper, sometimes sits AFK, and teleports or walks to its next zone. Walks end on arrival, not on a timer,
	 * since a live character follows the terrain. Game thread, under the bot's monitor; the row was just captured.
	 */
	private void live(HotEntry entry, Player player, ColdBot bot, ColdLife.Context life, long now)
	{
		final PhantomManager phantoms = PhantomManager.getInstance();
		if (player.isDead())
		{
			// Like a player: lie there a moment, then stand up in the nearest town and recover there.
			phantoms.claimLivingDeath(player);
			if (entry._deadSince == 0)
			{
				entry._deadSince = now;
			}
			else if ((now - entry._deadSince) >= REVIVE_DELAY_MS)
			{
				reviveInTown(entry, player, bot, life, now);
			}
			return;
		}
		entry._deadSince = 0;
		final String activity = (bot.getActivity() == null) ? ColdLife.HUNTING : bot.getActivity();

		if (ColdLife.isHunting(activity))
		{
			if (!ColdLife.HUNTING.equals(entry._applied))
			{
				phantoms.setLivingIdle(player, false);
				entry._applied = ColdLife.HUNTING;
			}
			if (!phantoms.isLivingEngaged(player))
			{
				step(entry, player, bot, life, now); // never plan a trip in the middle of a fight
			}
			return;
		}

		final TravelLeg leg = bot.getLeg();
		final boolean walking = ColdLife.WALKING_TO_TOWN.equals(activity) || ColdLife.TO_ZONE.equals(activity);
		if (walking && phantoms.isLivingEngaged(player))
		{
			// Attacked on the road: fight back like a player would, for a while, then carry on even if still harassed
			// (the fight clock only resets once it is out of combat, so it cannot be held in a fight forever).
			if (entry._fightingSince == 0)
			{
				entry._fightingSince = now;
				phantoms.setLivingIdle(player, false);
				entry._applied = null;
				entry._route = null; // the fight moves it off its route: plan again from where it ends
			}
			// The fight clock only lets it walk off once nothing is still going for it: walking away from an attacker
			// drags the fight along the road (and made the stuck check hop it forward mid-fight).
			if (((now - entry._fightingSince) < FIGHT_LIMIT_MS) || PhantomManager.isLivingUnderAttack(player))
			{
				return;
			}
		}
		else
		{
			entry._fightingSince = 0;
		}
		if (ColdLife.ESCAPING.equals(activity) && (leg != null) && PhantomManager.isLivingUnderAttack(player))
		{
			// A monster hit it while it read the scroll: the cast breaks, it fights, and it reads the scroll again after.
			if (entry._applied != null)
			{
				phantoms.setLivingIdle(player, false);
				entry._applied = null;
			}
			bot.setLeg(TravelLeg.stay(leg.from(), now, leg.durationMs()));
			return;
		}
		if (entry._applied == null)
		{
			// First step for this activity on this character (just materialized mid-trip, or back from a fight).
			phantoms.setLivingIdle(player, true);
			if (ColdLife.ESCAPING.equals(activity) && (leg != null))
			{
				phantoms.livingCastEscape(player, (int) Math.min(Integer.MAX_VALUE, leg.durationMs())); // reads the scroll again
			}
			if (ColdLife.AFK.equals(activity) || ColdLife.RESTING.equals(activity) || ColdLife.DEAD.equals(activity))
			{
				phantoms.livingSit(player, true); // AFK, resting, or back on its feet in town after dying while cold
			}
			resetProgress(entry, now);
			entry._applied = activity;
		}
		if (leg == null)
		{
			step(entry, player, bot, life, now); // ColdLife puts a bot without its timing back to hunting
			return;
		}

		switch (activity)
		{
			case ColdLife.WALKING_TO_TOWN:
			case ColdLife.TO_ZONE:
			{
				if (walkToward(entry, player, leg.to(), now, ARRIVE_RANGE))
				{
					// Arrived: close the leg now whatever its estimated time said, and take the next decision.
					bot.setLeg(new TravelLeg(leg.from(), leg.to(), leg.startAt(), Math.min(leg.endAt(), now)));
					step(entry, player, bot, life, now);
				}
				break;
			}
			case ColdLife.IN_TOWN:
			{
				runErrands(entry, player, bot, life, now);
				// A live bot shops only once it has actually walked to the grocer and the gatekeeper (the timer is the
				// cold estimate; streets are longer than a straight line), with an overtime cap so it can never hang.
				if (leg.done(now) && ((entry._errandStep >= 2) || (now > (leg.endAt() + ERRAND_OVERTIME_MS))))
				{
					step(entry, player, bot, life, now);
				}
				break;
			}
			case ColdLife.CLASS_MASTER:
			case ColdLife.TRAINER:
			{
				// Walk up to the class master or trainer and talk to it; the change or lesson happens in the step.
				final boolean there = walkToward(entry, player, leg.to(), now, ERRAND_RANGE);
				if (leg.done(now) && (there || (now > (leg.endAt() + ERRAND_OVERTIME_MS))))
				{
					step(entry, player, bot, life, now);
				}
				break;
			}
			default:
			{
				// Casting the scroll, AFK, or paying the gatekeeper to the class master's town: stay put until the time is up.
				if (leg.done(now))
				{
					step(entry, player, bot, life, now);
				}
				break;
			}
		}
	}

	/** Runs one {@link ColdLife} decision on the row, then acts the change out on the live character. */
	private void step(HotEntry entry, Player player, ColdBot bot, ColdLife.Context life, long now)
	{
		final PhantomManager phantoms = PhantomManager.getInstance();
		final String before = (bot.getActivity() == null) ? ColdLife.HUNTING : bot.getActivity();
		final long adena = bot.getAdena();
		final long soulshots = bot.getSoulshots();
		final long potions = bot.getPotions();
		final long escapes = bot.getEscapes();
		final int gearTier = bot.getGearTier();
		final int classId = bot.getClassId();
		final String skills = bot.getSkills();
		final String gear = bot.getGear();

		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, now, 0L, life, events);
		bot.getDecisions().addTick(now, events);

		final String after = (bot.getActivity() == null) ? ColdLife.HUNTING : bot.getActivity();
		final TravelLeg leg = bot.getLeg();
		boolean teleported = false;
		if (!after.equals(before))
		{
			if (ColdLife.ESCAPING.equals(before) || ColdLife.TO_TOWN.equals(before))
			{
				// The cast is over, or the gatekeeper was paid: appear at the town's arrival point (the row already moved there).
				phantoms.livingTeleport(player, bot.getX(), bot.getY(), bot.getZ());
				teleported = true;
			}
			switch (after)
			{
				case ColdLife.ESCAPING:
				{
					phantoms.setLivingIdle(player, true);
					phantoms.livingCastEscape(player, (int) Math.min(Integer.MAX_VALUE, (leg == null) ? 0L : leg.durationMs()));
					break;
				}
				case ColdLife.WALKING_TO_TOWN:
				{
					phantoms.setLivingIdle(player, true);
					break;
				}
				case ColdLife.AFK:
				case ColdLife.RESTING:
				case ColdLife.DEAD:
				{
					phantoms.setLivingIdle(player, true);
					phantoms.livingSit(player, true);
					break;
				}
				case ColdLife.IN_TOWN:
				{
					phantoms.setLivingIdle(player, true);
					phantoms.livingSit(player, false);
					entry._errandStep = 0;
					break;
				}
				case ColdLife.CLASS_MASTER:
				case ColdLife.TRAINER:
				case ColdLife.TO_TOWN:
				{
					phantoms.setLivingIdle(player, true);
					phantoms.livingSit(player, false);
					break;
				}
				case ColdLife.TO_ZONE:
				{
					phantoms.livingSit(player, false);
					if ((leg != null) && (HandoffPolicy.planarDistance(player.getX(), player.getY(), leg.from().x(), leg.from().y()) > TELEPORT_START_RANGE))
					{
						// Paid a gatekeeper: teleport to the destination's arrival point, then walk to a hunting spot.
						phantoms.livingTeleport(player, leg.from().x(), leg.from().y(), leg.from().z());
						teleported = true;
					}
					break;
				}
				default:
				{
					phantoms.setLivingIdle(player, false); // arrived at its zone: hunt
					break;
				}
			}
			entry._applied = after;
			resetProgress(entry, now);
		}
		else if (ColdLife.IN_TOWN.equals(after))
		{
			entry._errandStep = 2; // could not afford a way on yet: waits by the gatekeeper
		}

		// Changed class at the class master: the live character takes the new class, its skills and its rotation.
		if (classId != bot.getClassId())
		{
			phantoms.livingSetClass(player, bot.getClassId(), SkillPlanner.decode(bot.getSkills()));
		}
		// Learned at its trainer: the live character gets the skills and pays the SP.
		if ((bot.getSkills() != null) && !bot.getSkills().equals(skills))
		{
			phantoms.livingSyncSkills(player, SkillPlanner.decode(bot.getSkills()), bot.getSp());
		}

		// Bought gear piece by piece: the live character puts on what the row now says it wears (the old pieces were sold).
		final boolean slots = (life.gear() != null) && (bot.getGear() != null);
		if (slots && !bot.getGear().equals(gear))
		{
			phantoms.livingWearGear(player, LivingGear.items(LivingGear.decode(bot.getGear())));
		}
		// Shopping, a gatekeeper fee or a new potion grade for its level: bring the live inventory in line with the row.
		final int gearTierNow = bot.getGearTier();
		if ((adena != bot.getAdena()) || (soulshots != bot.getSoulshots()) || (potions != bot.getPotions()) || (escapes != bot.getEscapes()) || (gearTier != gearTierNow))
		{
			final int step = _gearTierLevelStep;
			final int gearLevel = ((gearTierNow <= 0) || (step <= 0)) ? 0 : Math.min(bot.getLevel(), gearTierNow * step);
			// A whole-tier upgrade swaps the visible set; with gear kept per slot the pieces were put on above.
			phantoms.livingSyncSupplies(player, bot.getAdena(), gearLevel, !slots && (gearTier != gearTierNow), bot.getSoulshots(), LivingSupplies.potionIdFor(bot.getLevel()), bot.getPotions(), bot.getEscapes());
		}
		if (teleported)
		{
			clearRoute(entry); // a route planned before the teleport starts somewhere else
			// Teleported away from whoever was watching: let the next scan cool it at once if nobody is near the new spot.
			entry._lastNearAt = 0L;
		}
	}

	/** In town: walk to the grocer, then to the gatekeeper, pausing at each while the errand timer runs. */
	private void runErrands(HotEntry entry, Player player, ColdBot bot, ColdLife.Context life, long now)
	{
		final Town town = life.catalog().town(bot.getTown());
		if ((town == null) || (entry._errandStep >= 2))
		{
			return;
		}
		final Point stop = (entry._errandStep == 0) ? town.grocer() : town.gatekeeper();
		if (stop == null)
		{
			entry._errandStep++;
			return;
		}
		// Its own spot around the stop; once near the stop it is also there when it stands still (the spot may be behind
		// a counter or a wall, as far as the path lets it go).
		final Point spot = errandSpot(bot, stop);
		final boolean standingNear = !player.isMoving() && (HandoffPolicy.planarDistance(player.getX(), player.getY(), stop.x(), stop.y()) <= ERRAND_RANGE);
		// The same walking as on the road: an arrival point can be thousands of units from the shops (Dwarven Village).
		if (standingNear || walkToward(entry, player, spot, now, ERRAND_SPOT_RANGE))
		{
			entry._errandStep++;
			resetProgress(entry, now);
		}
	}

	/**
	 * @return where around a town errand stop this bot stands: the same spot every visit, at its own angle and distance
	 *         from the stop
	 */
	static Point errandSpot(ColdBot bot, Point stop)
	{
		final long mixed = (bot.getId() * 0x9E3779B97F4A7C15L) ^ (bot.getId() >>> 17);
		final double angle = ((mixed >>> 11) & 0xFFFF) * ((2 * Math.PI) / 0x10000);
		final double radius = ERRAND_SPREAD_MIN + ((((mixed >>> 33) & 0xFF) / 255.0) * (ERRAND_SPREAD_MAX - ERRAND_SPREAD_MIN));
		return new Point((int) Math.round(stop.x() + (Math.cos(angle) * radius)), (int) Math.round(stop.y() + (Math.sin(angle) * radius)), stop.z());
	}

	/**
	 * Keeps the live character walking toward a target the way a player would: along a route planned around water and
	 * over bridges ({@link LivingRoute}), one stretch at a time, the engine's pathfinding handling each short stretch. A
	 * long walk is planned in pieces. Where there is no terrain data to plan on, the old walk is used: a target close
	 * enough is walked to directly, a far one one waypoint at a time on the straight heading or headings to either side. A
	 * walk that stops gaining ground is planned again, and if it stays stuck the character is placed at its next waypoint
	 * (never in water) and carries on.
	 * @param arriveRange how close counts as there
	 * @return whether it has arrived
	 */
	private boolean walkToward(HotEntry entry, Player player, Point target, long now, double arriveRange)
	{
		final double distance = HandoffPolicy.planarDistance(player.getX(), player.getY(), target.x(), target.y());
		if (distance <= arriveRange)
		{
			clearRoute(entry);
			return true;
		}
		if (!target.equals(entry._routeTarget))
		{
			clearRoute(entry);
			entry._routeTarget = target;
			resetProgress(entry, now);
		}
		if (PhantomManager.isLivingUnderAttack(player))
		{
			resetProgress(entry, now); // held up by a fight, not by the terrain: never hop it away mid-fight
			entry._route = null; // a fight moves it off its route: plan again from where it ends
			return false;
		}
		final GeoTerrain terrain = new GeoTerrain(player.getInstanceId());
		if ((entry._route == null) && !entry._routeFailed)
		{
			entry._route = LivingRoute.plan(terrain, new Point(player.getX(), player.getY(), player.getZ()), target);
			entry._routeIndex = 0;
			entry._routeFailed = entry._route == null;
			resetProgress(entry, now);
		}
		if (entry._route != null)
		{
			return followRoute(entry, player, terrain, now);
		}
		return walkStraight(entry, player, target, distance, terrain, now);
	}

	/** One tick along the planned route. */
	private boolean followRoute(HotEntry entry, Player player, GeoTerrain terrain, long now)
	{
		final PhantomManager phantoms = PhantomManager.getInstance();
		Point next = entry._route.get(entry._routeIndex);
		double toNext = HandoffPolicy.planarDistance(player.getX(), player.getY(), next.x(), next.y());
		boolean advanced = false;
		while ((toNext <= ROUTE_POINT_RANGE) && (entry._routeIndex < (entry._route.size() - 1)))
		{
			entry._routeIndex++;
			next = entry._route.get(entry._routeIndex);
			toNext = HandoffPolicy.planarDistance(player.getX(), player.getY(), next.x(), next.y());
			advanced = true;
		}
		if (toNext <= ROUTE_POINT_RANGE)
		{
			entry._route = null; // the end of a partial plan: plan the rest from here on the next tick
			resetProgress(entry, now);
			return false;
		}
		if (advanced)
		{
			resetProgress(entry, now);
		}
		else if (toNext < (entry._bestDistance - 50.0))
		{
			entry._bestDistance = toNext;
			entry._progressAt = now;
		}
		if ((now - entry._progressAt) > STUCK_MS)
		{
			if (!entry._replanned)
			{
				entry._replanned = true; // first plan again from where it stands
				entry._route = null;
			}
			else
			{
				entry._replanned = false;
				if (!terrain.water(next.x(), next.y(), next.z()))
				{
					phantoms.livingTeleport(player, next.x(), next.y(), next.z());
				}
				entry._route = null;
			}
			resetProgress(entry, now);
			return false;
		}
		if (player.isMoving() && !advanced)
		{
			return false;
		}
		if (!phantoms.livingMoveTo(player, next.x(), next.y(), next.z()))
		{
			entry._route = null; // the stretch cannot be walked after all (a door, a moved obstacle): plan again
		}
		return false;
	}

	/** The walk used where there is no terrain data to plan a route on. */
	private boolean walkStraight(HotEntry entry, Player player, Point target, double distance, GeoTerrain terrain, long now)
	{
		if (distance < (entry._bestDistance - 50.0))
		{
			entry._bestDistance = distance;
			entry._progressAt = now;
		}
		if ((now - entry._progressAt) > STUCK_MS)
		{
			final Point hop = waypoint(player, target, distance, WAYPOINT_STEP, 0.0);
			if (!terrain.water(hop.x(), hop.y(), terrain.height(hop.x(), hop.y(), hop.z())))
			{
				PhantomManager.getInstance().livingTeleport(player, hop.x(), hop.y(), hop.z());
			}
			entry._routeFailed = false; // try planning again from the new spot
			resetProgress(entry, now);
			return false;
		}
		if (player.isMoving())
		{
			return false;
		}
		final PhantomManager phantoms = PhantomManager.getInstance();
		if ((distance <= DIRECT_WALK_RANGE) && phantoms.livingMoveTo(player, target.x(), target.y(), target.z()))
		{
			return false;
		}
		for (double step : WAYPOINT_STEPS)
		{
			for (double turn : WAYPOINT_TURNS)
			{
				final Point hop = waypoint(player, target, distance, step, turn);
				if (phantoms.livingMoveTo(player, hop.x(), hop.y(), hop.z()))
				{
					return false;
				}
			}
		}
		return false; // nothing reachable this tick; the stuck fallback takes over if it stays that way
	}

	private static void clearRoute(HotEntry entry)
	{
		entry._route = null;
		entry._routeIndex = 0;
		entry._routeTarget = null;
		entry._routeFailed = false;
		entry._replanned = false;
	}

	/** The live terrain for route planning: the server's geodata, and its water zones (the sea and the rivers at sea level). */
	private static final class GeoTerrain implements LivingRoute.Terrain
	{
		private final int _instanceId;

		private GeoTerrain(int instanceId)
		{
			_instanceId = instanceId;
		}

		@Override
		public boolean known(int x, int y)
		{
			return GeoEngine.getInstance().hasGeo(x, y);
		}

		@Override
		public int height(int x, int y, int z)
		{
			return GeoEngine.getInstance().getHeight(x, y, z);
		}

		@Override
		public boolean water(int x, int y, int z)
		{
			return ZoneManager.getInstance().getZone(x, y, z, WaterZone.class) != null;
		}

		@Override
		public boolean canWalk(int x, int y, int z, int tx, int ty, int tz)
		{
			return GeoEngine.getInstance().canMoveToTarget(x, y, z, tx, ty, tz, _instanceId);
		}
	}

	/**
	 * Stands a dead live bot up in a town, the way a player does: at the server's own "to village" point when a town with
	 * shops is near it, otherwise at that town. The row then recovers there and runs its errands (see
	 * {@link ColdLife#diedHot}). Game thread, under the bot's monitor.
	 */
	private void reviveInTown(HotEntry entry, Player player, ColdBot bot, ColdLife.Context life, long now)
	{
		final Point where = townPointFor(player, life);
		final List<DecisionLog.Event> events = new ArrayList<>();
		if (ColdLife.diedHot(bot, where, now, life, events) == null)
		{
			return; // no town known: stays down until one is (the catalog always has towns in practice)
		}
		bot.getDecisions().addTick(now, events);
		PhantomManager.getInstance().livingRevive(player, where.x(), where.y(), where.z());
		entry._deadSince = 0;
		entry._fightingSince = 0;
		entry._applied = null; // the next step sits it down to recover
		clearRoute(entry);
		resetProgress(entry, now);
	}

	/** Where a dead character stands up: the server's "to village" point, or the nearest town with shops when that is far from it. */
	private static Point townPointFor(Player player, ColdLife.Context life)
	{
		final Location village = MapRegionData.getInstance().getTeleToLocation(player, TeleportWhereType.TOWN);
		final Point where = (village == null) ? new Point(player.getX(), player.getY(), player.getZ()) : new Point(village.getX(), village.getY(), village.getZ());
		final Town nearest = life.catalog().nearestShoppingTown(where);
		return ((nearest != null) && (nearest.arrival().distance(where) > REVIVE_TOWN_RANGE)) ? nearest.arrival() : where;
	}

	/** A point {@code step} units from the character, on the heading to the target turned by {@code turn} radians. */
	private static Point waypoint(Player player, Point target, double distance, double step, double turn)
	{
		final double length = Math.min(step, distance);
		final double heading = Math.atan2(target.y() - player.getY(), target.x() - player.getX()) + turn;
		final double t = length / Math.max(1.0, distance);
		return new Point((int) Math.round(player.getX() + (Math.cos(heading) * length)), (int) Math.round(player.getY() + (Math.sin(heading) * length)), (int) Math.round(player.getZ() + ((target.z() - player.getZ()) * t)));
	}

	private static void resetProgress(HotEntry entry, long now)
	{
		entry._bestDistance = Double.MAX_VALUE;
		entry._progressAt = now;
	}

	/**
	 * Makes a bot live now, wherever it is, because a player invited it to a party (no player needs to be near, and the
	 * live cap does not apply: a party holds few). Resolver thread. Nothing happens if it is already live or on its way.
	 */
	public void activateForParty(ColdBot bot, long now)
	{
		if (bot.isHotLock() || _hot.containsKey(bot.getId()) || _pending.contains(bot.getId()) || _orphans.contains(bot.getId()))
		{
			return;
		}
		bot.getDecisions().add(now, null, "Going hot: invited to a party. Becomes a live character where it is");
		activate(bot, now);
	}

	/**
	 * Marks a live bot as serving in a player's party (it stays live and the party drives it), or back on its own: it
	 * then picks its plans again from where it stands (a new hunting spot for its level) and cools once no one is near.
	 * Game thread.
	 */
	public void setPartied(ColdBot bot, boolean partied)
	{
		if (partied)
		{
			_partied.add(bot.getId());
			return;
		}
		if (!_partied.remove(bot.getId()))
		{
			return;
		}
		synchronized (bot)
		{
			final HotEntry entry = _hot.get(bot.getId());
			if (entry != null)
			{
				entry._applied = null;
				entry._fightingSince = 0;
				clearRoute(entry);
				resetProgress(entry, System.currentTimeMillis());
			}
			bot.setActivity(ColdLife.HUNTING);
			bot.setLeg(null);
			bot.setZone(null); // far from its old spot now: the next step picks a zone for where it stands
			bot.getDecisions().add(System.currentTimeMillis(), null, "Left the party. Back to its own plans");
		}
	}

	/** @return the travel world for hot bots, or null while travel is off */
	public ColdLife.Context life()
	{
		return _life;
	}

	/** @return whether this bot is serving in a player's party */
	public boolean isPartied(long id)
	{
		return _partied.contains(id);
	}

	/** Locks the bot and dispatches its materialization to the game thread. Called on the resolver thread. */
	private void activate(ColdBot bot, long now)
	{
		if (_stopped)
		{
			return;
		}
		bot.setHotLock(true);
		bot.setPhase("hot");
		bot.setUpdatedAt(now);
		_dao.update(bot);
		_pending.add(bot.getId());

		final long id = bot.getId();
		final int charId = (int) bot.getCharId();
		final String name = bot.getName();
		final int classId = bot.getClassId();
		final int level = Math.max(1, bot.getLevel());
		final long expIntoLevel = Math.max(0L, bot.getExpIntoLevel());
		final long adena = Math.max(0L, bot.getAdena());
		final int gearTier = bot.getGearTier();
		final int step = _gearTierLevelStep;
		// Gate gear to the owned tier: tier 0 (or upgrades disabled) renders the newbie starter; otherwise best-in-grade
		// for the level the owned tier corresponds to, never above the bot's real level.
		final int gearLevel = ((gearTier <= 0) || (step <= 0)) ? 0 : Math.min(level, gearTier * step);
		final long soulshots = Math.max(0L, bot.getSoulshots());
		final long potions = Math.max(0L, bot.getPotions());
		final long escapes = Math.max(0L, bot.getEscapes());
		final int potionId = LivingSupplies.potionIdFor(level);
		// With travel on, a bot caught mid-trip or in town arrives idle: the life step drives it from its first tick.
		final boolean idle = (_life != null) && !ColdLife.isHunting(bot.getActivity());
		final Location location = new Location(bot.getX(), bot.getY(), bot.getZ());
		// With skill training on, it knows only what it learned at its trainer; otherwise every skill of its level.
		final Map<Integer, Integer> skills = ((_life != null) && (_life.skills() != null)) ? SkillPlanner.decode(bot.getSkills()) : null;
		final long sp = Math.max(0L, bot.getSp());
		// With gear kept per slot it wears exactly its row's pieces; a row not converted yet keeps the tier look.
		final List<Integer> gear = ((_life != null) && (_life.gear() != null) && (bot.getGear() != null)) ? LivingGear.items(LivingGear.decode(bot.getGear())) : null;
		final long generation = _generation.get(); // this run's token; a stop bumps it so this callback can detect a restart
		ThreadPool.execute(() ->
		{
			try
			{
				final Player phantom = PhantomManager.getInstance().spawnLivingPopulationPhantom(id, charId, name, classId, level, expIntoLevel, adena, gearLevel, gear, soulshots, potionId, potions, escapes, idle, sp, skills, location);
				if (phantom == null)
				{
					failActivation(generation, bot, "spawn returned null");
					return;
				}

				// This callback runs on the game thread, possibly long after dispatch. If a stop happened since (this run
				// ended, or a new run started on the same singleton), do NOT register it: its ColdBot belongs to the
				// previous generation and the new run loaded fresh rows. The generation check covers the stop+restart case
				// that the _stopped flag alone cannot (a restart resets _stopped).
				final boolean current;
				synchronized (_runLock)
				{
					current = !_stopped && (generation == _generation.get());
					if (current)
					{
						if (charId <= 0)
						{
							bot.setCharId(phantom.getObjectId());
							_dao.update(bot);
						}
						final HotEntry entry = new HotEntry(bot);
						entry._player = phantom;
						entry._lastNearAt = System.currentTimeMillis();
						_hot.put(id, entry);
						_pending.remove(id);
						_retryAfter.remove(id);
					}
				}
				if (!current)
				{
					if (charId <= 0)
					{
						// Freshly created and never bound to a cold row (we return before storing char_id). Hard-delete it,
						// character row included, so it does not leak as an orphan on the living_population account.
						PhantomManager.getInstance().discardLivingPopulationPhantom(phantom);
					}
					else
					{
						// Already-bound existing character: keep its row, just remove the live instance.
						PhantomManager.getInstance().coolLivingPopulationPhantom(phantom);
					}
					// No _pending clean-up: the stop already cleared it, and a new run's entry for this bot is its own.
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: handoff activation failed for bot " + id + ": " + e.getMessage(), e);
				failActivation(generation, bot, e.getMessage());
			}
		});
	}

	/**
	 * Rolls an activation back to cold and arms a short retry backoff, so a repeatable failure cannot spin. Called from
	 * the spawn callback; the roll back itself runs on the resolver thread (see {@link #complete}).
	 */
	private void failActivation(long generation, ColdBot bot, String reason)
	{
		LOGGER.info("LivingPopulation: bot " + bot.getId() + " stays cold (" + reason + ").");
		complete(() -> inRun(generation, () ->
		{
			bot.setPhase("cold");
			bot.setHotLock(false);
			bot.getDecisions().add(System.currentTimeMillis(), null, "Could not go hot (" + reason + "). Stays cold and retries in " + (SPAWN_RETRY_BACKOFF_MS / 1000L) + "s");
			_dao.update(bot);
			_retryAfter.put(bot.getId(), System.currentTimeMillis() + SPAWN_RETRY_BACKOFF_MS);
			_pending.remove(bot.getId());
		}));
	}

	/**
	 * Hands the end of a transition back to cold to the resolver thread, which applies it before it looks at any bot
	 * again. Until then the bot stays hot-locked, so no cold resolve can start on it while the game thread is still
	 * capturing or persisting. Once the simulation has stopped (no resolver any more) it runs right away.
	 * @param transition the change that unlocks the bot, including its row write
	 */
	private void complete(Runnable transition)
	{
		if (_stopped)
		{
			runCompletion(transition);
			return;
		}
		_completions.add(transition);
		if (_stopped)
		{
			drainCompletions(); // stopped while queuing: the resolver is gone, so apply it here
		}
	}

	/**
	 * Applies the transitions the game thread handed back (see {@link #complete}). Called on the resolver thread at the
	 * start of every resolve and handoff tick, and by {@link #shutdown()} once the resolver has stopped.
	 */
	public void drainCompletions()
	{
		Runnable transition;
		while ((transition = _completions.poll()) != null)
		{
			runCompletion(transition);
		}
	}

	private static void runCompletion(Runnable transition)
	{
		try
		{
			transition.run();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not finish a transition back to cold: " + e.getMessage(), e);
		}
	}

	/** Captures the hot character's progress back into the row and despawns it, keeping the persistent character. */
	private void cool(ColdBot bot, Player player, long now)
	{
		final HotEntry existing = _hot.remove(bot.getId()); // eager: no second cool can be scheduled for this bot
		final long generation = _generation.get(); // this run's token: the task below must not join a later run
		ThreadPool.execute(() ->
		{
			try
			{
				// Capture the hot progress into the row first. The bot is still hot-locked here, so the resolver never
				// races these writes; the lock is cleared only after the character is verified gone (below).
				if (World.getInstance().findObject(player.getObjectId()) != null)
				{
					synchronized (bot)
					{
						capture(bot, player, System.currentTimeMillis()); // the shared purse, shots, potions and scrolls
						final ColdLife.Context life = _life;
						if (player.isDead() && (life != null))
						{
							// Died and left sight before standing up: the row recovers in a town, as if it had pressed "to village".
							final long at = System.currentTimeMillis();
							final List<DecisionLog.Event> events = new ArrayList<>();
							if (ColdLife.diedHot(bot, townPointFor(player, life), at, life, events) != null)
							{
								bot.getDecisions().addTick(at, events);
							}
						}
					}
				}

				final boolean removed = PhantomManager.getInstance().coolLivingPopulationPhantom(player);
				if (!removed)
				{
					// The character is still live. Keep it hot-locked (do NOT clear the lock or resume cold) and re-track it
					// so a later scan retries the cooldown. This preserves the "never a live actor the resolver is also
					// advancing" invariant.
					if (!retrack(generation, bot, existing, player, now))
					{
						// The run that owned this bot has stopped. Its character is still tracked by PhantomManager, so the
						// next run finds it and takes it back (adoptOrphans) instead of it staying live with no owner.
						LOGGER.warning("LivingPopulation: bot " + bot.getId() + " could not be cooled and its run has stopped; the next run will take its character back.");
					}
					return;
				}

				// Verified gone: safe to resume cold. The resolver thread unlocks and persists it (see complete()), so the
				// bot is never both unlocked and still being written here, and never unlocked while still live.
				final HandoffPolicy.Params params = _params;
				final String why = (params == null) ? "no player nearby" : ("no player within " + DecisionLog.num(Math.round(params.deactivationRadius())) + " units for " + (params.cooldownGraceMs() / 1000L) + "s");
				// A new run loads this bot's row afresh; inRun keeps this old copy from overwriting it.
				complete(() -> inRun(generation, () ->
				{
					bot.setPhase("cold");
					bot.setUpdatedAt(now);
					bot.setLastResolvedAt(System.currentTimeMillis()); // do not credit offline-style catch-up for hot time
					bot.setHotLock(false);
					bot.getDecisions().add(System.currentTimeMillis(), null, "Cooled back to cold: " + why + ". Kept " + carried(bot));
					_dao.update(bot);
				}));
			}
			catch (Exception e)
			{
				// Unknown failure: be conservative. Keep the bot hot-locked and re-tracked for a retry rather than
				// resuming cold, which could double with a still-live character.
				LOGGER.log(Level.WARNING, "LivingPopulation: handoff cooldown failed for bot " + bot.getId() + ": " + e.getMessage(), e);
				retrack(generation, bot, existing, player, now);
			}
		});
	}

	/**
	 * The character left the world outside our control. Best-effort capture its progress from the retained object (so the
	 * cold row does not regress), release the lock, and ensure the character row is persisted and fully removed. The
	 * capture and cleanup are dispatched to the game thread, honoring the same threading contract as {@link #cool}.
	 */
	private void releaseGone(ColdBot bot, Player player)
	{
		_hot.remove(bot.getId());
		final long generation = _generation.get(); // this run's token: the task below must not join a later run
		ThreadPool.execute(() ->
		{
			try
			{
				// The object has left the world index but we still hold the reference; its cached stats are readable.
				synchronized (bot)
				{
					capture(bot, player, System.currentTimeMillis());
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: could not capture state for departed bot " + bot.getId() + ": " + e.getMessage(), e);
			}
			try
			{
				PhantomManager.getInstance().coolLivingPopulationPhantom(player); // persist the row and ensure removal
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: cleanup of departed bot " + bot.getId() + " failed: " + e.getMessage(), e);
			}
			// The resolver thread unlocks and persists it (see complete()), after the capture and cleanup above.
			complete(() -> inRun(generation, () ->
			{
				bot.setPhase("cold");
				bot.setUpdatedAt(System.currentTimeMillis());
				bot.setLastResolvedAt(System.currentTimeMillis());
				bot.setHotLock(false);
				bot.getDecisions().add(System.currentTimeMillis(), null, "Its live character left the world outside the simulation. Back to cold with " + carried(bot));
				_dao.update(bot);
			}));
			LOGGER.info("LivingPopulation: hot bot " + bot.getId() + " left the world; captured and released to cold.");
		});
	}

	/**
	 * Cools a hot bot now, whoever is near it (the bot is being removed). Called on the resolver thread.
	 * @param id the bot id
	 * @return whether it was hot and its cooldown started
	 */
	public boolean release(long id)
	{
		final HotEntry entry = _hot.get(id);
		if ((entry == null) || (entry._player == null) || (entry._bot == null))
		{
			return false;
		}
		cool(entry._bot, entry._player, System.currentTimeMillis());
		return true;
	}

	/**
	 * @param id a bot id
	 * @return whether the bot is hot or going hot
	 */
	public boolean isHot(long id)
	{
		return _hot.containsKey(id) || _pending.contains(id);
	}

	/**
	 * Puts a bot whose cooldown failed back under watch, so a later scan retries it, but only while the run it belongs to
	 * is still going. Once that run has stopped (and whether or not a new one has started), the old copy must not enter
	 * the tracking: the character stays tracked by PhantomManager and the next run takes it back (see adoptOrphans).
	 * @return whether it was put back
	 */
	private boolean retrack(long generation, ColdBot bot, HotEntry existing, Player player, long now)
	{
		synchronized (_runLock)
		{
			if (_stopped || (generation != _generation.get()))
			{
				return false;
			}
			final HotEntry retry = (existing != null) ? existing : new HotEntry(bot);
			retry._player = player;
			retry._lastNearAt = now;
			_hot.put(bot.getId(), retry);
			return true;
		}
	}

	/**
	 * Runs a task's write only while no later run has started since the task began (a stop followed by a start in the
	 * same server). Between a stop and the next start the old run's writes are still safe, so they go ahead. The check
	 * and the write hold the run lock, so a start cannot slip in between them.
	 * @param generation the run the task was started in
	 * @param write the change to the task's bot
	 * @return whether the write ran
	 */
	private boolean inRun(long generation, Runnable write)
	{
		synchronized (_runLock)
		{
			if ((generation != _generation.get()) && !_stopped)
			{
				return false;
			}
			write.run();
			return true;
		}
	}

	/**
	 * Takes back Living Population characters that no run owns: one whose cooldown failed while the simulation was
	 * restarted, or one a stop could not remove. PhantomManager still tracks them, so a spawn for that bot would refuse
	 * (it is already live) while nothing here would ever cool it. The bot is hot-locked, so the resolver does not advance
	 * it while its character may still be in the world, and its cooldown is retried on every look until the character is
	 * gone; then the bot resumes cold from its row. A character is taken back only when two looks in a row find it
	 * unowned, so a spawn or cooldown of this run that is still finishing is left alone. Called on the resolver thread.
	 */
	private void adoptOrphans(List<ColdBot> bots, long now)
	{
		if (now < _nextOrphanSweepAt)
		{
			return;
		}
		_nextOrphanSweepAt = now + ORPHAN_SWEEP_MS;

		final Map<Long, Player> tracked = PhantomManager.getInstance().livingPopulationPhantoms();
		final Set<Long> seen = new HashSet<>();
		Map<Long, ColdBot> byId = null;
		for (Map.Entry<Long, Player> found : tracked.entrySet())
		{
			final long id = found.getKey();
			final HotEntry entry = _hot.get(id);
			if (((entry != null) && (entry._player == found.getValue())) || _pending.contains(id))
			{
				continue; // owned by this run, or its spawn or retry is still under way
			}
			seen.add(id);
			if (!_orphanSeen.contains(id))
			{
				continue; // first sighting: give a transition that is still finishing its chance
			}
			if (byId == null)
			{
				byId = new HashMap<>();
				for (ColdBot bot : bots)
				{
					byId.put(bot.getId(), bot);
				}
			}
			final ColdBot bot = byId.get(id);
			if ((bot != null) && bot.isHotLock() && !_orphans.contains(id))
			{
				continue; // a transition of this run holds the lock; it finishes on its own
			}
			adopt(id, bot, found.getValue(), now);
		}
		_orphanSeen = seen;
	}

	/** Locks the bot (when it is in this run) and retries its unowned character's cooldown on the game thread. */
	private void adopt(long id, ColdBot bot, Player player, long now)
	{
		if (_stopped)
		{
			return;
		}
		if ((bot != null) && _orphans.add(id))
		{
			bot.setHotLock(true);
			bot.setPhase("hot");
			bot.setUpdatedAt(now);
			bot.getDecisions().add(now, null, "Found its character still in the world with no owner (left over from a restart or a stop). Taking it back");
			_dao.update(bot);
			LOGGER.warning("LivingPopulation: taking back the unowned character of bot " + id + " (objId=" + player.getObjectId() + ").");
		}
		_pending.add(id);
		final long generation = _generation.get();
		ThreadPool.execute(() ->
		{
			boolean removed = false;
			try
			{
				removed = PhantomManager.getInstance().coolLivingPopulationPhantom(player);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: could not remove the unowned character of bot " + id + ": " + e.getMessage(), e);
			}
			final boolean gone = removed;
			complete(() -> inRun(generation, () ->
			{
				_pending.remove(id);
				if (!gone)
				{
					return; // still in the world: the bot stays locked and the next look retries
				}
				_orphans.remove(id);
				if (bot != null)
				{
					bot.setPhase("cold");
					bot.setUpdatedAt(System.currentTimeMillis());
					bot.setLastResolvedAt(System.currentTimeMillis());
					bot.setHotLock(false);
					bot.getDecisions().add(System.currentTimeMillis(), null, "Its unowned character is gone. Back to cold with " + carried(bot));
					_dao.update(bot);
				}
			}));
		});
	}

	/**
	 * Cools every hot bot immediately (module stop). Sets the stopped flag first so no new activation starts and any
	 * in-flight spawn cools itself on completion, then cools the live ones. Must be invoked on the game thread, since it
	 * removes {@link Player} objects inline; {@link LivingPopulationManager#stop()} is the only caller.
	 */
	public void shutdown()
	{
		synchronized (_runLock)
		{
			_stopped = true;
			_generation.incrementAndGet(); // invalidate any activation dispatched in this run but not yet completed
		}
		drainCompletions(); // transitions queued for the resolver, which has stopped: apply them here
		for (Long id : _hot.keySet())
		{
			final HotEntry entry = _hot.remove(id);
			if ((entry == null) || (entry._player == null))
			{
				continue;
			}
			final Player player = entry._player;
			final ColdBot bot = entry._bot;
			try
			{
				// Capture the hot character's progress into the row while it is still hot-locked (safe), then despawn, then
				// clear the lock ONLY if removal is verified. Clearing the lock before removal would leave a cold-eligible
				// row while the character is still live (the same invariant cool() protects).
				if (bot != null)
				{
					if (World.getInstance().findObject(player.getObjectId()) != null)
					{
						synchronized (bot)
						{
							capture(bot, player, System.currentTimeMillis());
						}
					}
				}
				final boolean removed = PhantomManager.getInstance().coolLivingPopulationPhantom(player);
				if (bot != null)
				{
					bot.setPhase("cold");
					// Re-anchor the resolve clock so a resolver tick racing this shutdown cannot cold-simulate the whole
					// hot span again from a stale lastResolvedAt (which still points before activation).
					bot.setLastResolvedAt(System.currentTimeMillis());
					if (removed)
					{
						bot.setHotLock(false); // only once the character is verified gone
						bot.getDecisions().add(System.currentTimeMillis(), null, "Cooled back to cold: the simulation is stopping. Kept " + carried(bot));
					}
					else
					{
						LOGGER.warning("LivingPopulation: shutdown could not remove hot bot " + id + "; leaving it hot-locked; the next run takes its character back.");
					}
					_dao.update(bot);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: shutdown cooldown failed for bot " + id + ": " + e.getMessage(), e);
			}
		}
		// Drop transient tracking so a later start() (after configure() clears _stopped) begins clean.
		drainCompletions();
		_pending.clear();
		_partied.clear();
		_retryAfter.clear();
		// Bots locked for taking back a character are cleared at the next load (see LivingPopulationManager.start), and
		// the character, if still tracked, is found again by the next run.
		_orphans.clear();
		_orphanSeen = new HashSet<>();
		_nextOrphanSweepAt = 0;
	}

	/**
	 * Copies a hot character's progress into its row: level, experience, purse, consumables and position. A trip that was
	 * under way continues from where the character actually stands: a walk is re-timed from here to its target, and a
	 * timed stay (casting, shopping, AFK) keeps its end time. Caller holds the bot's monitor.
	 */
	private void capture(ColdBot bot, Player player, long now)
	{
		final int level = player.getLevel();
		bot.setLevel(level);
		bot.setExpIntoLevel(Math.max(0L, player.getExp() - ExperienceData.getInstance().getExpForLevel(level)));
		bot.setAdena(player.getAdena()); // capture the shared purse (hot earnings/spends) back to the row
		if (bot.getSkills() != null)
		{
			bot.setSp(player.getSp()); // SP earned for real, spent only at the trainer (the row's skills are the source of truth)
		}
		final long[] consumables = PhantomManager.getInstance().captureLivingConsumables(player);
		bot.setSoulshots(consumables[0]); // capture what the bot actually used while hot
		bot.setPotions(consumables[1]);
		bot.setEscapes(consumables[2]);
		bot.setX(player.getX());
		bot.setY(player.getY());
		bot.setZ(player.getZ());
		captureGear(bot, player, now);

		final TravelLeg leg = bot.getLeg();
		if ((leg != null) && !ColdLife.isHunting(bot.getActivity()))
		{
			final Point here = new Point(player.getX(), player.getY(), player.getZ());
			final ColdLife.Context life = _life;
			if (leg.from().equals(leg.to()) || (life == null))
			{
				bot.setLeg(new TravelLeg(here, here, leg.startAt(), leg.endAt()));
			}
			else
			{
				bot.setLeg(TravelLeg.walk(here, leg.to(), now, life.travel().moveSpeed()));
			}
		}
	}

	/**
	 * With gear kept per slot: the live character puts on the better pieces it picked up, the rest of its loot goes into
	 * the row's loot (sold on its next town visit, as a cold bot's is) and leaves the bag, and the row records what it
	 * wears. Game thread, under the bot's monitor.
	 */
	private void captureGear(ColdBot bot, Player player, long now)
	{
		final ColdLife.Context life = _life;
		final ColdLife.GearShop shop = (life == null) ? null : life.gear();
		if ((shop == null) || (bot.getGear() == null))
		{
			return;
		}
		if (World.getInstance().findObject(player.getObjectId()) == null)
		{
			// Left the world outside the simulation: only read what it wears, do not touch its bag.
			ColdLife.setGear(bot, PhantomManager.getInstance().livingGearOf(player), shop);
			return;
		}
		final LivingGear.Fit fit = ColdLife.fitOf(shop, bot.getClassId(), bot.getId(), bot.getLevel());
		final PhantomManager.LivingBag bag = PhantomManager.getInstance().livingReviewBag(player, fit, shop.items(), shop::wearable, true);
		for (LivingGear.Change change : bag.worn())
		{
			bot.getDecisions().add(now, null, "Picked up " + LivingGear.describe(change, fit) + " and put it on");
		}
		if (bag.loot() > 0)
		{
			bot.setLoot(Math.min(ColdEconomy.MAX_ADENA, bot.getLoot() + bag.loot()));
		}
		ColdLife.setGear(bot, PhantomManager.getInstance().livingGearOf(player), shop);
	}

	/** The progress a cooled bot carries back to its row, in plain words for its decision log. */
	private static String carried(ColdBot bot)
	{
		return "level " + bot.getLevel() + ", " + DecisionLog.num(bot.getAdena()) + " adena, " + DecisionLog.num(bot.getSoulshots()) + " soulshots, " + DecisionLog.num(bot.getPotions()) + " potions, " + DecisionLog.num(bot.getEscapes()) + " Scrolls of Escape";
	}

	/** Snapshot of the online real players' positions, taken once per scan from a stable copy of the player list. */
	private Observers observers()
	{
		// Copy the live player collection once so a player logging in mid-scan cannot desync the array size from the
		// iteration (which would silently drop that player from proximity checks for the tick).
		final List<Player> players = new ArrayList<>(World.getInstance().getPlayers());
		int count = 0;
		final int[] xs = new int[players.size()];
		final int[] ys = new int[players.size()];
		final int[] zs = new int[players.size()];
		for (Player player : players)
		{
			// Real humans only: online, with a network client, not a buddy bot. Hot living-population characters are
			// buddy bots, so they never count themselves (or each other) as observers. Living bots live in the main world
			// (instance 0), so a player inside an instance cannot see them, as the world's own visibility rule says.
			if ((player != null) && player.isOnline() && (player.getClient() != null) && !player.isBuddyBot() && (player.getInstanceId() == 0))
			{
				xs[count] = player.getX();
				ys[count] = player.getY();
				zs[count] = player.getZ();
				count++;
			}
		}
		return new Observers(xs, ys, zs, count);
	}

	private static class Observers
	{
		private final int[] _xs;
		private final int[] _ys;
		private final int[] _zs;
		private final int _count;

		private Observers(int[] xs, int[] ys, int[] zs, int count)
		{
			_xs = xs;
			_ys = ys;
			_zs = zs;
			_count = count;
		}

		/** @return the map distance to the nearest observer on the same level (see {@link #OBSERVER_Z_RANGE}), or -1 when there are none */
		private double nearest(int x, int y, int z)
		{
			double best = -1.0;
			for (int i = 0; i < _count; i++)
			{
				if (Math.abs(z - _zs[i]) > OBSERVER_Z_RANGE)
				{
					continue; // another floor or level: it cannot see this bot
				}
				final double distance = HandoffPolicy.planarDistance(x, y, _xs[i], _ys[i]);
				if ((best < 0.0) || (distance < best))
				{
					best = distance;
				}
			}
			return best;
		}
	}
}
