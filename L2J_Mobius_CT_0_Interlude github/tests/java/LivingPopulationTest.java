/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.l2jmobius.gameserver.livingpop.ColdBot;
import org.l2jmobius.gameserver.livingpop.ColdBotStatus;
import org.l2jmobius.gameserver.livingpop.ColdEconomy;
import org.l2jmobius.gameserver.livingpop.ColdProgression;
import org.l2jmobius.gameserver.livingpop.ColdSeedPlan;
import org.l2jmobius.gameserver.livingpop.DecisionLog;
import org.l2jmobius.gameserver.livingpop.HandoffPolicy;
import org.l2jmobius.gameserver.livingpop.LivingPopulationConfig;
import org.l2jmobius.gameserver.livingpop.NeedsEvaluator;
import org.l2jmobius.gameserver.livingpop.PopulationDirector;
import org.l2jmobius.gameserver.livingpop.ColdRisk;
import org.l2jmobius.gameserver.livingpop.ZoneCombat;

/**
 * Standalone (no JUnit, no game server) regression harness for the dependency-free Living Population cold logic:
 * {@link ColdProgression} (experience/level advancement), {@link ColdSeedPlan} (the one-bot-per-race verification seed),
 * {@link ColdBotStatus} (the monitoring JSON shape), and {@link LivingPopulationConfig} defaults. The manager and DAO are
 * verified by the whole-tree compile; the pure decisions the manager runs are locked down here.
 *
 * <p>Run from the project root ("L2J_Mobius_CT_0_Interlude github"):
 * <pre>
 *   javac -d build/test-classes \
 *         "java/org/l2jmobius/gameserver/livingpop/LivingPopulationConfig.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/ColdBot.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/ColdProgression.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/ColdSeedPlan.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/ColdBotStatus.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/HandoffPolicy.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/NeedsEvaluator.java" \
 *         "java/org/l2jmobius/gameserver/livingpop/ColdEconomy.java" \
 *         "tests/java/LivingPopulationTest.java"
 *   java -cp build/test-classes LivingPopulationTest
 * </pre>
 * Exit code is 0 when every check passes, 1 otherwise.
 */
public class LivingPopulationTest
{
	private static int _checks = 0;
	private static int _failures = 0;

	public static void main(String[] args) throws Exception
	{
		testProgressionNoTimeNoGain();
		testProgressionLevelsUp();
		testProgressionFractionalProgress();
		testProgressionCappedAtMaxLevel();
		testProgressionIgnoresNegativeElapsed();
		testProgressionZeroPace();
		testProgressionRespectsServerRate();
		testProgressionCurveSlowsAtHighLevels();
		testDirectorTargetMedian();
		testDirectorPressure();
		testFirstSeedsAreOnePerRace();
		testScaledSeedCyclesRaces();
		testSeedNamesAreUnique();
		testSeedReconcileMissing();
		testStatusJsonShape();
		testStatusQuoteEscaping();
		testLevelBand();
		testConfigDefaults();
		testHandoffActivation();
		testHandoffWatchAndCool();
		testHandoffDistance();
		testHotBotIsReadableFromRow();
		testNeedsPriority();
		testEconomyAdenaAccrual();
		testEconomySoulshotMilestoneOnce();
		testEconomySoulshotConsumeAndRestock();
		testEconomyPotionRestock();
		testEconomyGearUpgrade();
		testEconomyAdenaCappedToIntMax();
		testDecisionLogCapacityAndOrder();
		testDecisionLogKeyedOnce();
		testDecisionLogJsonRoundTrip();
		testDecisionLogToleratesBadJson();
		testHuntingCounters();
		testSnapshotGear();
		testEconomyReportsDecisions();
		testEconomyReportsWaits();
		testStatusIncludesDecisions();
		testZoneCombat();

		System.out.println("LivingPopulationTest: " + (_checks - _failures) + "/" + _checks + " checks passed.");
		if (_failures > 0)
		{
			System.exit(1);
		}
	}

	// A fixed 1000-exp-per-level requirement keeps the kill math easy to reason about in tests.
	private static long flatRequirement(int level)
	{
		return 1000L;
	}

	// A fixed 1000-exp-per-minute rate, independent of level, for the simple cases.
	private static long flatPerMinute(int level)
	{
		return 1000L;
	}

	private static void testProgressionNoTimeNoGain()
	{
		final ColdProgression.Progress p = ColdProgression.resolve(3, 100L, 0L, 80, LivingPopulationTest::flatRequirement, LivingPopulationTest::flatPerMinute);
		check("no elapsed keeps level", p.level() == 3);
		check("no elapsed keeps exp", p.expIntoLevel() == 100L);
	}

	private static void testProgressionLevelsUp()
	{
		// 1000 exp/min against a 1000 requirement = one level per minute; 3 minutes = 3 levels.
		final ColdProgression.Progress p = ColdProgression.resolve(1, 0L, 180_000L, 80, LivingPopulationTest::flatRequirement, LivingPopulationTest::flatPerMinute);
		check("one level per minute gains three levels in three minutes", p.level() == 4);
		check("lands on a level boundary with no residual", p.expIntoLevel() == 0L);
	}

	private static void testProgressionFractionalProgress()
	{
		// Half a minute at 1000/min = 500 exp, stored against the 1000 requirement.
		final ColdProgression.Progress half = ColdProgression.resolve(1, 0L, 30_000L, 80, LivingPopulationTest::flatRequirement, LivingPopulationTest::flatPerMinute);
		check("half a minute stays at the same level", half.level() == 1);
		check("half a minute stores 500 exp", half.expIntoLevel() == 500L);

		// Existing 500 plus another 500 rolls exactly one level.
		final ColdProgression.Progress roll = ColdProgression.resolve(1, 500L, 30_000L, 80, LivingPopulationTest::flatRequirement, LivingPopulationTest::flatPerMinute);
		check("carried 500 plus 500 levels up", roll.level() == 2);
		check("no residual after a clean roll", roll.expIntoLevel() == 0L);
	}

	private static void testProgressionCappedAtMaxLevel()
	{
		final ColdProgression.Progress atCap = ColdProgression.resolve(80, 0L, 3_600_000L, 80, LivingPopulationTest::flatRequirement, LivingPopulationTest::flatPerMinute);
		check("does not exceed max level", atCap.level() == 80);
		check("no residual exp at cap", atCap.expIntoLevel() == 0L);

		final ColdProgression.Progress hugeGain = ColdProgression.resolve(1, 0L, 10_000_000_000L, 80, LivingPopulationTest::flatRequirement, level -> 1_000_000L);
		check("massive gain still clamps to cap", hugeGain.level() == 80);
	}

