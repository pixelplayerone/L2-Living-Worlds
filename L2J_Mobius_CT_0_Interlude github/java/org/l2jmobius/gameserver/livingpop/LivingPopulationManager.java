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

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.modules.Json;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.skill.holders.SkillLearn;

/**
 * The Living Population core: the persistent cold population and the resolver that advances it. This is the Phase 1
 * (cold foundation) manager. It has no actors, no packets, and no combat; it keeps durable per-bot rows and advances
 * their level and experience cheaply over time, mirroring what a player would earn by hunting. The hot/cold handoff,
 * goals, travel, and town errands are later phases.
 *
 * <p>It is dormant unless the opt-in module turns it on: the module entry point reads {@code config/module.ini} and, only
 * when the switch is on, calls {@link #start(LivingPopulationConfig)}. Nothing here runs on the game thread pool; the
 * resolver and the monitoring snapshot run on a dedicated daemon executor so simulation latency can never affect
 * gameplay.
 */
public class LivingPopulationManager
{
	private static final Logger LOGGER = Logger.getLogger(LivingPopulationManager.class.getName());

	private final CopyOnWriteArrayList<ColdBot> _bots = new CopyOnWriteArrayList<>();
	private final AtomicBoolean _started = new AtomicBoolean(false);
	private final ColdBotDao _dao = new ColdBotDao();
	private final HotColdHandoff _handoff = new HotColdHandoff(_dao);

	private LivingPopulationConfig _config = LivingPopulationConfig.defaults();
	private TravelConfig _travelConfig = TravelConfig.defaults();
	private volatile ZoneCatalog _catalog = ZoneCatalog.empty();
	private volatile ZoneCombat _combat = ZoneCombat.off(); // zone-based kill and death rates, or off for the flat ones
	private volatile ZoneCombat.Params _combatParams = new ZoneCombat.Params(false, 12.0, 0.5, 0.5, 3.0, 24.0, 0.25, 4.0, 10);
	private static final double MIN_RESPAWN_RATE = 0.3; // kills per minute a bot always manages, whatever the zone's respawns
	private volatile boolean _zoneExp; // experience per kill from the zone's real monsters, not level x ColdExpPerMobLevel
	private volatile boolean _respawnLimit = true; // zone combat: a zone's respawns cap what its bots can kill
	private volatile double _respawnShare = 0.5; // share of a zone's spawns a bot can practically reach
	private volatile double _aggroRisk = 1.0; // zone combat: extra death multiple where every monster is aggressive
	private volatile double _soulshotDamage = 2.0; // zone combat: damage with soulshots over without
	private volatile double _spiritshotDamage = Math.sqrt(2.0);
	private volatile double _blessedDamage = 2.0; // zone combat: damage with blessed spiritshots over without
	private boolean _lossTableLoaded; // the server's death experience loss table was handed to ColdRisk
	private volatile boolean _partyOn = true; // zone combat: bots may hunt in a virtual party (tank, damage dealer, buffer, healer)
	private volatile double _partyChance = 0.5; // share of bot visits that roll a party
	private volatile boolean _partyHealers = true; // healers always hunt in the party
	private volatile ZoneCombat.PartyParams _partyParams = ZoneCombat.PartyParams.defaults();
	private static final long PARTY_WINDOW_MS = 4L * 3_600_000L; // a bot keeps its roll (party or alone, who is in it) for this long in a zone
	private volatile boolean _zoneRest = true; // zone combat: sitting to refill HP and MP lowers kills (potions cover some HP)
	private volatile boolean _zoneEvasion = true; // zone combat: monsters miss a bot with evasion
	private volatile boolean _rotationTtk = true; // zone combat: time to kill from the sim rotations (real seconds)
	private volatile double _blessedShare; // share of mages that fire blessed spiritshots (until buying decides it per bot)
	private volatile double[] _buffShares = new double[4]; // buffed leveling: share of the full buffer party, per role
	private volatile boolean _combatRates = true; // kill and death rates from the zone model
	private volatile boolean _expGap = true; // no hunting experience when outleveled for the zone, like the server
	private volatile String _combatFile = "modules/living-population/data/zone_combat.tsv";
	private volatile long[] _kitPrices; // gear kit price by grade, from the item data (see kitPrices)
	private final Map<String, DropYield.Yield> _zoneYields = new ConcurrentHashMap<>(); // zone|level|spoiler|gear -> per kill
	private final Map<String, Map<Integer, Double>> _zoneGear = new ConcurrentHashMap<>(); // zone|level|spoiler -> gear drop chances
	private volatile GearCatalog _gear; // gear kept per slot (travel and GearSlots on), else null
	private final Map<Integer, List<SkillPlanner.Entry>> _skillTrees = new ConcurrentHashMap<>(); // class id -> skill tree
	private final Map<String, Double> _spRatios = new ConcurrentHashMap<>(); // zone -> SP earned per experience point
	private volatile boolean _travel = false; // Phase 5 travel active (switch on and a usable catalog loaded)
	private final Random _random = new Random();
	private ScheduledExecutorService _executor;
	private volatile int _targetLevel = 0;
	private long _nextBirthAt; // when the next wave of new bots is born (resolver thread)
	private volatile int _populationTarget; // PopulationSize, until the monitor lowers it while running
	// Bots the monitor asked to remove, by id. Each is cooled first if hot, then its row and character are deleted.
	private final Set<Long> _removals = ConcurrentHashMap.newKeySet();
	private int _resolveCursor = 0; // round-robin start index for the resolver, so a large population is advanced fairly

	protected LivingPopulationManager()
	{
	}

	/**
	 * Starts the cold simulation from the supplied configuration. Idempotent: a second call while running is ignored.
	 * When the config is disabled this returns without touching anything. Loads existing bots, seeds the initial
	 * population when the table is empty, and schedules the resolver and the monitoring snapshot.
	 * @param config the module configuration
	 */
	public void start(LivingPopulationConfig config)
	{
		start(config, TravelConfig.defaults());
	}

	/**
	 * Sets the zone combat model's tuning and data file. Call before {@link #start(LivingPopulationConfig, TravelConfig)}.
	 * @param params the tuning ({@code enabled} false keeps the flat kill and death rates)
	 * @param dataFile the generated zone_combat.tsv
	 * @param buffShares per role (tank, melee, bow, mage) the share of the buffer party's buffs the bots have while leveling, 0 to 1 (null or zeros = unbuffed)
	 * @param expLevelGap whether hunting gives no experience when the bot is {@code MonsterExpMaxLevelDifference} or more levels away from the zone's monsters (uses the same data file)
	 */
	public void setZoneLimits(boolean respawnLimit, double respawnShare, double aggroRisk, boolean zoneExp)
	{
		_zoneExp = zoneExp;
		_respawnLimit = respawnLimit;
		_respawnShare = respawnShare;
		_aggroRisk = aggroRisk;
	}

	public void setParty(boolean on, double chance, boolean healers, ZoneCombat.PartyParams params)
	{
		_partyOn = on;
		_partyChance = Math.max(0.0, Math.min(1.0, chance));
		_partyHealers = healers;
		_partyParams = params;
	}

	public void setRestAndEvasion(boolean rest, boolean evasion)
	{
		_zoneRest = rest;
		_zoneEvasion = evasion;
	}

	public void setRotationTtk(boolean on)
	{
		_rotationTtk = on;
	}

	public void setBlessedSpiritshots(double damage, double share)
	{
		_blessedDamage = damage;
		_blessedShare = Math.max(0.0, Math.min(1.0, share));
	}

	/** @return whether this bot fires blessed spiritshots: a fixed share of the mages by bot id (a stand-in until a bot's purchases decide it) */
	private boolean usesBlessed(ColdBot bot)
	{
		return (_blessedShare > 0.0) && (Math.floorMod(bot.getId() * 2654435761L, 100L) < Math.round(_blessedShare * 100.0));
	}

	public void setShotDamage(double soulshot, double spiritshot)
	{
		_soulshotDamage = soulshot;
		_spiritshotDamage = spiritshot;
	}

	public void setZoneCombat(ZoneCombat.Params params, String dataFile, boolean expLevelGap, double[] buffShares)
	{
		_buffShares = (buffShares == null) ? new double[4] : buffShares.clone();
		_expGap = expLevelGap;
		_combatRates = (params != null) && params.enabled();
		_combatParams = (params == null) ? _combatParams : params;
		_combatFile = (dataFile == null) ? _combatFile : dataFile;
	}