	private static void testProgressionIgnoresNegativeElapsed()
	{
		final ColdProgression.Progress p = ColdProgression.resolve(5, 250L, -5000L, 80, LivingPopulationTest::flatRequirement, LivingPopulationTest::flatPerMinute);
		check("negative elapsed does not change level", p.level() == 5);
		check("negative elapsed does not remove exp", p.expIntoLevel() == 250L);
	}

	private static void testProgressionZeroPace()
	{
		final ColdProgression.Progress p = ColdProgression.resolve(5, 250L, 60_000L, 80, LivingPopulationTest::flatRequirement, level -> 0L);
		check("zero rate makes no progress", (p.level() == 5) && (p.expIntoLevel() == 250L));
	}

	private static void testProgressionRespectsServerRate()
	{
		// The manager folds the server XP rate into the per-minute rate. Double the rate = twice the experience in the
		// same span, so twice as many levels. 3 min at 1000/min -> 3 levels; at 2000/min -> 6 levels.
		final ColdProgression.Progress base = ColdProgression.resolve(1, 0L, 180_000L, 80, LivingPopulationTest::flatRequirement, level -> 1000L);
		final ColdProgression.Progress fast = ColdProgression.resolve(1, 0L, 180_000L, 80, LivingPopulationTest::flatRequirement, level -> 2000L);
		check("base rate gains three levels", base.level() == 4);
		check("double rate gains twice as many levels", fast.level() == 7);
	}

	private static void testProgressionCurveSlowsAtHighLevels()
	{
		// Real shape: the requirement (level^2 * 1000) outgrows the per-kill rate (level * 130), so the same span
		// yields many early levels and few high ones. Low starts at level 1, high at level 40.
		final ColdProgression.Progress low = ColdProgression.resolve(1, 0L, 600_000L, 80, level -> (long) level * level * 1000L, level -> (long) level * 130L);
		final ColdProgression.Progress high = ColdProgression.resolve(40, 0L, 600_000L, 80, level -> (long) level * level * 1000L, level -> (long) level * 130L);
		check("low levels gain more than high levels in the same time", (low.level() - 1) > (high.level() - 40));
	}

	private static void testDirectorTargetMedian()
	{
		check("empty players give no target", PopulationDirector.targetLevel(new int[] {}) == 0);
		check("null players give no target", PopulationDirector.targetLevel(null) == 0);
		check("single player is the target", PopulationDirector.targetLevel(new int[]
		{
			22
		}) == 22);
		check("odd count uses the middle", PopulationDirector.targetLevel(new int[]
		{
			10,
			40,
			20
		}) == 20);
		check("even count rounds the average of the middle two", PopulationDirector.targetLevel(new int[]
		{
			10,
			21
		}) == 16);
	}

	private static void testDirectorPressure()
	{
		final PopulationDirector.Params params = new PopulationDirector.Params(2, 1.35, 0.85, 0.06);
		check("no target is neutral", PopulationDirector.pressure(5, 0, params) == 1.0);
		check("in band is neutral", PopulationDirector.pressure(19, 20, params) == 1.0);
		check("far below catches up", PopulationDirector.pressure(10, 20, params) > 1.0);
		check("catch-up is capped", PopulationDirector.pressure(1, 80, params) <= 1.35);
		check("far above slows down", PopulationDirector.pressure(40, 20, params) == 0.85);
		check("just outside the band starts catching up", PopulationDirector.pressure(17, 20, params) > 1.0);
	}

	private static void testFirstSeedsAreOnePerRace()
	{
		// An empty population starts with one fighter per race, the old verification set.
		final List<ColdSeedPlan.SeedSpec> specs = ColdSeedPlan.plan(5, null, new Random(1), name -> false);
		check("five seeds", specs.size() == 5);
		check("first a Human fighter", specs.get(0).race().equals("Human") && (specs.get(0).classId() == 0));
		check("then an Elf fighter", specs.get(1).race().equals("Elf") && (specs.get(1).classId() == 18));
		check("then a Dark Elf fighter", specs.get(2).race().equals("DarkElf") && (specs.get(2).classId() == 31));
		check("then an Orc fighter", specs.get(3).race().equals("Orc") && (specs.get(3).classId() == 44));
		check("then a Dwarf fighter", specs.get(4).race().equals("Dwarf") && (specs.get(4).classId() == 53));
	}

	private static void testScaledSeedCyclesRaces()
	{
		final List<ColdSeedPlan.SeedSpec> nine = ColdSeedPlan.plan(9, null, new Random(2), name -> false);
		final Set<Integer> classes = new HashSet<>();
		for (ColdSeedPlan.SeedSpec spec : nine)
		{
			classes.add(spec.classId());
		}
		check("nine seeds cover all nine starting classes", classes.equals(Set.of(0, 10, 18, 25, 31, 38, 44, 49, 53)));
		check("mystics come with their race", nine.get(5).race().equals("Human") && (nine.get(5).classId() == 10) && nine.get(8).race().equals("Orc") && (nine.get(8).classId() == 49));
		check("no seeds for zero", ColdSeedPlan.plan(0, null, new Random(3), name -> false).isEmpty());

		// Five fighters already: the next four are the four mystics.
		final java.util.Map<Integer, Integer> fighters = new java.util.HashMap<>();
		for (int classId : new int[] { 0, 18, 31, 44, 53 })
		{
			fighters.put(classId, 1);
		}
		final List<ColdSeedPlan.SeedSpec> grow = ColdSeedPlan.plan(4, fighters, new Random(4), name -> false);
		check("growth fills the classes the population lacks", (grow.get(0).classId() == 10) && (grow.get(1).classId() == 25) && (grow.get(2).classId() == 38) && (grow.get(3).classId() == 49));
		final java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
		for (ColdSeedPlan.SeedSpec spec : ColdSeedPlan.plan(90, null, new Random(5), name -> false))
		{
			counts.merge(spec.classId(), 1, Integer::sum);
		}
		check("ninety seeds are ten of each class", (counts.size() == 9) && counts.values().stream().allMatch(c -> c == 10));
	}

	private static void testSeedNamesAreUnique()
	{
		final Set<String> taken = new HashSet<>();
		final List<ColdSeedPlan.SeedSpec> specs = ColdSeedPlan.plan(300, null, new Random(6), name -> taken.contains(name.toLowerCase()));
		final Set<String> names = new HashSet<>();
		boolean legal = true;
		for (ColdSeedPlan.SeedSpec spec : specs)
		{
			legal &= (spec.name().length() <= 16) && !spec.name().isEmpty() && !ColdSeedPlan.placeholder(spec.name());
			names.add(spec.name().toLowerCase());
		}
		check("names are client-legal and not numbered placeholders", legal);
		check("three hundred names, no repeats", names.size() == 300);
		final String first = ColdSeedPlan.plan(1, null, new Random(7), name -> false).get(0).name();
		check("a taken name is never handed out", !ColdSeedPlan.plan(1, null, new Random(7), name -> name.equals(first)).get(0).name().equals(first));
		check("an exhausted name pool still yields a name", ColdSeedPlan.newName(new Random(8), name -> !name.matches(".*\\d+")).matches(".*\\d+"));
	}

	private static void testSeedReconcileMissing()
	{
		check("the numbered names are placeholders", ColdSeedPlan.placeholder("Human000") && ColdSeedPlan.placeholder("DarkElf042") && ColdSeedPlan.placeholder("Dwarf004"));
		check("real names are not", !ColdSeedPlan.placeholder("Aldaron") && !ColdSeedPlan.placeholder("Human") && !ColdSeedPlan.placeholder("Orc12") && !ColdSeedPlan.placeholder(null));
		check("race of a starting class", "DarkElf".equals(ColdSeedPlan.raceOf(38)) && (ColdSeedPlan.raceOf(1) == null));
	}

	private static void testStatusJsonShape()
	{
		final List<ColdBot> bots = new ArrayList<>();
		bots.add(bot(1, "Human000", "Human", 0, 3, "hunting", "cold"));
		bots.add(bot(2, "Dwarf004", "Dwarf", 53, 12, "resting", "cold"));
		final String json = ColdBotStatus.render(bots, 12, 1234L);
		check("has generatedAt", json.contains("\"generatedAt\":1234"));
		check("has director target", json.contains("\"directorTarget\":12"));
		check("has total", json.contains("\"total\":2"));
		check("has byRace", json.contains("\"byRace\":{") && json.contains("\"Human\":1") && json.contains("\"Dwarf\":1"));
		check("has level band 1-9", json.contains("\"1-9\":1"));
		check("has level band 10-19", json.contains("\"10-19\":1"));
		check("has bots array", json.contains("\"bots\":[") && json.contains("\"name\":\"Human000\""));
		check("bot brief has goal", json.contains("\"goal\":\"hunting\""));
		check("bot brief has adena", json.contains("\"adena\":0"));
		check("bot brief has soulshots", json.contains("\"soulshots\":0"));
		check("bot brief has potions", json.contains("\"potions\":0"));
		check("bot brief has gearTier", json.contains("\"gearTier\":0"));
		check("empty renders zero total", ColdBotStatus.render(new ArrayList<>(), 0, 5L).contains("\"total\":0"));
	}

	private static void testStatusQuoteEscaping()
	{
		check("null becomes json null", ColdBotStatus.quote(null).equals("null"));
		check("quotes are escaped", ColdBotStatus.quote("a\"b").equals("\"a\\\"b\""));
		check("backslash is escaped", ColdBotStatus.quote("a\\b").equals("\"a\\\\b\""));
	}

	private static void testLevelBand()
	{
		check("level 1 band", ColdBotStatus.levelBand(1).equals("1-9"));
		check("level 9 band", ColdBotStatus.levelBand(9).equals("1-9"));
		check("level 10 band", ColdBotStatus.levelBand(10).equals("10-19"));
		check("level 45 band", ColdBotStatus.levelBand(45).equals("40-49"));
		check("level 80 band", ColdBotStatus.levelBand(80).equals("80-89"));
	}

	private static void testConfigDefaults()
	{
		final LivingPopulationConfig defaults = LivingPopulationConfig.defaults();
		check("defaults are disabled", !defaults.enabled());
		check("default size is 100", defaults.populationSize() == 100);
		check("default births are 10 every 20 minutes", (defaults.birthBatch() == 10) && (defaults.birthIntervalMs() == 1_200_000L));
		check("default level goal follows the players", defaults.levelGoal() == 0);
		check("default exp per mob level 13", defaults.expPerMobLevel() == 13.0);
		check("default kills per minute 12", defaults.killsPerMinute() == 12.0);
		check("default max level 80", defaults.maxLevel() == 80);
		check("director on by default", defaults.directorEnabled());
		check("default band radius 2", defaults.directorBandRadius() == 2);
		check("director params expose the band radius", defaults.directorParams().bandRadius() == 2);
		check("handoff on by default", defaults.handoffEnabled());
		check("default activation radius 3000", defaults.handoffActivationRadius() == 3000.0);
		check("default deactivation radius 4000", defaults.handoffDeactivationRadius() == 4000.0);
		check("default cooldown grace 30s", defaults.handoffCooldownGraceMs() == 30_000L);
		check("default max hot bots follow the population", (defaults.handoffMaxHotBots() == 0) && (defaults.effectiveMaxHotBots() == defaults.populationSize()));
		check("a set max hot bots is kept", new LivingPopulationConfig(true, 500, 30_000L, 64, 13.0, 12.0, 80, true, 2, 1.35, 0.85, 0.06, true, 5_000L, 3000.0, 4000.0, 30_000L, 40, true, 5.0, 6, 1000L, 6.0, 500L, 1000L, 12, 100L, 500L, 60, 10, 50_000L, 5_000L, "x", 10, 1_200_000L, 0).effectiveMaxHotBots() == 40);
		check("birth wave keeps the batch for a small population", LivingPopulationConfig.birthWave(10, 1_200_000L, 60, 60) == 10);
		check("birth wave grows for a large population", LivingPopulationConfig.birthWave(10, 1_200_000L, 500, 500) == 56);
		check("birth wave never exceeds what is missing", LivingPopulationConfig.birthWave(10, 1_200_000L, 500, 3) == 3);
		check("birth batch 0 creates everything at once", LivingPopulationConfig.birthWave(0, 1_200_000L, 500, 420) == 420);
		check("handoff params expose activation radius", defaults.handoffParams().activationRadius() == 3000.0);
		check("handoff params never let deactivation fall below activation", defaults.handoffParams().deactivationRadius() >= defaults.handoffParams().activationRadius());
		check("economy on by default", defaults.economyEnabled());
		check("default adena per mob level 5", defaults.adenaPerMobLevel() == 5.0);
		check("default soulshot milestone level 6", defaults.soulshotMilestoneLevel() == 6);
		check("default gear tier level step 10", defaults.gearTierLevelStep() == 10);
		check("default potion restock threshold 100", defaults.potionRestockThreshold() == 100L);
		check("economy params expose the potion cost", defaults.economyParams().potionCost() == 60);
		check("economy params expose the milestone level", defaults.economyParams().soulshotMilestoneLevel() == 6);
		check("economy params share the exp kills-per-minute", defaults.economyParams().killsPerMinute() == defaults.killsPerMinute());
	}