	/**
	 * Starts the module (see {@link #setZoneCombat} for the zone combat model's tuning, set before this).
	 * @param config the module configuration
	 * @param travel the travel, town and supply configuration
	 */
	public void start(LivingPopulationConfig config, TravelConfig travel)
	{
		if (config == null)
		{
			return;
		}

		_config = config;
		_travelConfig = (travel == null) ? TravelConfig.defaults() : travel;
		if (!config.enabled())
		{
			return;
		}

		if (!_started.compareAndSet(false, true))
		{
			return;
		}

		// Establish the new handoff generation before reading or repairing rows. Old async cooldown/activation callbacks
		// may still be finishing after a same-JVM stop; from this point on they must not write their stale ColdBot copies
		// over rows this start is about to load.
		_handoff.beginRun();

		// Phase 5: the travel catalog. Travel needs the economy (it is the shopping half of it) and a usable catalog; without
		// either the bots keep the Phase 4 behavior (restock in place, no movement).
		_travel = false;
		if (_travelConfig.enabled() && config.economyEnabled())
		{
			try
			{
				_catalog = ZoneCatalog.load(Path.of(_travelConfig.zonesFile()));
				_travel = _catalog.isUsable();
				if (!_travel)
				{
					LOGGER.warning("LivingPopulation: travel is on but " + _travelConfig.zonesFile() + " is missing or has no towns with a grocer; bots keep restocking in place.");
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: could not read " + _travelConfig.zonesFile() + ": " + e.getMessage() + "; bots keep restocking in place.", e);
			}
		}

		_gear = (_travel && _travelConfig.gearSlots()) ? new GearCatalog(_travelConfig.gearTrade(), config.gearTierLevelStep()) : null;

		// Zone combat: kill and death rates from each bot's stats against its zone's monsters. Needs the zone data file.
		_combat = ZoneCombat.off();
		if (_combatRates || _expGap || _zoneExp)
		{
			try (Reader reader = Files.newBufferedReader(Path.of(_combatFile), StandardCharsets.UTF_8))
			{
				_combat = ZoneCombat.parse(reader, new ZoneCombat.Params(true, Math.max(0.01, config.killsPerMinute()), _combatParams.fightShare(), _combatParams.skillFloor(), _combatParams.minKillsPerMinute(), _combatParams.maxKillsPerMinute(), _combatParams.minDeathFactor(), _combatParams.maxDeathFactor(), config.gearTierLevelStep()));
				_combat.setBuffShares(_buffShares);
				_combat.setShotDamage(_soulshotDamage, _spiritshotDamage);
				_combat.setBlessedDamage(_blessedDamage);
				_combat.setRotationTtk(_rotationTtk);
				_combat.setRest(_zoneRest);
				_combat.setParty(new ZoneCombat.PartyParams(_partyOn && _partyParams.enabled(), _partyParams.expBonus(), _partyParams.healReduction(), _partyParams.healCoverage(), _partyParams.chainChance(), _partyParams.resetSeconds(), _partyParams.healerMpFactor(), _partyParams.baseDeathsPerHour(), _partyParams.gearPenalty()));
				_combat.setEvasion(_zoneEvasion);
				_combat.setAggroRisk(_aggroRisk);
				if (!_combat.enabled())
				{
					LOGGER.warning("LivingPopulation: " + _combatFile + " has no usable zone data; cold bots keep the flat kill and death rates.");
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "LivingPopulation: could not read " + _combatFile + ": " + e.getMessage() + "; cold bots keep the flat kill and death rates and the experience level gap is not applied.", e);
			}
		}

		_dao.ensureColumns(); // add columns a newer module version needs to an older table (no manual migration)
		final long now = System.currentTimeMillis();
		final List<ColdBot> loaded = _dao.loadAll();
		if (loaded == null)
		{
			// The table could not be read. Starting now would look like an empty population and seed a new one next to
			// the real one; stay stopped instead, until a restart can read it.
			LOGGER.severe("LivingPopulation: could not read the bot table; the simulation is NOT started. Check the database and restart.");
			_started.set(false);
			return;
		}
		for (ColdBot bot : loaded)
		{
			// Do not accrue progress for time the server was offline. Anchor the resolve clock to now so the first
			// tick advances by at most one interval, not by the whole downtime.
			bot.setLastResolvedAt(now);
			bot.setNextResolveAt(now + config.resolveIntervalMs());
			// No bot is materialized at boot. A row left hot_lock=true by an unclean shutdown (or a crash while hot)
			// would otherwise be skipped by the resolver forever, freezing that bot. Clear the lock and the hot phase on
			// load and persist the correction so cold resolution resumes.
			if (bot.isHotLock() || "hot".equals(bot.getPhase()))
			{
				bot.setHotLock(false);
				bot.setPhase("cold");
				_dao.update(bot);
			}
			if (_travel && ColdLife.adoptStartingZone(bot, _catalog))
			{
				_dao.update(bot);
			}
			if (bot.getDecisions().size() == 0)
			{
				// A bot created before decision logging existed: start its log so the monitor does not look empty.
				bot.getDecisions().add(now, null, "Decision log started at level " + bot.getLevel() + ", goal " + bot.getGoal());
			}
			_bots.add(bot);
		}

		// The first bots were numbered (Human000 ...): give them real names, characters included.
		renamePlaceholders(loaded);

		// Removals asked for while the server was down, before any new bot is born.
		_populationTarget = Math.max(0, config.populationSize());
		readRequests();
		removeRequested();

		// Reconcile the population toward the configured target: seed any bots missing to reach it. This covers the first
		// run (nothing loaded) and a later increase of PopulationSize. Reducing the target does not delete existing bots
		// (they hold persistent progression); shrinking is an explicit admin action, not a silent side effect of config.
		seed(now);

		LOGGER.info("LivingPopulation: started with " + _bots.size() + " cold bot(s).");

		_executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			final Thread thread = new Thread(runnable, "LivingPopulation-Resolver");
			thread.setDaemon(true);
			return thread;
		});
		_executor.scheduleWithFixedDelay(this::safeResolveTick, config.resolveIntervalMs(), config.resolveIntervalMs(), TimeUnit.MILLISECONDS);
		_executor.scheduleWithFixedDelay(this::safeSnapshotTick, config.snapshotIntervalMs(), config.snapshotIntervalMs(), TimeUnit.MILLISECONDS);