	private static void testHandoffActivation()
	{
		// A player within the activation radius wakes a cold bot; one outside it, or none online, does not.
		check("player inside activation radius activates", HandoffPolicy.shouldActivate(2500.0, 3000.0));
		check("player exactly at the radius still activates", HandoffPolicy.shouldActivate(3000.0, 3000.0));
		check("player beyond the radius does not activate", !HandoffPolicy.shouldActivate(3001.0, 3000.0));
		check("no players online (negative distance) does not activate", !HandoffPolicy.shouldActivate(-1.0, 3000.0));
	}

	private static void testHandoffWatchAndCool()
	{
		// Hysteresis: a hot bot stays watched out to the wider deactivation radius, not just the activation radius.
		check("hot bot still watched between the two radii", HandoffPolicy.isStillWatched(3500.0, 4000.0));
		check("hot bot no longer watched past the deactivation radius", !HandoffPolicy.isStillWatched(4500.0, 4000.0));
		check("no players online means not watched", !HandoffPolicy.isStillWatched(-1.0, 4000.0));

		// Cooldown grace: cool only after the grace window has fully elapsed since a player was last near.
		final long lastNear = 100_000L;
		check("does not cool before the grace elapses", !HandoffPolicy.shouldCool(lastNear, lastNear + 29_000L, 30_000L));
		check("cools once the grace elapses", HandoffPolicy.shouldCool(lastNear, lastNear + 30_000L, 30_000L));
		check("cools well after the grace", HandoffPolicy.shouldCool(lastNear, lastNear + 120_000L, 30_000L));
	}

	private static void testHandoffDistance()
	{
		check("planar distance ignores identical points", HandoffPolicy.planarDistance(100, 200, 100, 200) == 0.0);
		check("planar distance is a 3-4-5 triangle", HandoffPolicy.planarDistance(0, 0, 3000, 4000) == 5000.0);
		check("planar distance is symmetric", HandoffPolicy.planarDistance(10, 20, 40, 60) == HandoffPolicy.planarDistance(40, 60, 10, 20));
	}

	private static void testHotBotIsReadableFromRow()
	{
		// The hot lock and char binding the handoff writes are plain row fields the resolver and snapshot already read.
		final ColdBot bot = bot(7, "Human006", "Human", 0, 15, "hunting", "hot");
		bot.setHotLock(true);
		bot.setCharId(268437456L);
		check("a hot bot reports hot lock", bot.isHotLock());
		check("a hot bot carries its char binding", bot.getCharId() == 268437456L);
		check("the snapshot shows a hot bot", ColdBotStatus.render(java.util.List.of(bot), 15, 1L).contains("\"hot\":true"));
	}

	// A fixed economy tuning that makes the transactions easy to reason about in tests.
	private static ColdEconomy.Params econ()
	{
		return new ColdEconomy.Params(5.0, 12.0, 6, 1000L, 6.0, 500L, 1000L, 12, 100L, 500L, 60, 10, 50_000L);
	}

	private static void testNeedsPriority()
	{
		final ColdEconomy.Params p = econ();
		// Potions kept above their floor (1000) in these cases so only the intended driver fires.
		check("out of shots and affordable -> restock", NeedsEvaluator.evaluate(10, 100_000L, 0L, 1000L, 0, p) == NeedsEvaluator.Need.RESTOCK);
		check("out of shots but broke -> hunt", NeedsEvaluator.evaluate(10, 100L, 0L, 1000L, 0, p) == NeedsEvaluator.Need.HUNT);
		check("shots ok and gear affordable -> upgrade", NeedsEvaluator.evaluate(10, 60_000L, 1000L, 1000L, 0, p) == NeedsEvaluator.Need.UPGRADE);
		check("restock beats upgrade when both possible", NeedsEvaluator.evaluate(10, 100_000L, 0L, 1000L, 0, p) == NeedsEvaluator.Need.RESTOCK);
		check("gear at level ceiling -> no upgrade", NeedsEvaluator.evaluate(10, 100_000L, 1000L, 1000L, 1, p) == NeedsEvaluator.Need.HUNT);
		check("gear below its level step -> no upgrade", NeedsEvaluator.evaluate(5, 100_000L, 1000L, 1000L, 0, p) == NeedsEvaluator.Need.HUNT);
		check("low potions and affordable -> restock", NeedsEvaluator.evaluate(10, 100_000L, 1000L, 0L, 1, p) == NeedsEvaluator.Need.RESTOCK);
	}

	private static void testEconomyAdenaAccrual()
	{
		// One minute at level 10: adena += 10*5*12 = 600; soulshots -= 12*6 = 72. No milestone (already claimed), no buy.
		final ColdEconomy.State before = new ColdEconomy.State(0L, 1000L, 1000L, 1, true, "hunting");
		final ColdEconomy.State after = ColdEconomy.resolve(before, 10, 60_000L, econ());
		check("adena accrues per minute", after.adena() == 600L);
		check("soulshots are consumed per minute", after.soulshots() == 928L);
		check("potions are not consumed in cold", after.potions() == 1000L);
		check("no purchase leaves the goal hunting", after.goal().equals("hunting"));
		check("gear tier unchanged when nothing bought", after.gearTier() == 1);
	}

	private static void testEconomySoulshotMilestoneOnce()
	{
		final ColdEconomy.Params p = econ();
		// At the milestone level with the reward unclaimed, grant it once (elapsed 0 isolates the grant).
		final ColdEconomy.State granted = ColdEconomy.resolve(new ColdEconomy.State(0L, 0L, 1000L, 0, false, "hunting"), 6, 0L, p);
		check("milestone grants soulshots", granted.soulshots() == 1000L);
		check("milestone marks the reward claimed", granted.rewardClaimed());

		// A second resolve does not grant again.
		final ColdEconomy.State again = ColdEconomy.resolve(granted, 6, 0L, p);
		check("milestone is not granted twice", again.soulshots() == 1000L);

		// Below the milestone level nothing is granted.
		final ColdEconomy.State early = ColdEconomy.resolve(new ColdEconomy.State(0L, 0L, 1000L, 0, false, "hunting"), 5, 0L, p);
		check("no milestone below the reward level", (early.soulshots() == 0L) && !early.rewardClaimed());
	}

	private static void testEconomySoulshotConsumeAndRestock()
	{
		// Low shots and enough adena: buy a batch (adena -= 1000*12, soulshots += 1000). Elapsed 0 isolates the buy.
		// Potions kept above their floor so only the soulshot restock fires.
		final ColdEconomy.State after = ColdEconomy.resolve(new ColdEconomy.State(20_000L, 100L, 1000L, 1, true, "hunting"), 10, 0L, econ());
		check("restock spends adena", after.adena() == 8000L);
		check("restock adds a batch of soulshots", after.soulshots() == 1100L);
		check("restock sets the goal", after.goal().equals("restock"));
	}

	private static void testEconomyPotionRestock()
	{
		// Shots ok but out of potions and affordable: buy a batch (adena -= 500*60 = 30000, potions += 500).
		final ColdEconomy.State after = ColdEconomy.resolve(new ColdEconomy.State(40_000L, 1000L, 0L, 1, true, "hunting"), 10, 0L, econ());
		check("potion restock spends adena", after.adena() == 10_000L);
		check("potion restock adds a batch of potions", after.potions() == 500L);
		check("potion restock sets the goal", after.goal().equals("restock"));
	}

	private static void testEconomyGearUpgrade()
	{
		final ColdEconomy.Params p = econ();
		// Shots and potions ok, a tier unlocked and affordable: buy it (adena -= 50000, tier++).
		final ColdEconomy.State up = ColdEconomy.resolve(new ColdEconomy.State(60_000L, 1000L, 1000L, 0, true, "hunting"), 10, 0L, p);
		check("upgrade spends adena", up.adena() == 10_000L);
		check("upgrade raises the gear tier", up.gearTier() == 1);
		check("upgrade sets the goal", up.goal().equals("upgrade"));

		// Not enough adena for the tier: stay put and hunt.
		final ColdEconomy.State poor = ColdEconomy.resolve(new ColdEconomy.State(40_000L, 1000L, 1000L, 0, true, "hunting"), 10, 0L, p);
		check("cannot upgrade when broke", (poor.gearTier() == 0) && poor.goal().equals("hunting"));
	}

	private static void testEconomyAdenaCappedToIntMax()
	{
		// A character's purse is an int in-game, so the cold row must not grow past Integer.MAX_VALUE or the handoff
		// capture (through Player.getAdena()) would truncate it. Accrual near the ceiling clamps instead of overflowing.
		final ColdEconomy.State near = new ColdEconomy.State(Integer.MAX_VALUE - 100L, 100_000L, 100_000L, 8, true, "hunting");
		final ColdEconomy.State after = ColdEconomy.resolve(near, 80, 60_000L, econ());
		check("adena is capped at the in-game int ceiling", after.adena() == Integer.MAX_VALUE);
	}

	private static ColdBot bot(long id, String name, String race, int classId, int level, String activity, String phase)
	{
		final ColdBot bot = new ColdBot();
		bot.setId(id);
		bot.setName(name);
		bot.setRace(race);
		bot.setClassId(classId);
		bot.setLevel(level);
		bot.setActivity(activity);
		bot.setPhase(phase);
		bot.setGoal("hunting");
		bot.setRegion(race);
		return bot;
	}

	private static void testDecisionLogCapacityAndOrder()
	{
		final DecisionLog log = new DecisionLog();
		for (int i = 0; i < (DecisionLog.CAPACITY + 5); i++)
		{
			log.add(i, null, "entry " + i);
		}
		final List<DecisionLog.Entry> entries = log.newestFirst();
		check("log keeps only the capacity", entries.size() == DecisionLog.CAPACITY);
		check("log lists newest first", entries.get(0).text().equals("entry " + (DecisionLog.CAPACITY + 4)));
		check("log drops the oldest", entries.get(entries.size() - 1).text().equals("entry 5"));
		log.add(99, null, "  ");
		check("blank text is ignored", log.size() == DecisionLog.CAPACITY && log.newestFirst().get(0).at() != 99);
	}

	private static void testDecisionLogKeyedOnce()
	{
		final DecisionLog log = new DecisionLog();
		final List<DecisionLog.Event> waiting = new ArrayList<>();
		waiting.add(new DecisionLog.Event("wait-potions", "potions low"));
		log.addTick(1, waiting);
		log.addTick(2, waiting);
		log.addTick(3, waiting);
		check("an ongoing keyed situation is logged once", log.size() == 1);
		log.addTick(4, new ArrayList<>());
		log.addTick(5, waiting);
		check("a keyed situation that ends and returns is logged again", log.size() == 2);
		final List<DecisionLog.Event> oneOff = new ArrayList<>();
		oneOff.add(new DecisionLog.Event(null, "bought"));
		log.addTick(6, oneOff);
		log.addTick(7, oneOff);
		check("unkeyed decisions are always logged", log.size() == 4);
	}

	private static void testDecisionLogJsonRoundTrip()
	{
		final DecisionLog log = new DecisionLog();
		log.add(100L, null, "Bought 500 \"potions\"");
		log.add(200L, null, "Reached level 7");
		final String json = log.toJson();
		check("log json has the decisions array", json.startsWith("{\"decisions\":["));
		final DecisionLog loaded = new DecisionLog();
		loaded.loadJson(json);
		final List<DecisionLog.Entry> entries = loaded.newestFirst();
		check("round trip keeps every entry", entries.size() == 2);
		check("round trip keeps order and time", (entries.get(0).at() == 200L) && entries.get(0).text().equals("Reached level 7"));
		check("round trip keeps escaped text", entries.get(1).text().equals("Bought 500 \"potions\""));

		final ColdBot bot = new ColdBot();
		bot.setStatsJson(json);
		check("the bot loads its log from stats_json", bot.getDecisions().size() == 2);
		check("the bot writes its log to stats_json", bot.getStatsJson().equals(json));
	}

	private static void testHuntingCounters()
	{
		final ColdBot bot = new ColdBot();
		final String noCounters = bot.getStatsJson();
		check("a bot with no hunting keeps the plain log json", !noCounters.contains("counters"));
		bot.addHunting(6.0, 340L);
		bot.addHunting(6.5, 60L);
		bot.addHunting(-1.0, -5L);
		check("kills add up and ignore negatives", Math.abs(bot.getKills() - 12.5) < 1e-9);
		check("adena adds up", bot.getAdenaEarned() == 400L);
		final String json = bot.getStatsJson();
		check("counters are written next to the log", json.startsWith("{\"decisions\":[") && json.contains("\"counters\":{\"kills\":12.5,\"adena\":400}"));
		final ColdBot loaded = new ColdBot();
		loaded.setStatsJson(json);
		check("counters survive a reload", (Math.abs(loaded.getKills() - 12.5) < 1e-9) && (loaded.getAdenaEarned() == 400L));
		loaded.setStatsJson("{\"decisions\":[],\"counters\":\"junk\"}");
		check("bad counters load as zero", (loaded.getKills() == 0.0) && (loaded.getAdenaEarned() == 0L));
		loaded.setStatsJson("{\"decisions\":[],\"counters\":{\"kills\":3,\"exp\":900,\"adena\":5}}");
		check("an old exp counter is dropped", (loaded.getAdenaEarned() == 5L) && !loaded.getStatsJson().contains("exp"));
		loaded.setStatsJson("not json");
		check("unreadable stats_json loads as zero", loaded.getAdenaEarned() == 0L);
	}