		// Phase 3: the hot/cold handoff runs on the same single thread as the resolver, so cold-row decisions never race
		// each other; only the actual character spawn/despawn is dispatched to the game thread from inside the handoff.
		if (config.handoffEnabled())
		{
			_handoff.configure(config.handoffParams(), config.gearTierLevelStep());
			_executor.scheduleWithFixedDelay(this::safeHandoffTick, config.handoffIntervalMs(), config.handoffIntervalMs(), TimeUnit.MILLISECONDS);
			LOGGER.info("LivingPopulation: hot/cold handoff enabled (activation " + (int) config.handoffActivationRadius() + "u, cool grace " + (config.handoffCooldownGraceMs() / 1000L) + "s, max hot " + config.effectiveMaxHotBots() + ").");
		}
	}

	/**
	 * Stops the resolver executor. Reserved for a future hot-unload path; V1 applies enable and disable on restart.
	 */
	public void stop()
	{
		// Stop the resolver thread FIRST so it cannot race the handoff's shutdown row writes (a concurrent tick could
		// otherwise observe a freshly unlocked bot and re-simulate its hot span). Then cool every hot bot back to cold,
		// which stores each character row and clears the world so nothing is left orphaned.
		if (_executor != null)
		{
			_executor.shutdownNow();
			try
			{
				// Wait for a tick in progress to end, so the shutdown below is the only thread writing bots.
				if (!_executor.awaitTermination(10, TimeUnit.SECONDS))
				{
					LOGGER.warning("LivingPopulation: the resolver did not stop within 10s; stopping anyway.");
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			_executor = null;
		}
		_handoff.shutdown();
		_handoff.setLife(null);
		// Clear in-memory state so a later start() reloads cleanly instead of double-loading the population.
		_bots.clear();
		_removals.clear();
		_resolveCursor = 0;
		_targetLevel = 0;
		_nextBirthAt = 0;
		_started.set(false);
	}

	private void seed(long now)
	{
		// Grow toward the target in waves, like new players arriving: at least BirthBatch bots now (more for a large
		// population, so all arrive within about three hours), the next wave after BirthIntervalMinutes. Every bot starts
		// at level 1, so the waves spread the population's levels out over time.
		// New bots take the starting classes the population has least of and a random name no character uses.
		final int wanted = Math.max(0, _populationTarget);
		final int missing = wanted - _bots.size();
		if (missing <= 0)
		{
			return;
		}
		final int needed = LivingPopulationConfig.birthWave(_config.birthBatch(), _config.birthIntervalMs(), wanted, missing);
		_nextBirthAt = now + _config.birthIntervalMs();
		final Set<String> existing = new HashSet<>();
		final Map<Integer, Integer> classes = new HashMap<>();
		for (ColdBot present : _bots)
		{
			existing.add(present.getName().toLowerCase());
			classes.merge(startingClassOf(present.getClassId()), 1, Integer::sum);
		}
		final List<ColdSeedPlan.SeedSpec> specs = ColdSeedPlan.plan(needed, classes, _random, name -> nameTaken(name, existing));

		int created = 0;
		for (ColdSeedPlan.SeedSpec spec : specs)
		{
			final Location start = creationPoint(spec.classId());
			final ColdBot bot = new ColdBot();
			bot.setName(spec.name());
			bot.setRace(spec.race());
			bot.setClassId(spec.classId());
			bot.setLevel(1);
			bot.setExpIntoLevel(0L);
			bot.setSp(0L);
			bot.setAdena(0L);
			bot.setRegion(spec.race());
			if (start != null)
			{
				bot.setX(start.getX());
				bot.setY(start.getY());
				bot.setZ(start.getZ());
			}
			bot.setActivity("hunting");
			bot.setPhase("cold");
			bot.setGoal("hunting");
			bot.setSoulshots(0L);
			bot.setPotions(0L);
			bot.setGearTier(0);
			bot.setRewardClaimed(false);
			bot.setCreatedAt(now);
			bot.setUpdatedAt(now);
			bot.setLastResolvedAt(now);
			bot.setNextResolveAt(now + _config.resolveIntervalMs());
			bot.setHotLock(false);
			bot.getDecisions().add(now, null, "Born a level 1 " + ClassPath.name(spec.classId()) + " at the " + spec.race() + " starting point" + ((start != null) ? (" (" + start.getX() + ", " + start.getY() + ")") : "") + ". Goal: hunt to level up");
			if (_travel)
			{
				ColdLife.adoptStartingZone(bot, _catalog);
			}
			if (_dao.insert(bot))
			{
				_bots.add(bot);
				existing.add(bot.getName().toLowerCase());
				created++;
			}
		}
		if (created > 0)
		{
			LOGGER.info("LivingPopulation: " + created + " new bot(s) born (total " + _bots.size() + " of " + wanted + ((_bots.size() < wanted) ? ("; the next wave in " + (_config.birthIntervalMs() / 60_000L) + " min") : "") + ").");
		}
	}

	/**
	 * @return the starting class a class descends from (itself for a starting class)
	 */
	private static int startingClassOf(int classId)
	{
		final PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
		return ((playerClass == null) || (playerClass.getRootClass() == null)) ? classId : playerClass.getRootClass().getId();
	}

	/**
	 * @param name a candidate name
	 * @param bots the lower-case names of the bots
	 * @return whether a bot or any character already has it
	 */
	private static boolean nameTaken(String name, Set<String> bots)
	{
		return bots.contains(name.toLowerCase()) || CharInfoTable.getInstance().doesCharNameExist(name);
	}

	/**
	 * Renames the bots that still carry a numbered placeholder name (Human000 and so on), their characters too. A bot
	 * whose character is still tracked live (left over from a run in this server) keeps its name until a later start.
	 */
	private void renamePlaceholders(List<ColdBot> bots)
	{
		final Set<String> names = new HashSet<>();
		for (ColdBot bot : bots)
		{
			names.add(bot.getName().toLowerCase());
		}
		final Set<Long> live = PhantomManager.getInstance().livingPopulationPhantoms().keySet();
		int renamed = 0;
		for (ColdBot bot : bots)
		{
			if (!ColdSeedPlan.placeholder(bot.getName()) || live.contains(bot.getId()))
			{
				continue;
			}
			final String old = bot.getName();
			final String name = ColdSeedPlan.newName(_random, candidate -> nameTaken(candidate, names));
			if (!_dao.rename(bot, name))
			{
				continue;
			}
			names.add(name.toLowerCase());
			if (bot.getCharId() > 0)
			{
				CharInfoTable.getInstance().removeName((int) bot.getCharId()); // the cache reloads the new name on demand
			}
			bot.setName(name);
			bot.getDecisions().add(System.currentTimeMillis(), null, "Renamed from " + old + " to " + name);
			_dao.update(bot);
			renamed++;
		}
		if (renamed > 0)
		{
			LOGGER.info("LivingPopulation: gave " + renamed + " bot(s) a real name in place of their numbered one.");
		}
	}

	private Location creationPoint(int classId)
	{
		try
		{
			final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(classId);
			if (template != null)
			{
				return template.getCreationPoint();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: no creation point for class " + classId + ": " + e.getMessage(), e);
		}
		return null;
	}

	private void safeResolveTick()
	{
		try
		{
			resolveTick();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: resolver tick failed: " + e.getMessage(), e);
		}
	}

	private void resolveTick()
	{
		_handoff.drainCompletions(); // bots the game thread finished cooling are unlocked here, before any is resolved
		final long now = System.currentTimeMillis();
		readRequests();
		removeRequested();
		if (now >= _nextBirthAt)
		{
			seed(now); // the next wave of new bots, while the population is below its target
		}

		// Follow the server and model real hunting: honor its XP rate, use its real experience table for the per-level
		// requirement, and earn experience per kill. A cold bot is assumed to clear level-appropriate mobs, so its
		// experience per minute is (level * expPerMobLevel) * killsPerMinute * server XP rate. Read once per tick.
		if (!_lossTableLoaded)
		{
			_lossTableLoaded = true;
			try
			{
				final org.l2jmobius.gameserver.data.xml.ExperienceLossData loss = org.l2jmobius.gameserver.data.xml.ExperienceLossData.getInstance();
				final double[] table = new double[ExperienceData.getInstance().getMaxLevel() + 1];
				for (int level = 1; level < table.length; level++)
				{
					table[level] = loss.getPercentLost(level);
				}
				ColdRisk.setExpLossTable(table); // cold deaths cost what a real death costs
			}
			catch (RuntimeException e)
			{
				// keep the estimate
			}
		}
		final ExperienceData experience = ExperienceData.getInstance();
		final double rate = Math.max(0.01, RatesConfig.RATE_XP);
		final int maxLevel = Math.min(_config.maxLevel(), experience.getMaxLevel());
		final IntToLongFunction expToNextLevel = level -> Math.max(1L, experience.getExpForLevel(level + 1) - experience.getExpForLevel(level));

		// Population director: bias leveling toward the online players' level so the world stays peered to them. With
		// the director off or no players online the target is 0 (neutral). It is folded per level into the experience
		// rate, so a catching-up bot naturally eases off as it approaches the band.
		// A configured level goal replaces the players' level.
		final int targetLevel = !_config.directorEnabled() ? 0 : (_config.levelGoal() > 0) ? Math.min(_config.levelGoal(), maxLevel) : PopulationDirector.targetLevel(humanPlayerLevels());
		_targetLevel = targetLevel;
		final PopulationDirector.Params directorParams = _config.directorParams();
		final ZoneCombat combat = _combatRates ? _combat : ZoneCombat.off(); // rates
		final ZoneCombat gapData = (_expGap || _zoneExp) ? _combat : ZoneCombat.off(); // zone monster levels and exp
		final int maxLevelGap = RatesConfig.MONSTER_EXP_MAX_LEVEL_DIFFERENCE;
		final double flatKillsPerMinute = Math.max(0.0, _config.killsPerMinute());
		final double expPerKillUnitPerRate = Math.max(0.0, _config.expPerMobLevel()) * rate; // times a kill rate: experience per mob level per minute

		// Phase 4: the cold goal/needs economy (adena, soulshots, gear tier, goal). Read the tuning once per tick.
		final boolean economy = _config.economyEnabled();
		final ColdEconomy.Params economyParams = economy ? _config.economyParams() : null;

		// Phase 5: travel, town visits and player-like supplies. Occupancy is counted once per tick for zone capacity, and
		// each bot that picks a zone moves itself in the count at once.
		final boolean travel = _travel && economy;
		final ColdLife.PriceBook priceBook = new ColdLife.PriceBook()
		{
			@Override
			public SupplyPlanner.Prices prices(int level, int gearTier)
			{
				return LivingPopulationManager.this.prices(level, gearTier);
			}

			@Override
			public SupplyPlanner.Prices prices(int level, int gearTier, int classId)
			{
				return LivingPopulationManager.this.prices(level, gearTier, classId);
			}
		};
		final Map<String, Integer> occupancy = (travel || (_respawnLimit && combat.enabled())) ? zoneOccupancy() : null;
		final ColdLife.Context life = travel ? new ColdLife.Context(_catalog, _travelConfig.supplyParams(_config), _travelConfig.travelParams(), priceBook, occupancy, _random, _travelConfig.riskParams(), expToNextLevel, _travelConfig.classQuestParams(), _travelConfig.skillTraining() ? this::skillTree : null, _gear, combat.enabled() ? combat : null) : null;
		_handoff.setLife(life); // hot bots make the same decisions, acted out by their live characters

		// Resolve up to resolveBatch due bots, starting from a rotating cursor so a population larger than the batch is
		// advanced fairly round-robin. (Starting from index 0 every tick would let the first resolveBatch bots, which
		// become due again by the next tick, monopolize the budget and starve every bot beyond them forever.)
		final int size = _bots.size();
		final int batch = Math.max(1, _config.resolveBatch());
		int resolved = 0;
		int examined = 0;
		int index = (size == 0) ? 0 : (_resolveCursor % size);
		while ((examined < size) && (resolved < batch))
		{
			final ColdBot bot = _bots.get(index);
			index = (index + 1) % size;
			examined++;
			if (bot.isHotLock() || (bot.getNextResolveAt() > now))
			{
				continue;
			}

			final long elapsed = Math.max(0L, now - bot.getLastResolvedAt());
			// With travel on, experience, adena and soulshot use accrue only while hunting (not while traveling or in town).
			final long huntedMs = (!travel || ColdLife.isHunting(bot.getActivity())) ? elapsed : 0L;
			// This bot's own kill rate: its stats against the average monster of the zone it hunts (the flat rate when the model is off).
			final java.util.function.IntToDoubleFunction perKillAt = level ->
			{
				// Like the server, a kill pays no experience when the bot is too many levels away from the zone's monsters.
				if (_expGap && ZoneCombat.outleveled(level, gapData.mobLevel(bot.getZone()), maxLevelGap))
				{
					return 0.0;
				}
				// Experience per kill: the zone's real average (option), else a representative level x ColdExpPerMobLevel.
				final double zoneExp = _zoneExp ? gapData.expPerKill(bot.getZone()) : -1.0;
				return (zoneExp > 0.0) ? (zoneExp * rate) : (level * expPerKillUnitPerRate);
			};
			final IntToLongFunction expPerMinuteForLevel = level ->
			{
				final double perKill = perKillAt.applyAsDouble(level);
				if (perKill <= 0.0)
				{
					return 0L;
				}
				final double soloRate = ratePerMinute(combat, bot, level, flatKillsPerMinute, huntedMs, economyParams, occupancy);
				final ZoneCombat.PartyOutcome party = partyChoice(combat, bot, level, perKill, soloRate, now, expToNextLevel);
				final double kills = (party == null) ? soloRate : capToZone(combat, bot, party.killsPerMinute(), occupancy);
				final double base = Math.max(0.0, perKill * kills * ((party == null) ? 1.0 : party.expShare()));
				return (long) Math.max(0.0, base * PopulationDirector.pressure(level, targetLevel, directorParams));
			};
			final ColdProgression.Progress progress = ColdProgression.resolve(bot.getLevel(), bot.getExpIntoLevel(), huntedMs, maxLevel, expToNextLevel, expPerMinuteForLevel);
			// The rate at the level it ends the span on drives the span's kills, adena, soulshots and drops (a span is short).
			final double botKillsPerMinute = ratePerMinute(combat, bot, progress.level(), flatKillsPerMinute, huntedMs, economyParams, occupancy);
			// Kills at the modeled kill rate (a monitor counter), and the experience this span added (it earns SP).
			final double huntKills = botKillsPerMinute * (huntedMs / 60_000.0);
			final ZoneCombat.PartyOutcome partyNow = partyChoice(combat, bot, progress.level(), perKillAt.applyAsDouble(progress.level()), botKillsPerMinute, now, expToNextLevel);
			bot.setPartyDeathFactor((partyNow == null) ? 0.0 : partyNow.deathFactor());
			// In a party the kills are the party's, and each drop is split four ways: the bot gets a quarter of the adena and a quarter of the chance at each item.
			// Shots and potions stay at the bot's own rate (it attacks the whole time).
			final double lootScale = (partyNow == null) ? 1.0 : (capToZone(combat, bot, partyNow.killsPerMinute(), occupancy) * partyNow.lootShare() / Math.max(1e-9, botKillsPerMinute));
			final long huntExp = (experience.getExpForLevel(progress.level()) + progress.expIntoLevel()) - (experience.getExpForLevel(bot.getLevel()) + bot.getExpIntoLevel());
			long huntAdena = 0L;
			final List<DecisionLog.Event> events = new ArrayList<>();
			if (travel && (_gear != null) && (bot.getGear() == null))
			{
				convertGear(bot, events);
			}
			if (progress.level() > bot.getLevel())
			{
				final int gained = progress.level() - bot.getLevel();
				events.add(new DecisionLog.Event(null, "Reached level " + progress.level() + ((gained > 1) ? (" (up " + gained + " levels)") : "") + " while hunting"));
			}
			bot.setLevel(progress.level());
			bot.setExpIntoLevel(progress.expIntoLevel());
			// SP comes with the experience, at the zone monsters' own SP to experience ratio (a hot bot earns it for real).
			if (travel && _travelConfig.skillTraining() && (huntExp > 0))
			{
				bot.setSp(Math.min(ColdEconomy.MAX_ADENA, bot.getSp() + Math.round(huntExp * spRatio(bot.getZone()))));
			}

			// Advance the economy at the (possibly new) level over the same elapsed span.
			if (travel)
			{
				final ColdEconomy.State before = new ColdEconomy.State(bot.getAdena(), bot.getSoulshots(), bot.getPotions(), bot.getGearTier(), bot.isRewardClaimed(), bot.getGoal());
				// A kill pays from the zone monsters' real drop lists when the catalog has them: adena now, loot sold in town.
				final DropYield.Yield yield = _travelConfig.dropIncome() ? zoneYield(bot.getZone(), progress.level(), LivingSupplies.isSpoiler(bot.getClassId())) : null;
				// A mystic fires spiritshots, at its own rate per kill. A bot's own kill rate (zone combat) replaces the flat one.
				final ColdEconomy.Params shotParams = LivingSupplies.isMystic(bot.getClassId()) ? economyParams.withSoulshotsPerKill(_travelConfig.spiritshotsPerKill()) : economyParams;
				final ColdEconomy.Params botEconomy = combat.enabled() ? shotParams.withKillsPerMinute(botKillsPerMinute) : shotParams;
				final ColdEconomy.State after = ColdEconomy.accrue(before, progress.level(), huntedMs, botEconomy, (yield == null) ? -1.0 : (yield.adena() * lootScale), events);
				huntAdena = Math.max(0L, after.adena() - before.adena());
				if ((yield != null) && (huntedMs > 0))
				{
					final double kills = botKillsPerMinute * lootScale * (huntedMs / 60_000.0);
					final long lootValue = Math.round(Math.max(0.0, kills * yield.loot()));
					bot.setLoot(Math.min(ColdEconomy.MAX_ADENA, bot.getLoot() + lootValue));
					huntAdena += lootValue;
				}
				// Gear kept per slot: the weapons and armor its kills dropped are rolled for real; it wears the better ones.
				if ((_gear != null) && _travelConfig.dropIncome() && (huntedMs > 0))
				{
					final Map<Integer, Double> chances = zoneGear(bot.getZone(), progress.level(), LivingSupplies.isSpoiler(bot.getClassId()));
					if (!chances.isEmpty())
					{
						final double kills = botKillsPerMinute * lootScale * (huntedMs / 60_000.0);
						final long found = ColdLife.findDrops(bot, LivingGear.roll(chances, kills, _random), life, events);
						bot.setLoot(Math.min(ColdEconomy.MAX_ADENA, bot.getLoot() + found));
						huntAdena += found;
					}
				}
				bot.setAdena(after.adena());
				bot.setSoulshots(after.soulshots());
				bot.setRewardClaimed(after.rewardClaimed());
				ColdLife.advance(bot, now, elapsed, life, events);
			}
			else if (economy)
			{
				final ColdEconomy.State before = new ColdEconomy.State(bot.getAdena(), bot.getSoulshots(), bot.getPotions(), bot.getGearTier(), bot.isRewardClaimed(), bot.getGoal());
				final ColdEconomy.State after = ColdEconomy.resolve(before, progress.level(), elapsed, economyParams, events);
				bot.setAdena(after.adena());
				bot.setSoulshots(after.soulshots());
				bot.setPotions(after.potions());
				bot.setGearTier(after.gearTier());
				bot.setRewardClaimed(after.rewardClaimed());
				bot.setGoal(after.goal());
			}
			bot.getDecisions().addTick(now, events);
			if (huntedMs > 0)
			{
				bot.addHunting(huntKills, huntAdena);
			}

			bot.setLastResolvedAt(now);
			bot.setNextResolveAt(now + _config.resolveIntervalMs());
			bot.setUpdatedAt(now);
			_dao.update(bot);
			resolved++;
		}
		_resolveCursor = index; // resume here next tick so the budget rotates across the whole population
	}

	/** The zone's respawns cap what a bot (or a party) kills; the same cap as for a bot alone. */
	private double capToZone(ZoneCombat combat, ColdBot bot, double rate, Map<String, Integer> occupancy)
	{
		if (_respawnLimit && (occupancy != null))
		{
			return Math.min(rate, Math.max(MIN_RESPAWN_RATE, combat.respawnCap(bot.getZone(), occupancy.getOrDefault(bot.getZone(), 1), _respawnShare)));
		}
		return rate;
	}

	/**
	 * Whether the bot hunts in its virtual party now: healers always (option), others by a roll that holds for a few hours in a zone, and only
	 * where the party beats going alone after the experience each death costs. Loot and shots are not touched (still at the solo rate).
	 * @param perKill experience a kill pays (0 to use only the death-free comparison: then healers only and rolled bots take the party)
	 * @return the party's outcome, or null for hunting alone
	 */
	private ZoneCombat.PartyOutcome partyChoice(ZoneCombat combat, ColdBot bot, int level, double perKill, double soloKills, long now, IntToLongFunction expToNextLevel)
	{
		if (!_partyOn || !combat.enabled() || !combat.knows(bot.getZone()))
		{
			return null;
		}
		final boolean healer = _partyHealers && ZoneCombat.isHealer(bot.getClassId());
		final int hash = Objects.hash(bot.getId(), bot.getZone(), now / PARTY_WINDOW_MS) & 0x7fffffff;
		final boolean rolled = ((hash % 1000) / 1000.0) < _partyChance;
		if (!healer && !rolled)
		{
			return null;
		}
		final ZoneCombat.Stats stats = ColdLife.statsOf(bot, _gear, combat);
		double skills = 1.0;
		if (_travelConfig.skillTraining() && (bot.getSkills() != null))
		{
			skills = ZoneCombat.skillFraction(skillTree(bot.getClassId()), SkillPlanner.decode(bot.getSkills()), level);
		}
		final ZoneCombat.PartyOutcome outcome = combat.party(bot.getZone(), bot.getClassId(), level, stats, skills, 1.0, usesBlessed(bot), hash / 1000);
		if ((outcome == null) || healer)
		{
			return outcome;
		}
		if (perKill <= 0.0)
		{
			return null; // no experience here to compare (or only the death rate was asked for): stay alone
		}
		final double base = _partyParams.baseDeathsPerHour();
		final double loss = (ColdRisk.expLossPercent(level) / 100.0) * Math.max(1L, expToNextLevel.applyAsLong(level));
		final double soloNet = (perKill * soloKills * 60.0) - (base * combat.deathFactor(bot.getZone(), bot.getClassId(), level, stats) * loss);
		final double partyNet = (perKill * outcome.expShare() * outcome.killsPerMinute() * 60.0) - (base * outcome.deathFactor() * loss);
		return (partyNet > soloNet) ? outcome : null;
	}

	/**
	 * @param combat the zone combat model
	 * @param bot a bot (its zone, class, gear and skills)
	 * @param level the level to rate it at
	 * @param flat the flat rate to fall back on
	 * @param shotFraction the share of the time it has its soulshots (spiritshots for a mystic)
	 * @return the bot's kills per minute in its zone
	 */
	private double ratePerMinute(ZoneCombat combat, ColdBot bot, int level, double flat, long huntedMs, ColdEconomy.Params economyParams, Map<String, Integer> occupancy)
	{
		final double full = killsPerMinuteOf(combat, bot, level, flat, 1.0);
		if (!combat.knows(bot.getZone()))
		{
			return full;
		}
		final double shots = shotFractionOf(bot, level, full, huntedMs, economyParams);
		double rate = (shots >= 1.0) ? full : killsPerMinuteOf(combat, bot, level, flat, shots);
		if (_respawnLimit && (occupancy != null))
		{
			// The zone only supplies so many monsters a minute, and its bots share them.
			rate = Math.min(rate, Math.max(MIN_RESPAWN_RATE, combat.respawnCap(bot.getZone(), occupancy.getOrDefault(bot.getZone(), 1), _respawnShare)));
		}
		return rate;
	}

	private double killsPerMinuteOf(ZoneCombat combat, ColdBot bot, int level, double flat, double shotFraction)
	{
		if (!combat.knows(bot.getZone()))
		{
			return flat;
		}
		final ZoneCombat.Stats stats = ColdLife.statsOf(bot, _gear, combat);
		double skills = 1.0;
		if (_travelConfig.skillTraining() && (bot.getSkills() != null))
		{
			skills = ZoneCombat.skillFraction(skillTree(bot.getClassId()), SkillPlanner.decode(bot.getSkills()), level);
		}
		return combat.killsPerMinute(bot.getZone(), bot.getClassId(), level, stats, skills, shotFraction, usesBlessed(bot), (bot.getPotions() > 0) ? (_travelConfig.potionsPerHour() * LivingSupplies.potionUseFactor(bot.getClassId())) : 0.0);
	}

	/**
	 * Whether the bot has the shots for the damage bonus. Its stock lasts as long as its kills use them up; a span longer
	 * than that is part shot, part not. Below the level shots are first handed out, or with the economy off, nothing is tracked
	 * and it counts as shot (what the calibration assumes).
	 * @param bot the bot
	 * @param level its level
	 * @param fullKillsPerMinute its kill rate with shots
	 * @param huntedMs the hunting time of the span
	 * @param economyParams the economy tuning, or null when it is off
	 * @return the share of the span it fires shots, 0 to 1
	 */
	private double shotFractionOf(ColdBot bot, int level, double fullKillsPerMinute, long huntedMs, ColdEconomy.Params economyParams)
	{
		if ((economyParams == null) || (level < economyParams.soulshotMilestoneLevel()))
		{
			return 1.0;
		}
		final double perKill = LivingSupplies.isMystic(bot.getClassId()) ? _travelConfig.spiritshotsPerKill() : economyParams.soulshotsPerKill();
		final double perMinute = fullKillsPerMinute * Math.max(0.0, perKill);
		final double minutes = huntedMs / 60_000.0;
		if (perMinute <= 0.0)
		{
			return 1.0;
		}
		if (minutes <= 0.0)
		{
			return (bot.getSoulshots() > 0) ? 1.0 : 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (bot.getSoulshots() / perMinute) / minutes));
	}

	/** Bots in or heading to each zone, counted once per resolver tick for zone capacity. */
	private Map<String, Integer> zoneOccupancy()
	{
		// Concurrent: a bot that picks a zone updates it at once (ColdLife), on this thread or, for a hot bot, the game's.
		final Map<String, Integer> counts = new ConcurrentHashMap<>();
		for (ColdBot bot : _bots)
		{
			if (bot.getZone() != null)
			{
				counts.merge(bot.getZone(), 1, Integer::sum);
			}
		}
		return counts;
	}

	/**
	 * Unit prices a bot pays at the grocer, from the real item data: the healing potion a player of its level buys, the
	 * soulshot grade its gear tier fires, and a Scroll of Escape.
	 * @param level bot level
	 * @param gearTier bot gear tier
	 * @return the prices
	 */
	private SupplyPlanner.Prices prices(int level, int gearTier)
	{
		return prices(level, gearTier, -1);
	}

	/**
	 * As {@link #prices(int, int)}, with the spiritshot price for a mystic.
	 * @param classId bot class id (-1 for a fighter's soulshots)
	 * @return the prices
	 */
	private SupplyPlanner.Prices prices(int level, int gearTier, int classId)
	{
		final int step = _config.gearTierLevelStep();
		final int gearLevel = ((gearTier <= 0) || (step <= 0)) ? 0 : Math.min(level, gearTier * step);
		final long nextTier = (step <= 0) ? 0L : kitPrices()[LivingSupplies.gradeFor((gearTier + 1) * step)];
		return new SupplyPlanner.Prices(price(LivingSupplies.potionIdFor(level)), price(LivingSupplies.shotIdFor(classId, gearLevel)), price(LivingSupplies.SCROLL_OF_ESCAPE), nextTier);
	}

	/**
	 * What a set of gear costs at each grade, from the real item data: the median shop price of a weapon plus the median
	 * price of each armor piece (chest, legs, helmet, gloves, boots) of that grade. A gear tier upgrade costs the kit of
	 * the grade that tier reaches, so a C-grade upgrade costs millions as it does for a player. Computed once.
	 * @return prices indexed by {@link LivingSupplies#gradeFor(int)}
	 */
	private long[] kitPrices()
	{
		long[] prices = _kitPrices;
		if (prices != null)
		{
			return prices;
		}
		final String[] grades =
		{
			"NONE",
			"D",
			"C",
			"B",
			"A",
			"S"
		};
		final BodyPart[] pieces =
		{
			BodyPart.CHEST,
			BodyPart.LEGS,
			BodyPart.HEAD,
			BodyPart.GLOVES,
			BodyPart.FEET
		};
		prices = new long[grades.length];
		for (int index = 0; index < grades.length; index++)
		{
			final CrystalType grade = CrystalType.valueOf(grades[index]);
			final List<Long> weapons = new ArrayList<>();
			final Map<BodyPart, List<Long>> armor = new HashMap<>();
			for (ItemTemplate item : ItemData.getInstance().getAllItems())
			{
				if ((item == null) || (item.getCrystalType() != grade) || (item.getReferencePrice() <= 0))
				{
					continue;
				}
				if (item instanceof Weapon)
				{
					weapons.add((long) item.getReferencePrice());
				}
				else
				{
					for (BodyPart piece : pieces)
					{
						if (item.getBodyPart() == piece)
						{
							armor.computeIfAbsent(piece, key -> new ArrayList<>()).add((long) item.getReferencePrice());
						}
					}
				}
			}
			long total = median(weapons);
			for (BodyPart piece : pieces)
			{
				total += median(armor.getOrDefault(piece, List.of()));
			}
			prices[index] = total;
		}
		_kitPrices = prices;
		LOGGER.info("LivingPopulation: gear kit prices by grade (none, D, C, B, A, S): " + java.util.Arrays.toString(prices));
		return prices;
	}

	/**
	 * What one kill in a zone brings in at a level, from the real drop lists of the monsters that live there, weighted by
	 * how many of each spawn. Cached per zone, level and spoiler, since drop data and rates do not change while running.
	 * @param zoneName the bot's zone
	 * @param level the bot's level (drops fall off against monsters far below it)
	 * @param spoiler whether it also spoils its kills
	 * @return the yield per kill, or null when the zone lists no monsters (the flat formula applies)
	 */
	private DropYield.Yield zoneYield(String zoneName, int level, boolean spoiler)
	{
		final ZoneCatalog.Zone zone = (zoneName == null) ? null : _catalog.zone(zoneName);
		if ((zone == null) || zone.monsters().isEmpty())
		{
			return null;
		}
		final boolean gear = _gear != null;
		final String key = zone.name() + '|' + level + '|' + spoiler + '|' + gear;
		final DropYield.Yield cached = _zoneYields.get(key);
		if (cached != null)
		{
			return cached;
		}
		final DropYield.Rates rates = dropRates();
		// With gear kept per slot, weapon and armor drops are rolled one by one (see zoneGear), so they are left out here.
		final DropYield.Items items = dropItems(gear ? _gear : null);
		final List<DropYield.Yield> yields = new ArrayList<>();
		final List<Integer> weights = new ArrayList<>();
		for (ZoneCatalog.Monster monster : zone.monsters())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(monster.npcId());
			if (template == null)
			{
				continue;
			}
			final List<DropYield.Drop> drops = deathDrops(template);
			final List<DropYield.Drop> spoils = spoiler ? spoilDrops(template) : List.of();
			yields.add(DropYield.perKill(drops, spoils, spoiler, template.getLevel(), level, rates, items));
			weights.add(monster.count());
		}
		final DropYield.Yield yield = yields.isEmpty() ? null : DropYield.average(yields, weights);
		if (yield != null)
		{
			_zoneYields.put(key, yield);
		}
		return yield;
	}

	/**
	 * The weapons and armor a kill in a zone can drop, each with its chance per kill, from the real drop lists of the
	 * monsters there weighted by how many of each spawn. Cached per zone, level and spoiler.
	 * @param zoneName the bot's zone
	 * @param level the bot's level
	 * @param spoiler whether it also spoils its kills
	 * @return item id to chance per kill, empty when the zone lists no monsters
	 */
	private Map<Integer, Double> zoneGear(String zoneName, int level, boolean spoiler)
	{
		final GearCatalog gear = _gear;
		final ZoneCatalog.Zone zone = (zoneName == null) ? null : _catalog.zone(zoneName);
		if ((gear == null) || (zone == null) || zone.monsters().isEmpty())
		{
			return Map.of();
		}
		return _zoneGear.computeIfAbsent(zone.name() + '|' + level + '|' + spoiler, key ->
		{
			final DropYield.Rates rates = dropRates();
			final DropYield.Items items = dropItems(null);
			final Map<Integer, Double> weighted = new HashMap<>();
			double total = 0;
			for (ZoneCatalog.Monster monster : zone.monsters())
			{
				final NpcTemplate template = NpcData.getInstance().getTemplate(monster.npcId());
				if (template == null)
				{
					continue;
				}
				final int weight = Math.max(0, monster.count());
				total += weight;
				for (Map.Entry<Integer, Double> entry : DropYield.chances(deathDrops(template), spoiler ? spoilDrops(template) : List.of(), spoiler, template.getLevel(), level, rates, items).entrySet())
				{
					if (gear.piece(entry.getKey()) != null)
					{
						weighted.merge(entry.getKey(), entry.getValue() * weight, Double::sum);
					}
				}
			}
			final Map<Integer, Double> chances = new HashMap<>();
			if (total > 0)
			{
				for (Map.Entry<Integer, Double> entry : weighted.entrySet())
				{
					chances.put(entry.getKey(), entry.getValue() / total);
				}
			}
			return Map.copyOf(chances);
		});
	}

	/** A monster's death drop list, each entry with its group's chance (100 for an ungrouped entry). */
	private static List<DropYield.Drop> deathDrops(NpcTemplate template)
	{
		final List<DropYield.Drop> drops = new ArrayList<>();
		if (template.getDropGroups() != null)
		{
			for (DropGroupHolder group : template.getDropGroups())
			{
				for (DropHolder drop : group.getDropList())
				{
					drops.add(new DropYield.Drop(drop.getItemId(), group.getChance(), drop.getChance(), drop.getMin(), drop.getMax()));
				}
			}
		}
		if (template.getDropList() != null)
		{
			for (DropHolder drop : template.getDropList())
			{
				drops.add(new DropYield.Drop(drop.getItemId(), 100, drop.getChance(), drop.getMin(), drop.getMax()));
			}
		}
		return drops;
	}

	/** A monster's spoil list. */
	private static List<DropYield.Drop> spoilDrops(NpcTemplate template)
	{
		final List<DropYield.Drop> spoils = new ArrayList<>();
		if (template.getSpoilList() != null)
		{
			for (DropHolder drop : template.getSpoilList())
			{
				spoils.add(new DropYield.Drop(drop.getItemId(), 100, drop.getChance(), drop.getMin(), drop.getMax()));
			}
		}
		return spoils;
	}

	/**
	 * Gives a bot from before gear was kept per slot the gear it had: the starter gear of its class at gear tier 0, else
	 * a full set of the grade its tier reached. Runs once per bot, on the resolver thread.
	 */
	private void convertGear(ColdBot bot, List<DecisionLog.Event> events)
	{
		final GearCatalog gear = _gear;
		final int step = _config.gearTierLevelStep();
		final int tier = bot.getGearTier();
		final Map<LivingGear.Slot, Integer> set;
		final String what;
		if ((tier <= 0) || (step <= 0))
		{
			set = gear.starter(bot.getClassId());
			what = "its starter gear";
		}
		else
		{
			final int grade = LivingSupplies.gradeFor(Math.min(bot.getLevel(), tier * step));
			set = gear.kit(bot.getClassId(), grade);
			if (set.isEmpty())
			{
				return; // the item data is not ready yet: try again next time
			}
			what = "a full " + LivingGear.gradeName(grade) + " set for its gear tier " + tier;
		}
		ColdLife.setGear(bot, set, gear);
		events.add(new DecisionLog.Event(null, "Now keeps its gear piece by piece. Wears " + what + ": " + gearNames(set)));
	}

	/** The names of the items a bot wears, in slot order. */
	private String gearNames(Map<LivingGear.Slot, Integer> gear)
	{
		final List<String> names = new ArrayList<>();
		for (int itemId : LivingGear.items(gear))
		{
			final LivingGear.Piece piece = (_gear == null) ? null : _gear.piece(itemId);
			names.add((piece == null) ? ("item " + itemId) : piece.name());
		}
		return names.isEmpty() ? "nothing" : String.join(", ", names);
	}

	/**
	 * A class's complete skill tree from the server's skill data, as a living bot learns it: the skills a trainer teaches
	 * and the ones given for free, with their SP cost and the price of the spellbook they need. Forgotten Scroll skills
	 * and skills of another race are left out. Cached per class.
	 * @param classId the class
	 * @return the tree, empty for an unknown class
	 */
	private List<SkillPlanner.Entry> skillTree(int classId)
	{
		return _skillTrees.computeIfAbsent(classId, id ->
		{
			final PlayerClass playerClass = PlayerClass.getPlayerClass(id);
			if (playerClass == null)
			{
				return List.of();
			}
			final List<SkillPlanner.Entry> tree = new ArrayList<>();
			for (SkillLearn learn : SkillTreeData.getInstance().getCompleteClassSkillTree(playerClass).values())
			{
				if ((!learn.isLearnedByNpc() && !learn.isAutoGet()) || learn.isLearnedByFS())
				{
					continue;
				}
				if (!learn.getRaces().isEmpty() && !learn.getRaces().contains(playerClass.getRace()))
				{
					continue;
				}
				int bookId = 0;
				long bookPrice = 0L;
				boolean unsold = false;
				for (ItemHolder item : learn.getRequiredItems())
				{
					final ItemTemplate template = ItemData.getInstance().getTemplate(item.getId());
					final long price = (template == null) ? 0L : template.getReferencePrice();
					if (price <= 0)
					{
						unsold = true;
					}
					if (bookId == 0)
					{
						bookId = item.getId();
					}
					bookPrice += price * Math.max(1, item.getCount());
				}
				tree.add(new SkillPlanner.Entry(learn.getSkillId(), learn.getSkillLevel(), learn.getGetLevel(), learn.getLevelUpSp(), bookId, unsold ? 0L : bookPrice, learn.isAutoGet()));
			}
			return List.copyOf(tree);
		});
	}

	/**
	 * SP a cold bot earns per point of experience in a zone: its monsters' SP over their experience, weighted by how
	 * many of each spawn there, times the server's SP rate over its XP rate. A zone without a monster list uses the
	 * average of all zones.
	 */
	private double spRatio(String zoneName)
	{
		final String key = (zoneName == null) ? "" : zoneName;
		final Double cached = _spRatios.get(key);
		if (cached != null)
		{
			return cached;
		}
		final List<ZoneCatalog.Monster> monsters = new ArrayList<>();
		final ZoneCatalog.Zone zone = (zoneName == null) ? null : _catalog.zone(zoneName);
		if ((zone != null) && !zone.monsters().isEmpty())
		{
			monsters.addAll(zone.monsters());
		}
		else
		{
			for (ZoneCatalog.Zone any : _catalog.zones())
			{
				monsters.addAll(any.monsters());
			}
		}
		double sp = 0;
		double exp = 0;
		for (ZoneCatalog.Monster monster : monsters)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(monster.npcId());
			if (template != null)
			{
				sp += template.getSP() * monster.count();
				exp += template.getExp() * monster.count();
			}
		}
		final double ratio = (exp <= 0) ? 0.0 : ((sp / exp) * (Math.max(0.0, RatesConfig.RATE_SP) / Math.max(0.01, RatesConfig.RATE_XP)));
		_spRatios.put(key, ratio);
		return ratio;
	}

	/** The server's drop rates and rules from Rates.ini, as the cold income reads them. */
	private static DropYield.Rates dropRates()
	{
		return new DropYield.Rates(RatesConfig.RATE_DEATH_DROP_CHANCE_MULTIPLIER, RatesConfig.RATE_DEATH_DROP_AMOUNT_MULTIPLIER, RatesConfig.RATE_SPOIL_DROP_CHANCE_MULTIPLIER, RatesConfig.RATE_SPOIL_DROP_AMOUNT_MULTIPLIER, (RatesConfig.RATE_DROP_CHANCE_BY_ID == null) ? Map.of() : RatesConfig.RATE_DROP_CHANCE_BY_ID, (RatesConfig.RATE_DROP_AMOUNT_BY_ID == null) ? Map.of() : RatesConfig.RATE_DROP_AMOUNT_BY_ID, RatesConfig.DROP_MAX_OCCURRENCES_NORMAL, new DropYield.Gap(RatesConfig.DROP_ADENA_MIN_LEVEL_DIFFERENCE, RatesConfig.DROP_ADENA_MAX_LEVEL_DIFFERENCE, RatesConfig.DROP_ADENA_MIN_LEVEL_GAP_CHANCE), new DropYield.Gap(RatesConfig.DROP_ITEM_MIN_LEVEL_DIFFERENCE, RatesConfig.DROP_ITEM_MAX_LEVEL_DIFFERENCE, RatesConfig.DROP_ITEM_MIN_LEVEL_GAP_CHANCE));
	}

	/**
	 * Shop prices as a player gets them: half the item's reference price, nothing for quest or unsellable items.
	 * @param gear when set, weapons and armor are worth nothing here (they are rolled one by one instead)
	 */
	private static DropYield.Items dropItems(GearCatalog gear)
	{
		return new DropYield.Items()
		{
			@Override
			public long sellPrice(int itemId)
			{
				final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
				if ((item == null) || item.isQuestItem() || !item.isSellable() || ((gear != null) && (gear.piece(itemId) != null)))
				{
					return 0L;
				}
				return Math.max(0L, item.getReferencePrice() / 2L);
			}

			@Override
			public boolean herb(int itemId)
			{
				final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
				return (item != null) && item.hasExImmediateEffect();
			}
		};
	}

	private static long median(List<Long> values)
	{
		if (values.isEmpty())
		{
			return 0L;
		}
		final List<Long> sorted = new ArrayList<>(values);
		sorted.sort(null);
		return sorted.get(sorted.size() / 2);
	}

	private static long price(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return (template == null) ? 0L : Math.max(0L, template.getReferencePrice());
	}

	private int[] humanPlayerLevels()
	{
		final List<Integer> levels = new ArrayList<>();
		for (Player player : World.getInstance().getPlayers())
		{
			// Real human players only: online, with a network client, and not a phantom (phantoms are buddy bots with
			// no client attached). FakePlayers are NPCs and never appear in the player list.
			if ((player != null) && player.isOnline() && (player.getClient() != null) && !player.isBuddyBot())
			{
				levels.add(player.getLevel());
			}
		}

		final int[] out = new int[levels.size()];
		for (int index = 0; index < out.length; index++)
		{
			out[index] = levels.get(index);
		}
		return out;
	}

	private void safeHandoffTick()
	{
		try
		{
			final List<ColdBot> bots = new ArrayList<>(_bots);
			bots.removeIf(bot -> _removals.contains(bot.getId()) && !_handoff.isHot(bot.getId())); // never bring one back
			_handoff.tick(bots, System.currentTimeMillis());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: handoff tick failed: " + e.getMessage(), e);
		}
	}

	private void safeSnapshotTick()
	{
		try
		{
			final ExperienceData experience = ExperienceData.getInstance();
			final String json = ColdBotStatus.render(_bots, _targetLevel, System.currentTimeMillis(), _config.gearTierLevelStep(), equipment(), level -> Math.max(1L, experience.getExpForLevel(level + 1) - experience.getExpForLevel(level)), _config.maxLevel());
			final Path path = Path.of(_config.snapshotFile());
			if (path.getParent() != null)
			{
				Files.createDirectories(path.getParent());
			}
			Files.write(path, json.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: snapshot write failed: " + e.getMessage(), e);
		}
	}

	/** @return the monitor's request file, next to the snapshot */
	private Path requestFile()
	{
		return Path.of(_config.snapshotFile()).resolveSibling("LivingPopulation-requests.json");
	}

	/**
	 * Reads the monitor's request file, if there is one, and deletes it: bots to remove (by id, checked against the
	 * name the monitor showed, so a stale request never hits another bot) and optionally a new population size, which
	 * applies right away so the removed bots are not born again. Runs on the resolver thread, or at start.
	 */
	private void readRequests()
	{
		final Path file = requestFile();
		if (!Files.exists(file))
		{
			return;
		}
		String text;
		try
		{
			text = Files.readString(file, StandardCharsets.UTF_8);
			Files.delete(file);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not read " + file + ": " + e.getMessage(), e);
			return;
		}
		try
		{
			if (!(Json.parse(text) instanceof Map<?, ?> root))
			{
				return;
			}
			if (root.get("populationSize") instanceof Number size)
			{
				_populationTarget = Math.max(0, size.intValue());
				LOGGER.info("LivingPopulation: population size set to " + _populationTarget + " from the monitor.");
			}
			if (root.get("remove") instanceof List<?> list)
			{
				final Map<Long, ColdBot> byId = new HashMap<>();
				for (ColdBot bot : _bots)
				{
					byId.put(bot.getId(), bot);
				}
				int marked = 0;
				for (Object item : list)
				{
					if ((item instanceof Map<?, ?> request) && (request.get("id") instanceof Number id))
					{
						final ColdBot bot = byId.get(id.longValue());
						if ((bot != null) && bot.getName().equals(request.get("name")) && _removals.add(bot.getId()))
						{
							bot.getDecisions().add(System.currentTimeMillis(), null, "Marked for removal from the monitor");
							marked++;
						}
					}
				}
				if (marked > 0)
				{
					LOGGER.info("LivingPopulation: " + marked + " bot(s) marked for removal from the monitor.");
				}
			}
		}
		catch (Json.JsonException e)
		{
			LOGGER.warning("LivingPopulation: ignored an unreadable request file from the monitor: " + e.getMessage());
		}
	}

	/**
	 * Removes the bots marked for removal: a hot one is cooled first (its character leaves the world), and once it is
	 * cold and no character of it is live, its row and its character (only one on the Living Population account) are
	 * deleted for good. A bot not ready yet is retried on the next tick.
	 */
	private void removeRequested()
	{
		if (_removals.isEmpty())
		{
			return;
		}
		final Set<Long> live = PhantomManager.getInstance().livingPopulationPhantoms().keySet();
		int removed = 0;
		for (ColdBot bot : new ArrayList<>(_bots))
		{
			final long id = bot.getId();
			if (!_removals.contains(id))
			{
				continue;
			}
			if (_handoff.isHot(id))
			{
				_handoff.release(id); // cooled on the game thread; deleted on a later tick
				continue;
			}
			if (bot.isHotLock() || live.contains(id))
			{
				continue; // a transition is finishing, or its character is still in the world
			}
			if (!_dao.delete(bot))
			{
				continue;
			}
			if ((bot.getCharId() > 0) && _dao.isLivingCharacter(bot.getCharId()))
			{
				GameClient.deleteCharByObjId((int) bot.getCharId());
			}
			_bots.remove(bot);
			_removals.remove(id);
			removed++;
			LOGGER.info("LivingPopulation: removed bot '" + bot.getName() + "' (level " + bot.getLevel() + ") and its character.");
		}
		// Ids of bots that no longer exist are dropped.
		_removals.removeIf(id -> _bots.stream().noneMatch(bot -> bot.getId() == id));
		if (removed > 0)
		{
			LOGGER.info("LivingPopulation: " + removed + " bot(s) removed; " + _bots.size() + " left (target " + _populationTarget + ").");
		}
	}

	/** What each bot wears, for the monitor: a hot bot's live paperdoll, a cold bot's gear kept per slot. */
	private Map<Long, List<String[]>> equipment()
	{
		final Map<Long, List<String[]>> equipment = new HashMap<>(_handoff.hotEquipment());
		final GearCatalog gear = _gear;
		if (gear == null)
		{
			return equipment;
		}
		for (ColdBot bot : _bots)
		{
			final Map<LivingGear.Slot, Integer> worn = LivingGear.decode(bot.getGear());
			if ((worn == null) || equipment.containsKey(bot.getId()))
			{
				continue;
			}
			final List<String[]> items = new ArrayList<>();
			for (Map.Entry<LivingGear.Slot, Integer> entry : worn.entrySet())
			{
				final LivingGear.Piece piece = gear.piece(entry.getValue());
				items.add(new String[]
				{
					entry.getKey().label(),
					(piece == null) ? ("item " + entry.getValue()) : piece.name()
				});
			}
			equipment.put(bot.getId(), items);
		}
		return equipment;
	}

	/**
	 * A short human-readable status line for a GM command: total, per-race counts, and the level range.
	 * @return the status text
	 */
	public String statusText()
	{
		if (!_started.get())
		{
			return "Living Population: not running.";
		}

		final Map<String, Integer> byRace = new TreeMap<>();
		int minLevel = Integer.MAX_VALUE;
		int maxLevel = 0;
		for (ColdBot bot : _bots)
		{
			byRace.merge(bot.getRace() == null ? "unknown" : bot.getRace(), 1, Integer::sum);
			minLevel = Math.min(minLevel, bot.getLevel());
			maxLevel = Math.max(maxLevel, bot.getLevel());
		}

		final StringBuilder sb = new StringBuilder();
		sb.append("Living Population: ").append(_bots.size()).append(" cold bot(s)");
		if (!_bots.isEmpty())
		{
			sb.append(", levels ").append(minLevel).append('-').append(maxLevel).append(", ");
			boolean first = true;
			for (Map.Entry<String, Integer> entry : byRace.entrySet())
			{
				if (!first)
				{
					sb.append(", ");
				}
				first = false;
				sb.append(entry.getKey()).append(':').append(entry.getValue().intValue());
			}
		}
		sb.append(" | director target: ").append(_targetLevel > 0 ? _targetLevel : "none");
		sb.append(" | hot: ").append(_handoff.hotCount());
		return sb.toString();
	}

	/**
	 * A GM view of one bot: its state and its most recent decisions, newest first, one chat line each.
	 * @param name the bot's name (case-insensitive)
	 * @param maxDecisions how many decisions to include
	 * @return the lines to show; a single explanatory line when the bot is unknown or the simulation is not running
	 */
	public List<String> botDetailLines(String name, int maxDecisions)
	{
		final List<String> lines = new ArrayList<>();
		if (!_started.get())
		{
			lines.add("Living Population: not running.");
			return lines;
		}

		ColdBot found = null;
		for (ColdBot bot : _bots)
		{
			if ((bot.getName() != null) && bot.getName().equalsIgnoreCase(name))
			{
				found = bot;
				break;
			}
		}
		if (found == null)
		{
			lines.add("Living Population: no bot named " + name + ".");
			return lines;
		}

		lines.add(found.getName() + " (" + found.getRace() + ") level " + found.getLevel() + ", " + found.getPhase() + ", goal " + found.getGoal() + ", gear tier " + found.getGearTier());
		lines.add(DecisionLog.num(found.getAdena()) + " adena, " + DecisionLog.num(found.getSoulshots()) + " soulshots, " + DecisionLog.num(found.getPotions()) + " potions, " + DecisionLog.num(found.getEscapes()) + " Scrolls of Escape" + ((found.getDeaths() > 0) ? (", died " + found.getDeaths() + ((found.getDeaths() == 1) ? " time" : " times")) : "") + ((found.getLoot() > 0) ? (", loot worth " + DecisionLog.num(found.getLoot()) + " adena") : ""));
		if ((_gear != null) && (found.getGear() != null))
		{
			lines.add("Wears " + gearNames(LivingGear.decode(found.getGear())));
		}
		final String where = (found.getTown() != null) ? ("town " + found.getTown()) : ((found.getZone() != null) ? ("zone " + found.getZone()) : "no zone yet");
		lines.add("Now " + found.getActivity() + ", " + where + ", at " + found.getX() + ", " + found.getY() + ", " + found.getZ());
		final SimpleDateFormat clock = new SimpleDateFormat("MM-dd HH:mm");
		int shown = 0;
		for (DecisionLog.Entry entry : found.getDecisions().newestFirst())
		{
			if (shown++ >= maxDecisions)
			{
				break;
			}
			lines.add(clock.format(new Date(entry.at())) + " " + entry.text());
		}
		return lines;
	}

	/**
	 * @return the number of cold bots currently held in memory
	 */
	public int botCount()
	{
		return _bots.size();
	}

	/**
	 * A whisper to a Living Population bot, live or only a row: it answers like any player, from what it is really doing.
	 * Called by the whisper handler before the "not online" checks.
	 * @param sender who whispered
	 * @param name the name whispered to
	 * @param text the message
	 * @return whether the name is one of this population's bots (the whisper was taken)
	 */
	public boolean handleWhisper(Player sender, String name, String text)
	{
		if ((_executor == null) || (sender == null) || (name == null) || !FakePlayersConfig.FAKE_PLAYERS_ENABLED || !FakePlayersConfig.FAKE_PLAYER_CHAT)
		{
			return false;
		}
		final ColdBot bot = botNamed(name);
		if (bot == null)
		{
			return false;
		}
		final Player live = PhantomManager.getInstance().livingPopulationPhantoms().get(bot.getId());
		final int level = (live != null) ? live.getLevel() : bot.getLevel();
		final PlayerClass playerClass = PlayerClass.getPlayerClass((live != null) ? live.getPlayerClass().getId() : bot.getClassId());
		final String location = (live != null) ? FakePlayerChatManager.nearestLocation(live) : FakePlayerChatManager.nearestLocation(bot.getX(), bot.getY());
		final String activity = LivingChat.activity(bot.getActivity(), bot.getZone(), bot.getTown());
		sender.sendPacket(new CreatureSay(sender, ChatType.WHISPER, "->" + bot.getName(), text));
		FakePlayerChatManager.getInstance().handleLivingWhisper(sender, bot.getName(), level, displayName(playerClass), displayName(bot.getRace()), location, activity, text);
		return true;
	}

	// Party invites: a bot answers like a player clicking accept, after a moment.
	private static final int PARTY_ANSWER_MIN_MS = 1000;
	private static final int PARTY_ANSWER_MAX_MS = 2500;
	// Fighters decline a party this many levels away from the inviter; buffers and healers go anyway.
	static final int PARTY_LEVEL_GAP = 10;
	// How long the invite waits for the bot's character to come into the world before it gives up.
	private static final long PARTY_GO_LIVE_TIMEOUT_MS = 20_000L;
	private final Set<Long> _joining = ConcurrentHashMap.newKeySet();

	/**
	 * @param botLevel the bot's level
	 * @param inviterLevel the inviter's level
	 * @param support whether the bot is a buffer or healer
	 * @return why it declines for the level gap, or null when the gap is fine
	 */
	static String levelGapRefusal(int botLevel, int inviterLevel, boolean support)
	{
		if (support || (Math.abs(botLevel - inviterLevel) <= PARTY_LEVEL_GAP))
		{
			return null;
		}
		return (botLevel < inviterLevel) ? ("too low for u, im " + botLevel + " :/") : ("lvl gap too big, im " + botLevel);
	}

	/**
	 * A party invite to a Living Population bot, live or only a row, from anywhere. Like a town bot it accepts only after
	 * it agreed in a whisper; otherwise it declines and asks what the player wants. A fighter far from the inviter's level
	 * declines; buffers and healers do not mind. On accept it becomes a live character where it is, joins at once and
	 * travels to the inviter on the clock (see {@link LivingPartyTravel}). Called by the invite packet before the "no
	 * such player" check.
	 * @return whether the name is one of this population's bots (the invite was taken)
	 */
	public boolean onPartyInvite(Player owner, String name)
	{
		if ((_executor == null) || (owner == null) || (name == null))
		{
			return false;
		}
		final ColdBot bot = botNamed(name);
		if (bot == null)
		{
			return false;
		}
		final Player live = PhantomManager.getInstance().livingPopulationPhantoms().get(bot.getId());
		if ((live != null) && live.isInParty())
		{
			if (live.getParty() != owner.getParty())
			{
				owner.sendPacket(SystemMessageId.THE_PLAYER_DECLINED_TO_JOIN_YOUR_PARTY);
				whisper(owner, bot.getName(), "already in a pt, sry");
			}
			return true;
		}
		if (!FakePlayerChatManager.mayInviteLivingBot(owner))
		{
			return true; // the stock refusal was sent
		}
		final SystemMessage invited = new SystemMessage(SystemMessageId.YOU_HAVE_INVITED_S1_TO_YOUR_PARTY);
		invited.addString(bot.getName());
		owner.sendPacket(invited);
		final int answerIn = Rnd.get(PARTY_ANSWER_MIN_MS, PARTY_ANSWER_MAX_MS);
		if (!FakePlayerChatManager.isLivingPartyAgreed(owner, bot.getName()))
		{
			// Out of the blue: decline, then ask what they want, in its own voice.
			ThreadPool.schedule(() -> owner.sendPacket(SystemMessageId.THE_PLAYER_DECLINED_TO_JOIN_YOUR_PARTY), answerIn);
			handleWhisperQuietly(owner, bot, FakePlayerChatManager.INVITE_OUT_OF_BLUE);
			return true;
		}
		final PlayerClass playerClass = PlayerClass.getPlayerClass((live != null) ? live.getPlayerClass().getId() : bot.getClassId());
		final boolean support = (playerClass != null) && PhantomManager.roleForClass(playerClass).isSupport();
		final String gap = levelGapRefusal((live != null) ? live.getLevel() : bot.getLevel(), owner.getLevel(), support);
		if (gap != null)
		{
			ThreadPool.schedule(() ->
			{
				owner.sendPacket(SystemMessageId.THE_PLAYER_DECLINED_TO_JOIN_YOUR_PARTY);
				whisper(owner, bot.getName(), gap);
			}, answerIn);
			return true;
		}
		if (ColdLife.CLASS_MASTER.equals(bot.getActivity()))
		{
			ThreadPool.schedule(() ->
			{
				owner.sendPacket(SystemMessageId.THE_PLAYER_DECLINED_TO_JOIN_YOUR_PARTY);
				whisper(owner, bot.getName(), "doing my class change rn, inv me after");
			}, answerIn);
			return true;
		}
		if (!_joining.add(bot.getId()))
		{
			return true; // already answering an invite
		}
		bot.getDecisions().add(System.currentTimeMillis(), null, "Accepted a party invite from " + owner.getName());
		final ScheduledExecutorService executor = _executor;
		ThreadPool.schedule(() -> executor.execute(() -> _handoff.activateForParty(bot, System.currentTimeMillis())), answerIn);
		waitLiveThenJoin(owner, bot, System.currentTimeMillis() + answerIn + PARTY_GO_LIVE_TIMEOUT_MS);
		return true;
	}

	/** Checks every second until the bot's character is in the world, then joins it to the party (game thread). */
	private void waitLiveThenJoin(Player owner, ColdBot bot, long giveUpAt)
	{
		ThreadPool.schedule(() ->
		{
			final Player live = PhantomManager.getInstance().livingPopulationPhantoms().get(bot.getId());
			if ((live == null) || !_handoff.isHot(bot.getId()) || (World.getInstance().findObject(live.getObjectId()) == null))
			{
				if (System.currentTimeMillis() < giveUpAt)
				{
					waitLiveThenJoin(owner, bot, giveUpAt);
					return;
				}
				_joining.remove(bot.getId());
				owner.sendPacket(SystemMessageId.THE_PLAYER_DECLINED_TO_JOIN_YOUR_PARTY);
				whisper(owner, bot.getName(), "sry cant rn");
				return;
			}
			try
			{
				joinParty(owner, bot, live);
			}
			finally
			{
				_joining.remove(bot.getId());
			}
		}, 1000);
	}

	private void joinParty(Player owner, ColdBot bot, Player live)
	{
		if (!owner.isOnline())
		{
			return;
		}
		final ColdLife.Context life = _handoff.life();
		final LivingPartyTravel travel = new LivingPartyTravel((life == null) ? null : life.catalog(), (life == null) ? 120.0 : life.travel().moveSpeed());
		_handoff.setPartied(bot, true);
		PhantomManager.getInstance().adoptLivingForParty(live);
		if (!PhantomPartyManager.getInstance().joinFromTownFake(owner, live, lastSeen -> _handoff.setPartied(bot, false), travel))
		{
			return; // released at once: back on its own
		}
		bot.getDecisions().add(System.currentTimeMillis(), null, "Joined " + owner.getName() + "'s party");
		if (live.calculateDistance2D(owner) > LivingPartyTravel.ARRIVE_RANGE)
		{
			final LivingPartyTravel.Plan plan = travel.start(live, owner);
			final long minutes = Math.max(1L, Math.round(plan.seconds() / 60.0));
			whisper(owner, bot.getName(), plan.teleports() ? ("omw, gk to " + plan.town() + ", ~" + minutes + " min") : ("omw, ~" + minutes + " min walk"));
		}
	}

	/** A whisper from the bot to the player, by name (the bot needs no live character). */
	private static void whisper(Player player, String botName, String text)
	{
		if ((player != null) && player.isOnline())
		{
			player.sendPacket(new CreatureSay(null, ChatType.WHISPER, botName, text));
		}
	}

	/** The bot answers through the brain without the player's line echoed (an invite is not something they typed). */
	private void handleWhisperQuietly(Player sender, ColdBot bot, String text)
	{
		final Player live = PhantomManager.getInstance().livingPopulationPhantoms().get(bot.getId());
		final int level = (live != null) ? live.getLevel() : bot.getLevel();
		final PlayerClass playerClass = PlayerClass.getPlayerClass((live != null) ? live.getPlayerClass().getId() : bot.getClassId());
		final String location = (live != null) ? FakePlayerChatManager.nearestLocation(live) : FakePlayerChatManager.nearestLocation(bot.getX(), bot.getY());
		FakePlayerChatManager.getInstance().handleLivingWhisper(sender, bot.getName(), level, displayName(playerClass), displayName(bot.getRace()), location, LivingChat.activity(bot.getActivity(), bot.getZone(), bot.getTown()), text);
	}

	private ColdBot botNamed(String name)
	{
		for (ColdBot candidate : _bots)
		{
			if (name.equalsIgnoreCase(candidate.getName()))
			{
				return candidate;
			}
		}
		return null;
	}

	/** "ELVEN_MYSTIC" or "DarkElf" as "Elven Mystic" or "Dark Elf". */
	private static String displayName(Object value)
	{
		if (value == null)
		{
			return "";
		}
		final String raw = (value instanceof Enum<?> e) ? e.name() : value.toString().replaceAll("([a-z])([A-Z])", "$1_$2");
		final StringBuilder out = new StringBuilder();
		for (String word : raw.toLowerCase().split("_"))
		{
			if (!word.isEmpty())
			{
				out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return out.toString();
	}

	public static LivingPopulationManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final LivingPopulationManager INSTANCE = new LivingPopulationManager();
	}
}