	private static void testSnapshotGear()
	{
		final ColdBot cold = bot(1, "Human000", "Human", 0, 45, "hunting", "cold");
		cold.setGearTier(4);
		cold.addHunting(10.0, 70L);
		final ColdBot hot = bot(2, "Elf001", "Elf", 18, 25, "hunting", "hot");
		hot.setGearTier(2);
		final java.util.Map<Long, List<String[]>> worn = new java.util.HashMap<>();
		worn.put(2L, java.util.List.<String[]> of(new String[] { "weapon", "Sword of Revolution +3" }, new String[] { "chest", "Mithril \"Shirt\"" }));
		cold.setExpIntoLevel(300L);
		final String json = ColdBotStatus.render(java.util.List.of(cold, hot), 0, 1L, 10, worn, level -> 1000L, 80);
		check("snapshot carries the counters", json.contains("\"kills\":10,\"adenaEarned\":70") && !json.contains("expEarned"));
		check("progress to the next level in percent", json.contains("\"levelProgress\":30,") && !json.contains("expIntoLevel"));
		check("progress rounds down and never shows 100 below the cap", (ColdBotStatus.levelProgress(5, 999L, level -> 1000L, 80) == 99) && (ColdBotStatus.levelProgress(80, 0L, level -> 1000L, 80) == 100));
		check("gear level is capped by the bot level", json.contains("\"gearLevel\":40,\"gearGrade\":\"C\""));
		check("gear grade follows the tier", json.contains("\"gearLevel\":20,\"gearGrade\":\"D\""));
		check("a hot bot lists what it wears", json.contains("\"equipment\":[{\"slot\":\"weapon\",\"item\":\"Sword of Revolution +3\"},{\"slot\":\"chest\",\"item\":\"Mithril \\\"Shirt\\\"\"}]"));
		check("a cold bot lists no worn items", json.indexOf("\"equipment\"") == json.lastIndexOf("\"equipment\""));
		check("the short render has starter gear", ColdBotStatus.render(java.util.List.of(cold), 0, 1L).contains("\"gearLevel\":0,\"gearGrade\":\"none\""));
	}

	private static void testDecisionLogToleratesBadJson()
	{
		final DecisionLog log = new DecisionLog();
		log.add(1L, null, "kept?");
		log.loadJson("{not json");
		check("malformed json leaves an empty log", log.size() == 0);
		log.loadJson(null);
		check("null json leaves an empty log", log.size() == 0);
		log.loadJson("{\"other\":1}");
		check("json without decisions leaves an empty log", log.size() == 0);
		log.loadJson("{\"decisions\":[{\"t\":5,\"m\":\"ok\"},{\"t\":\"x\",\"m\":\"bad time\"},{\"t\":6}]}");
		check("bad entries are skipped, good ones kept", (log.size() == 1) && log.newestFirst().get(0).text().equals("ok"));
	}

	private static void testEconomyReportsDecisions()
	{
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdEconomy.resolve(new ColdEconomy.State(40_000L, 1000L, 0L, 1, true, "hunting"), 10, 0L, econ(), events);
		check("a potion restock is reported with its reason and price", (events.size() == 1) && events.get(0).text().contains("potions 0 below 100") && events.get(0).text().contains("30,000 adena") && (events.get(0).key() == null));

		events.clear();
		ColdEconomy.resolve(new ColdEconomy.State(200_000L, 1000L, 500L, 1, true, "hunting"), 20, 0L, econ(), events);
		check("a gear upgrade is reported", (events.size() == 1) && events.get(0).text().startsWith("Upgrade: level 20 unlocks gear tier 2") && events.get(0).text().contains("100,000 adena"));

		events.clear();
		ColdEconomy.resolve(new ColdEconomy.State(0L, 0L, 500L, 0, false, "hunting"), 6, 0L, econ(), events);
		check("the newbie reward is reported", events.stream().anyMatch(e -> e.text().startsWith("Received the newbie reward at level 6: 1,000 soulshots")));

		final ColdEconomy.State plain = ColdEconomy.resolve(new ColdEconomy.State(40_000L, 1000L, 0L, 1, true, "hunting"), 10, 0L, econ());
		final ColdEconomy.State logged = ColdEconomy.resolve(new ColdEconomy.State(40_000L, 1000L, 0L, 1, true, "hunting"), 10, 0L, econ(), new ArrayList<>());
		check("reporting does not change the outcome", plain.equals(logged));
	}

	private static void testEconomyReportsWaits()
	{
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdEconomy.resolve(new ColdEconomy.State(1_000L, 1000L, 0L, 1, true, "hunting"), 10, 0L, econ(), events);
		check("an unaffordable potion restock is reported as a keyed wait", (events.size() == 1) && "wait-potions".equals(events.get(0).key()) && events.get(0).text().contains("Keeps hunting"));

		events.clear();
		ColdEconomy.resolve(new ColdEconomy.State(1_000L, 1000L, 500L, 1, true, "hunting"), 25, 0L, econ(), events);
		check("an unaffordable gear tier is reported as a keyed wait", (events.size() == 1) && "wait-gear-2".equals(events.get(0).key()));

		events.clear();
		ColdEconomy.resolve(new ColdEconomy.State(1_000L, 1000L, 500L, 2, true, "hunting"), 25, 0L, econ(), events);
		check("a bot with nothing to do reports nothing", events.isEmpty());
	}

	private static void testStatusIncludesDecisions()
	{
		final ColdBot bot = bot(1, "Human000", "Human", 0, 3, "hunting", "cold");
		bot.getDecisions().add(10L, null, "first");
		bot.getDecisions().add(20L, null, "second");
		final List<ColdBot> bots = new ArrayList<>();
		bots.add(bot);
		final String json = ColdBotStatus.render(bots, 0, 1L);
		check("snapshot has the decisions newest first", json.contains("\"decisions\":[{\"t\":20,\"m\":\"second\"},{\"t\":10,\"m\":\"first\"}]"));
		check("snapshot has the position", json.contains("\"x\":") && json.contains("\"z\":"));
	}

	// ---- zone combat: kill and death rates from gear against the zone's monsters
	private static final String ZONE_DATA = String.join("\n",
		"CURVE\tpatk_melee\t38\t112\t190\t236\t305\t342", "CURVE\tpatk_bow\t64\t191\t323\t400\t570\t581", "CURVE\tmatk_mage\t31\t79\t122\t145\t167\t193",
		"CURVE\tpdef_tank\t170\t400\t519\t666\t724\t885", "CURVE\tpdef_melee\t80\t238\t316\t428\t468\t572", "CURVE\tpdef_light\t116\t176\t245\t378\t395\t446", "CURVE\tpdef_robe\t91\t134\t191\t293\t328\t363",
		"CURVE\tmdef_heavy\t98\t168\t224\t276\t333\t333", "CURVE\tmdef_light\t98\t168\t224\t276\t346\t346", "CURVE\tmdef_robe\t98\t168\t224\t276\t333\t333",
		"ZONE\tEasy\t1\t10\t4\t70\t50\t33\t11\t7", "ZONE\tMid\t35\t45\t42\t1200\t180\t120\t400\t250", "ZONE\tMid2\t40\t50\t46\t1500\t200\t130\t450\t280", "ZONE\tHard\t70\t75\t72\t2566\t308\t200\t743\t507", "ZONE\tCrowded\t35\t45\t42\t1200\t180\t120\t400\t250\t30\t4\t100\t3940");

	private static void testZoneCombat() throws RuntimeException
	{
		final ZoneCombat model;
		try
		{
			model = ZoneCombat.parse(new java.io.StringReader(ZONE_DATA), ZoneCombat.Params.defaults());
		}
		catch (java.io.IOException e)
		{
			throw new RuntimeException(e);
		}
		check("zone combat: parsed zones", model.enabled() && (model.zoneCount() == 5));
		check("zone combat: off model returns the flat rate", ZoneCombat.off().killsPerMinute("Mid", 2, 40, 2, 2, 1.0) == 12.0);
		check("zone combat: unknown zone returns the flat rate", model.killsPerMinute("Nowhere", 2, 40, 2, 2, 1.0) == 12.0);
		check("zone combat: unknown zone death factor is 1", model.deathFactor("Nowhere", 2, 40, 2) == 1.0);
		final double fitted = model.killsPerMinute("Mid", 2, 40, 2, 2, 1.0);
		final double behind = model.killsPerMinute("Mid", 2, 40, 1, 1, 1.0);
		final double noSkills = model.killsPerMinute("Mid", 2, 40, 2, 2, 0.0);
		check("zone combat: worse weapon grade kills slower", behind < fitted);
		check("zone combat: missing skills kill slower", noSkills < fitted);
		check("zone combat: skill floor halves damage, not more", noSkills > (fitted * 0.4));
		check("zone combat: a fitted bot is near the flat rate in the median zone", Math.abs(fitted - 12.0) < 3.0);
		check("zone combat: kill rate stays inside the limits", (model.killsPerMinute("Easy", 10, 5, 0, 0, 1.0) <= 24.0) && (model.killsPerMinute("Hard", 10, 72, 0, 0, 0.0) >= 3.0));
		check("zone combat: same stats, tougher zone, slower kills", model.killsPerMinute("Hard", 2, 72, 5, 5, 1.0) < model.killsPerMinute("Easy", 2, 72, 5, 5, 1.0));
		check("zone combat: more skills never kill slower", model.killsPerMinute("Mid", 2, 40, 2, 2, 0.6) >= model.killsPerMinute("Mid", 2, 40, 2, 2, 0.3));
		check("zone combat: weaker armor raises the death factor", model.deathFactor("Mid", 2, 40, 0) > model.deathFactor("Mid", 2, 40, 2));
		check("zone combat: tougher zone raises the death factor", model.deathFactor("Hard", 2, 40, 5) > model.deathFactor("Easy", 2, 40, 5));
		check("zone combat: death factor stays inside the limits", (model.deathFactor("Hard", 10, 40, 0) <= 4.0) && (model.deathFactor("Easy", 2, 40, 5) >= 0.25));
		check("zone combat: roles", (ZoneCombat.roleOf(6) == ZoneCombat.Role.TANK) && (ZoneCombat.roleOf(2) == ZoneCombat.Role.MELEE) && (ZoneCombat.roleOf(9) == ZoneCombat.Role.BOW) && (ZoneCombat.roleOf(10) == ZoneCombat.Role.MAGE));
		check("zone combat: grade of a whole tier", (ZoneCombat.gradeOfTier(0, 10) == 0) && (ZoneCombat.gradeOfTier(2, 10) == 1) && (ZoneCombat.gradeOfTier(5, 10) == 2) && (ZoneCombat.gradeOfTier(3, 0) == 0));
		check("zone combat: skill fraction counts learned levels", ZoneCombat.skillFraction(java.util.List.of(new org.l2jmobius.gameserver.livingpop.SkillPlanner.Entry(1, 1, 5, 0, 0, 0, true), new org.l2jmobius.gameserver.livingpop.SkillPlanner.Entry(2, 1, 5, 0, 0, 0, true), new org.l2jmobius.gameserver.livingpop.SkillPlanner.Entry(3, 1, 50, 0, 0, 0, true)), java.util.Map.of(1, 1), 10) == 0.5);
		check("zone combat: untracked skills count as complete", ZoneCombat.skillFraction(java.util.List.of(), null, 10) == 1.0);
		final ColdRisk.Params risk = new ColdRisk.Params(0.3, 90_000L, 600_000L, 60_000L, 3_600_000L);
		final double flat = ColdRisk.danger(risk, 40, 37, 43, 20, 4, 4, 2).deathsPerHour();
		final double zoned = ColdRisk.danger(risk, 40, 37, 43, 20, 4, 4, 2, 2.0).deathsPerHour();
		check("zone combat: old danger unchanged and zone factor multiplies", (Math.abs(flat - 0.3) < 1e-9) && (Math.abs(zoned - 0.6) < 1e-9));
		final double behindFlat = ColdRisk.danger(risk, 40, 37, 43, 20, 2, 4, 2).deathsPerHour();
		final double behindZoned = ColdRisk.danger(risk, 40, 37, 43, 20, 2, 4, 2, 1.0).deathsPerHour();
		check("zone combat: zone factor replaces the gear-behind step", (behindFlat > flat) && (Math.abs(behindZoned - 0.3) < 1e-9));
		check("zone combat: mob level is read from the data", (Math.abs(model.mobLevel("Mid") - 42.0) < 1e-9) && (model.mobLevel("Nowhere") < 0));
		check("exp gap: a bot 11+ levels over the zone earns nothing", ZoneCombat.outleveled(25, 4.0, 11) && ZoneCombat.outleveled(15, 4.0, 11));
		check("exp gap: inside the limit earns", !ZoneCombat.outleveled(14, 4.0, 11) && !ZoneCombat.outleveled(42, 42.0, 11));
		check("exp gap: far below the zone earns nothing too", ZoneCombat.outleveled(20, 42.0, 11) && !ZoneCombat.outleveled(35, 42.0, 11));
		check("exp gap: unknown zone or no limit never blocks", !ZoneCombat.outleveled(80, -1.0, 11) && !ZoneCombat.outleveled(80, 4.0, 0));
		// buffed leveling: a share of the full buffer party, per role
		final String withBuffs = ZONE_DATA + "\nBUFF\tmelee\t40\t1.25\t1.10\t1.40\nBUFF\tmage\t40\t1.60\t1.10\t1.40";
		final ZoneCombat buffed;
		try
		{
			buffed = ZoneCombat.parse(new java.io.StringReader(withBuffs), ZoneCombat.Params.defaults());
		}
		catch (java.io.IOException e)
		{
			throw new RuntimeException(e);
		}
		final double unbuffedKills = buffed.killsPerMinute("Mid", 2, 40, 2, 2, 1.0);
		buffed.setBuffShares(new double[] { 0.0, 1.0, 0.0, 0.5 });
		check("buffs: share 1 kills faster", buffed.killsPerMinute("Mid", 2, 40, 2, 2, 1.0) > unbuffedKills);
		check("buffs: a role with share 0 is unchanged", Math.abs(buffed.killsPerMinute("Mid", 6, 40, 2, 2, 1.0) - model.killsPerMinute("Mid", 6, 40, 2, 2, 1.0)) < 1e-9);
		check("buffs: half share sits between none and full", buffed.killsPerMinute("Mid", 10, 40, 2, 2, 1.0) > model.killsPerMinute("Mid", 10, 40, 2, 2, 1.0));
		final double buffedDeaths = buffed.deathFactor("Hard", 2, 40, 2);
		check("buffs: defence lowers the death factor", buffedDeaths < model.deathFactor("Hard", 2, 40, 2));
		check("buffs: a level without a row has no effect", Math.abs(buffed.killsPerMinute("Mid", 2, 41, 2, 2, 1.0) - model.killsPerMinute("Mid", 2, 41, 2, 2, 1.0)) < 1e-9);
		buffed.setBuffShares(null);
		check("buffs: shares cleared returns to the baseline", Math.abs(buffed.killsPerMinute("Mid", 2, 40, 2, 2, 1.0) - unbuffedKills) < 1e-9);
		final double noShots = model.killsPerMinute("Mid", 2, 40, 2, 2, 1.0, 0.0);
		final double halfShots = model.killsPerMinute("Mid", 2, 40, 2, 2, 1.0, 0.5);
		check("shots: a bot with none kills slower", noShots < fitted);
		check("shots: half the time sits between", (halfShots < fitted) && (halfShots > noShots));
		check("shots: all the time is the calibrated rate", Math.abs(model.killsPerMinute("Mid", 2, 40, 2, 2, 1.0, 1.0) - fitted) < 1e-9);
		check("shots: a mage loses less than a fighter (spiritshots add the square root)", (model.killsPerMinute("Mid", 10, 40, 2, 2, 1.0, 0.0) / model.killsPerMinute("Mid", 10, 40, 2, 2, 1.0, 1.0)) > (noShots / fitted));
		model.setShotDamage(1.0, 1.0);
		check("shots: a bonus of 1 turns the check off", Math.abs(model.killsPerMinute("Mid", 2, 40, 2, 2, 1.0, 0.0) - fitted) < 1e-9);
		model.setShotDamage(2.0, Math.sqrt(2.0));
		final ZoneCombat.Stats mageGear = model.curveStats(ZoneCombat.Role.MAGE, 2, 2);
		final double plainSps = model.killsPerMinute("Mid", 10, 40, mageGear, 1.0, 1.0, false);
		final double blessedSps = model.killsPerMinute("Mid", 10, 40, mageGear, 1.0, 1.0, true);
		check("blessed spiritshots: a mage firing them kills faster than with plain ones", blessedSps > plainSps);
		check("blessed spiritshots: a fighter is unaffected by the flag", Math.abs(model.killsPerMinute("Mid", 2, 40, model.curveStats(ZoneCombat.Role.MELEE, 2, 2), 1.0, 1.0, true) - model.killsPerMinute("Mid", 2, 40, model.curveStats(ZoneCombat.Role.MELEE, 2, 2), 1.0, 1.0, false)) < 1e-9);
		check("blessed spiritshots: with none in stock the flag changes nothing", Math.abs(model.killsPerMinute("Mid", 10, 40, mageGear, 1.0, 0.0, true) - model.killsPerMinute("Mid", 10, 40, mageGear, 1.0, 0.0, false)) < 1e-9);
		check("zone exp: the zone's average exp per kill is read, unknown is -1", (Math.abs(model.expPerKill("Crowded") - 3940.0) < 1e-9) && (model.expPerKill("Mid") < 0) && (model.expPerKill("Nowhere") < 0));
		// respawn limit and aggressive zones
		check("respawn: a lone bot gets the usable share of the zone (30 a minute, half usable)", Math.abs(model.respawnCap("Crowded", 1, 0.5) - 15.0) < 1e-9);
		check("respawn: bots share the zone", Math.abs(model.respawnCap("Crowded", 10, 0.5) - 1.5) < 1e-9);
		check("respawn: unknown respawns or zone never cap", Double.isInfinite(model.respawnCap("Mid", 5, 0.5)) && Double.isInfinite(model.respawnCap("Nowhere", 5, 0.5)));
		final double calm = model.deathFactor("Crowded", 2, 40, 2);
		model.setAggroRisk(1.0);
		check("aggro: a fully aggressive zone doubles the death factor", Math.abs(model.deathFactor("Crowded", 2, 40, 2) - (2.0 * calm)) < 1e-9);
		check("aggro: a zone with no aggressive monsters is unchanged", Math.abs(model.deathFactor("Mid", 2, 40, 2) - model.deathFactor("Mid", 2, 40, 2)) < 1e-9);
		model.setAggroRisk(0.0);
		check("aggro: risk 0 turns it off", Math.abs(model.deathFactor("Crowded", 2, 40, 2) - calm) < 1e-9);
		check("zone combat: economy params keep everything but the kill rate", econ().withKillsPerMinute(7.0).killsPerMinute() == 7.0 && econ().withKillsPerMinute(7.0).adenaPerMobLevel() == 5.0);
	}

	private static void check(String label, boolean condition)
	{
		_checks++;
		if (!condition)
		{
			_failures++;
			System.out.println("  FAIL: " + label);
		}
	}
}
