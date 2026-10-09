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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import org.l2jmobius.gameserver.livingpop.ClassPath;
import org.l2jmobius.gameserver.livingpop.ColdBot;
import org.l2jmobius.gameserver.livingpop.ColdLife;
import org.l2jmobius.gameserver.livingpop.ColdRisk;
import org.l2jmobius.gameserver.livingpop.DecisionLog;
import org.l2jmobius.gameserver.livingpop.DropYield;
import org.l2jmobius.gameserver.livingpop.GoalPlanner;
import org.l2jmobius.gameserver.livingpop.LivingChat;
import org.l2jmobius.gameserver.livingpop.LivingGear;
import org.l2jmobius.gameserver.livingpop.LivingRoute;
import org.l2jmobius.gameserver.livingpop.LivingSupplies;
import org.l2jmobius.gameserver.livingpop.SkillPlanner;
import org.l2jmobius.gameserver.livingpop.SupplyPlanner;
import org.l2jmobius.gameserver.livingpop.TravelConfig;
import org.l2jmobius.gameserver.livingpop.TravelLeg;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneChooser;

/**
 * Standalone regression harness for the Living Population Phase 5 travel logic: the zone catalog, supply and goal
 * planning, zone choice, travel legs and a bot's full town trip ({@link ColdLife}). Pure classes only; the hot side is
 * verified by the whole-tree compile. Exit code is 0 when every check passes, 1 otherwise.
 */
public class LivingTravelTest
{
	private static int _checks = 0;
	private static int _failures = 0;

	// Alpha has a grocer, Beta does not. Near Woods is walkable from Alpha, Far Hills needs Alpha's gatekeeper, Beyond is
	// only reachable from Beta (so from Alpha it is a hop through Beta).
	private static final String XML = "<zones>" //
		+ "<town name=\"Alpha\" x=\"0\" y=\"0\" z=\"0\"><gatekeeper npcId=\"1\" x=\"100\" y=\"0\" z=\"0\"/><grocer npcId=\"2\" x=\"0\" y=\"100\" z=\"0\"/><master script=\"ElfHumanFighterChange1\" npcId=\"4\" x=\"0\" y=\"300\" z=\"0\"/><route town=\"Beta\" fee=\"1000\"/></town>" //
		+ "<town name=\"Beta\" x=\"100000\" y=\"0\" z=\"0\"><gatekeeper npcId=\"3\" x=\"100100\" y=\"0\" z=\"0\"/><master script=\"ElfHumanFighterChange2\" npcId=\"5\" x=\"100000\" y=\"300\" z=\"0\"/><route town=\"Alpha\" fee=\"1000\"/></town>" //
		+ "<zone name=\"Newbie Field\" minLevel=\"1\" maxLevel=\"10\" starterRace=\"Human\"><spot x=\"2000\" y=\"0\" z=\"0\"/></zone>" //
		+ "<zone name=\"Near Woods\" minLevel=\"10\" maxLevel=\"20\"><spot x=\"5000\" y=\"0\" z=\"0\"/><monster npcId=\"20120\" count=\"3\"/><monster npcId=\"20121\" count=\"1\"/></zone>" //
		+ "<zone name=\"Far Hills\" minLevel=\"15\" maxLevel=\"25\"><teleport town=\"Alpha\" x=\"50000\" y=\"0\" z=\"0\" fee=\"2000\"/><spot x=\"52000\" y=\"0\" z=\"0\"/></zone>" //
		+ "<zone name=\"Beyond\" minLevel=\"20\" maxLevel=\"30\"><teleport town=\"Beta\" x=\"120000\" y=\"0\" z=\"0\" fee=\"500\"/><spot x=\"121000\" y=\"0\" z=\"0\"/></zone>" //
		+ "<zone name=\"Nowhere\" minLevel=\"1\" maxLevel=\"80\"></zone>" //
		+ "</zones>";

	public static void main(String[] args) throws Exception
	{
		testCatalogParse();
		testSupplies();
		testSupplyReserveAndShop();
		testGoalPlanner();
		testRolesAndPrices();
		testZoneChooser();
		testTravelLeg();
		testTownTripWithScroll();
		testWalkWithoutScroll();
		testAfkAndStuck();
		testErrandPosition();
		testStarterZone();
		testDropYield();
		testLootSale();
		testColdRisk();
		testColdDeathAndRest();
		testLivingChat();
		testClassPath();
		testClassChange();
		testSkillPlanner();
		testTrainerVisit();
		testGearRules();
		testGearShopping();
		testGearDrops();
		testGearShopSafety();
		testGearTripBudget();
		testDecisionGuardrails();
		testGearGuardrails();
		testIslands();
		testRouteOverBridge();

		System.out.println("LivingTravelTest: " + (_checks - _failures) + "/" + _checks + " checks passed.");
		if (_failures > 0)
		{
			System.exit(1);
		}
	}

	private static ZoneCatalog catalog() throws Exception
	{
		return ZoneCatalog.parse(XML);
	}

	private static SupplyPlanner.Params supply()
	{
		// 10 potions, restock at a quarter (2); 30 minutes of shots at 12 kills x 6 shots; 2 scrolls; reserve max(500, 250/level, 10%).
		return new SupplyPlanner.Params(10, 0.25, 30, 0.2, 2, 500L, 250L, 0.10, 12.0, 6.0, 50_000L, 10, 200L);
	}

	private static SupplyPlanner.Prices prices()
	{
		return new SupplyPlanner.Prices(90L, 7L, 400L, 0L);
	}

	private static ColdLife.Context context(int afkChance) throws Exception
	{
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, afkChance, 60_000L, 60_000L, 4.0, 8, 300_000L, 2500.0);
		return new ColdLife.Context(catalog(), supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7));
	}

	private static ColdBot bot(int level, String zone, long adena, long potions, long escapes, int x)
	{
		final ColdBot bot = new ColdBot();
		bot.setId(1);
		bot.setName("Tester");
		bot.setRace("Elf");
		bot.setLevel(level);
		bot.setActivity(ColdLife.HUNTING);
		bot.setZone(zone);
		bot.setAdena(adena);
		bot.setPotions(potions);
		bot.setEscapes(escapes);
		bot.setGearTier(level / 10);
		bot.setRewardClaimed(true);
		bot.setSoulshots(5000);
		bot.setX(x);
		return bot;
	}

	private static void testCatalogParse() throws Exception
	{
		final ZoneCatalog catalog = catalog();
		check("parses both towns", catalog.towns().size() == 2);
		check("drops a zone without spots", (catalog.zones().size() == 4) && (catalog.zone("Nowhere") == null));
		check("town routes and grocer", (catalog.town("Alpha").feeTo("Beta") == 1000L) && catalog.town("Alpha").hasGrocer() && !catalog.town("Beta").hasGrocer());
		check("unknown route is negative", catalog.town("Beta").feeTo("Gamma") < 0);
		check("a zone lists its monsters", catalog.zone("Near Woods").monsters().equals(List.of(new ZoneCatalog.Monster(20120, 3), new ZoneCatalog.Monster(20121, 1))) && catalog.zone("Far Hills").monsters().isEmpty());
		check("a starter zone knows its race", catalog.zone("Newbie Field").isStarter() && "Human".equals(catalog.zone("Newbie Field").starterRace()));
		check("zone fits its range", catalog.zone("Near Woods").fits(10) && catalog.zone("Near Woods").fits(20) && !catalog.zone("Near Woods").fits(21));
		check("teleport lookup by town", (catalog.zone("Far Hills").teleportFrom("Alpha") != null) && (catalog.zone("Far Hills").teleportFrom("Beta") == null));
		check("nearest shopping town skips towns without a grocer", "Alpha".equals(catalog.nearestShoppingTown(new Point(99000, 0, 0)).name()));
		check("catalog with a grocer town is usable", catalog.isUsable() && !ZoneCatalog.empty().isUsable());
		check("zoneAt finds the nearest spot", "Near Woods".equals(catalog.zoneAt(new Point(5100, 0, 0), 1000).name()) && (catalog.zoneAt(new Point(30000, 0, 0), 1000) == null));
	}

	private static void testSupplies()
	{
		check("lesser potions below level 20", LivingSupplies.potionIdFor(19) == 1060);
		check("healing potions from level 20", LivingSupplies.potionIdFor(20) == 1061);
		check("no-grade shots for starter gear", LivingSupplies.soulshotIdFor(0) == 1835);
		check("D-grade shots from gear level 20", LivingSupplies.soulshotIdFor(20) == 1463);
		check("S-grade shots from gear level 76", LivingSupplies.soulshotIdFor(76) == 1467);
		check("the scroll is 736", LivingSupplies.SCROLL_OF_ESCAPE == 736);
	}

	private static void testSupplyReserveAndShop()
	{
		final SupplyPlanner.Params params = supply();
		check("reserve floor", SupplyPlanner.reserve(1, 100L, params) == 500L);
		check("reserve per level", SupplyPlanner.reserve(10, 1000L, params) == 2500L);
		check("reserve fraction", SupplyPlanner.reserve(10, 100_000L, params) == 10_000L);
		check("no soulshots before the newbie reward", SupplyPlanner.soulshotTarget(false, params) == 0L);
		check("soulshot stock is minutes of hunting", SupplyPlanner.soulshotTarget(true, params) == 2160L);

		// Tight budget: 2500 reserve out of 3500 leaves 1000: two scrolls (800) first, then two potions (180).
		final SupplyPlanner.Purchase tight = SupplyPlanner.shop(10, 3500L, 0L, 0L, 5000L, 1, true, prices(), params);
		check("scrolls are bought first", tight.escapes() == 2L);
		check("then as many potions as fit", tight.potions() == 2L);
		check("never spends the reserve", (3500L - tight.cost()) >= 2500L);

		final SupplyPlanner.Purchase rich = SupplyPlanner.shop(20, 500_000L, 2L, 10L, 0L, 1, true, prices(), params);
		check("an unlocked tier is bought when affordable", rich.upgraded());
		check("soulshots are topped up to the stock", rich.soulshots() == 2160L);
		check("nothing needed buys nothing", !SupplyPlanner.shop(10, 500_000L, 2L, 10L, 5000L, 1, true, prices(), params).any());

		// Soulshots come in a batch of at least 200 or not at all (7 adena each, 2500 reserve at level 10).
		check("a nearly full bot does not top up a handful of soulshots", SupplyPlanner.shop(10, 500_000L, 2L, 10L, 2000L, 1, true, prices(), params).soulshots() == 0L);
		check("a bot that can afford only 50 soulshots buys none", !SupplyPlanner.shop(10, 2850L, 2L, 10L, 0L, 1, true, prices(), params).any());
		check("a bot that can afford 200 soulshots buys them", SupplyPlanner.shop(10, 3900L, 2L, 10L, 0L, 1, true, prices(), params).soulshots() == 200L);
		final SupplyPlanner.Params anyAmount = new SupplyPlanner.Params(10, 0.25, 30, 0.2, 2, 500L, 250L, 0.10, 12.0, 6.0, 50_000L, 10, 0L);
		check("no minimum buys any amount", SupplyPlanner.shop(10, 2850L, 2L, 10L, 0L, 1, true, prices(), anyAmount).soulshots() == 50L);
	}

	private static void testGoalPlanner()
	{
		final SupplyPlanner.Params params = supply();
		final GoalPlanner.Plan broke = GoalPlanner.plan(new GoalPlanner.View(10, 600L, 0L, 5000L, 2L, 1, true, "Near Woods", 10, 20, null), prices(), params);
		check("out of potions but broke keeps hunting", broke.chosen().goal() == GoalPlanner.Goal.HUNT);
		check("and says why it put the trip off", GoalPlanner.describe(broke).contains("Put off: Potions low"));

		final GoalPlanner.Plan low = GoalPlanner.plan(new GoalPlanner.View(10, 20_000L, 1L, 5000L, 2L, 1, true, "Near Woods", 10, 20, null), prices(), params);
		check("low potions with money goes to town", (low.chosen().goal() == GoalPlanner.Goal.TOWN) && "potions".equals(low.chosen().key()));

		final GoalPlanner.Plan weak = GoalPlanner.plan(new GoalPlanner.View(12, 20_000L, 0L, 5000L, 2L, 1, true, "Far Hills", 15, 25, "Near Woods"), prices(), params);
		check("too weak for the zone outranks potions", (weak.chosen().goal() == GoalPlanner.Goal.RELOCATE) && (weak.chosen().priority() == 85));

		final GoalPlanner.Plan outleveled = GoalPlanner.plan(new GoalPlanner.View(25, 1_000_000L, 10L, 5000L, 2L, 2, true, "Near Woods", 10, 20, "Far Hills"), prices(), params);
		check("outleveled with a better zone moves on", outleveled.chosen().goal() == GoalPlanner.Goal.RELOCATE);

		final GoalPlanner.Plan slack = GoalPlanner.plan(new GoalPlanner.View(24, 1_000_000L, 10L, 5000L, 2L, 2, true, "Near Woods", 10, 20, "Far Hills"), prices(), params);
		check("up to four levels past the top still fits", slack.chosen().goal() == GoalPlanner.Goal.HUNT);

		// Soulshots low (100 of 2,160): 5,600 adena at level 20 keeps 5,000, so only 85 shots: not worth a trip.
		final GoalPlanner.Plan fewShots = GoalPlanner.plan(new GoalPlanner.View(20, 5_600L, 10L, 100L, 2L, 2, true, "Near Woods", 10, 20, null), prices(), params);
		check("no trip for a handful of soulshots", (fewShots.chosen().goal() == GoalPlanner.Goal.HUNT) && GoalPlanner.describe(fewShots).contains("it cannot afford the smallest batch of 200"));
		final GoalPlanner.Plan manyShots = GoalPlanner.plan(new GoalPlanner.View(20, 20_000L, 10L, 100L, 2L, 2, true, "Near Woods", 10, 20, null), prices(), params);
		check("a trip for a proper batch of soulshots", (manyShots.chosen().goal() == GoalPlanner.Goal.TOWN) && "soulshots".equals(manyShots.chosen().key()));

		// Two potions left of ten and money for only one more: put off.
		final GoalPlanner.Plan fewPotions = GoalPlanner.plan(new GoalPlanner.View(10, 2_600L, 2L, 5000L, 2L, 1, true, "Near Woods", 10, 20, null), prices(), params);
		check("no trip for a single potion", fewPotions.chosen().goal() == GoalPlanner.Goal.HUNT);

		final GoalPlanner.Plan fine = GoalPlanner.plan(new GoalPlanner.View(15, 1_000L, 10L, 5000L, 2L, 1, true, "Near Woods", 10, 20, null), prices(), params);
		check("a stocked bot in a fitting zone hunts", (fine.chosen().goal() == GoalPlanner.Goal.HUNT) && GoalPlanner.describe(fine).equals("Keeps hunting"));
	}

	private static void testRolesAndPrices()
	{
		check("tanks carry half again as many potions", LivingSupplies.potionStockFor(4, 8) == 12);
		check("melee carries the base stock", LivingSupplies.potionStockFor(0, 8) == 8);
		check("mages carry half", LivingSupplies.potionStockFor(10, 8) == 4);
		check("archers carry half", LivingSupplies.potionStockFor(9, 8) == 4);
		check("restock at a quarter of the stock", (supply().withPotionStock(12).potionRestockAt() == 3) && (supply().withPotionStock(4).potionRestockAt() == 1));
		check("no stock, no restock", supply().withPotionStock(0).potionRestockAt() == 0);
		check("grade by level", (LivingSupplies.gradeFor(19) == 0) && (LivingSupplies.gradeFor(20) == 1) && (LivingSupplies.gradeFor(40) == 2) && (LivingSupplies.gradeFor(76) == 5));
		check("a tier costs its real kit price", SupplyPlanner.nextTierPrice(1, new SupplyPlanner.Prices(90L, 7L, 400L, 1_392_060L), supply()) == 1_392_060L);
		check("without item prices the configured cost is the fallback", SupplyPlanner.nextTierPrice(1, prices(), supply()) == 100_000L);
	}

	private static void testZoneChooser() throws Exception
	{
		final ZoneCatalog catalog = catalog();
		final ZoneCatalog.Town alpha = catalog.town("Alpha");

		check("a nearby zone is walked to", ZoneChooser.route(catalog.zone("Near Woods"), alpha, catalog).way() == ZoneChooser.Way.WALK);
		final ZoneChooser.Choice far = ZoneChooser.route(catalog.zone("Far Hills"), alpha, catalog);
		check("a far zone uses the gatekeeper", (far.way() == ZoneChooser.Way.GATEKEEPER) && (far.fee() == 2000L));
		final ZoneChooser.Choice hop = ZoneChooser.route(catalog.zone("Beyond"), alpha, catalog);
		check("a zone served by another town is a hop", (hop.way() == ZoneChooser.Way.HOP) && "Beta".equals(hop.viaTown()) && (hop.fee() == 1500L));

		final ZoneChooser.Choice keep = ZoneChooser.choose(new ZoneChooser.Situation(16, "Elf", catalog.zone("Near Woods"), alpha, 0L, null, 0), catalog, null);
		check("keeps a zone that still fits", "Near Woods".equals(keep.zone().name()));
		final ZoneChooser.Choice slack = ZoneChooser.choose(new ZoneChooser.Situation(24, "Elf", catalog.zone("Near Woods"), alpha, 100_000L, null, 0), catalog, null);
		check("keeps it up to four levels past its top", "Near Woods".equals(slack.zone().name()));

		// Level 18 with no zone: Far Hills is the better fit on levels, but it is far from the human newbie grounds.
		final ZoneChooser.Choice elf = ZoneChooser.choose(new ZoneChooser.Situation(18, "Elf", null, alpha, 100_000L, null, 0), catalog, null);
		check("without a home any zone that fits best", "Far Hills".equals(elf.zone().name()));
		final ZoneChooser.Choice human = ZoneChooser.choose(new ZoneChooser.Situation(18, "Human", null, alpha, 100_000L, null, 0), catalog, null);
		check("a young bot stays near home", "Near Woods".equals(human.zone().name()));
		final ZoneChooser.Choice older = ZoneChooser.choose(new ZoneChooser.Situation(21, "Human", null, alpha, 100_000L, null, 0), catalog, null);
		check("from level 21 it roams", !"Near Woods".equals(older.zone().name()));

		final ZoneChooser.Choice best = ZoneChooser.choose(new ZoneChooser.Situation(25, "Elf", catalog.zone("Near Woods"), alpha, 100_000L, null, 0), catalog, null);
		// Level 25: Beyond (20 to 30) has more room to grow than Far Hills (15 to 25), so it is the better fit.
		check("picks the best fit when outleveled", "Beyond".equals(best.zone().name()) && (best.way() == ZoneChooser.Way.HOP));

		final ZoneChooser.Choice poor = ZoneChooser.choose(new ZoneChooser.Situation(25, "Elf", catalog.zone("Near Woods"), alpha, 100L, null, 0), catalog, null);
		check("cannot pick a zone it cannot afford", poor == null);

		final Map<String, Integer> crowded = new HashMap<>();
		crowded.put("Beyond", 8);
		final ZoneChooser.Choice avoid = ZoneChooser.choose(new ZoneChooser.Situation(21, "Elf", null, alpha, 100_000L, crowded, 8), catalog, null);
		check("skips a full zone", "Far Hills".equals(avoid.zone().name()));
		final Map<String, Integer> allFull = new HashMap<>();
		allFull.put("Near Woods", 20);
		allFull.put("Far Hills", 12);
		allFull.put("Beyond", 9);
		final ZoneChooser.Choice spill = ZoneChooser.choose(new ZoneChooser.Situation(21, "Elf", null, alpha, 100_000L, allFull, 8), catalog, null);
		check("when every zone that fits is full it goes to the least crowded one", (spill != null) && "Beyond".equals(spill.zone().name()));
		check("but still not to one it cannot afford", ZoneChooser.choose(new ZoneChooser.Situation(21, "Elf", null, alpha, 100L, allFull, 8), catalog, null) == null);
	}

	private static void testTravelLeg()
	{
		final TravelLeg walk = TravelLeg.walk(new Point(0, 0, 0), new Point(1000, 0, 0), 1000L, 100.0);
		check("walk time follows speed", walk.durationMs() == 10_000L);
		check("halfway along the walk", walk.positionAt(6000L).equals(new Point(500, 0, 0)));
		check("done at the end", walk.done(11_000L) && !walk.done(10_999L) && walk.positionAt(20_000L).equals(new Point(1000, 0, 0)));
		final TravelLeg stay = TravelLeg.stay(new Point(5, 6, 7), 0L, 500L);
		check("a stay does not move", stay.positionAt(250L).equals(new Point(5, 6, 7)));
		check("round trip through the row text", TravelLeg.decode(walk.encode()).equals(walk));
		check("bad text gives no leg", (TravelLeg.decode("1,2,3") == null) && (TravelLeg.decode("a,b,c,d,e,f,g,h") == null) && (TravelLeg.decode(null) == null));
	}

	private static void testTownTripWithScroll() throws Exception
	{
		final ColdLife.Context context = context(0);
		final ColdBot bot = bot(15, "Near Woods", 20_000L, 0L, 1L, 5000);
		final List<DecisionLog.Event> events = new ArrayList<>();

		ColdLife.advance(bot, 0L, 0L, context, events);
		check("out of potions: uses a scroll", ColdLife.ESCAPING.equals(bot.getActivity()) && (bot.getEscapes() == 0L) && "Alpha".equals(bot.getTown()));
		check("the scroll use is logged", events.stream().anyMatch(e -> e.text().startsWith("Used a Scroll of Escape to Alpha")));
		ColdLife.advance(bot, 10_000L, 10_000L, context, events);
		check("stands still while casting", (bot.getX() == 5000) && ColdLife.ESCAPING.equals(bot.getActivity()));

		ColdLife.advance(bot, 20_000L, 10_000L, context, events);
		check("arrives in town after the cast", ColdLife.IN_TOWN.equals(bot.getActivity()) && (bot.getX() == 0) && (bot.getY() == 0));
		final long errandsEnd = bot.getLeg().endAt();
		check("errands take the walk to both NPCs plus a stop at each", errandsEnd > (20_000L + 20_000L));

		ColdLife.advance(bot, errandsEnd, errandsEnd - 20_000L, context, events);
		check("shopped in town", (bot.getPotions() == 10L) && (bot.getEscapes() == 2L) && (bot.getAdena() < 20_000L));
		check("heads back to its zone", ColdLife.TO_ZONE.equals(bot.getActivity()) && "Near Woods".equals(bot.getZone()) && (bot.getTown() == null));
		check("the purchase is logged", events.stream().anyMatch(e -> e.text().startsWith("Shopped in Alpha")));

		final long arrive = bot.getLeg().endAt();
		ColdLife.advance(bot, arrive, arrive - errandsEnd, context, events);
		check("hunting again on arrival", ColdLife.HUNTING.equals(bot.getActivity()) && (bot.getLeg() == null) && (bot.getX() == 5000));
	}

	private static void testWalkWithoutScroll() throws Exception
	{
		final ColdLife.Context context = context(0);
		final ColdBot bot = bot(15, "Near Woods", 20_000L, 0L, 0L, 5000);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, events);
		check("no scroll: walks to the grocer", ColdLife.WALKING_TO_TOWN.equals(bot.getActivity()) && bot.getLeg().to().equals(new Point(0, 100, 0)) && (bot.getLeg().durationMs() == 50_010L));
		check("the walk is logged", events.stream().anyMatch(e -> e.text().startsWith("Has no Scroll of Escape, so walks to Alpha")));
		ColdLife.advance(bot, 25_000L, 25_000L, context, events);
		check("moves along the road", (bot.getX() == 2500) && ColdLife.WALKING_TO_TOWN.equals(bot.getActivity()));
		ColdLife.advance(bot, 50_010L, 25_010L, context, events);
		check("reaches town at the grocer", ColdLife.IN_TOWN.equals(bot.getActivity()) && (bot.getX() == 0) && (bot.getY() == 100));

		// 2,000 units from town with a scroll in the bag: walks anyway, like a player.
		final ColdBot near = bot(15, "Near Woods", 20_000L, 0L, 1L, 2000);
		events.clear();
		ColdLife.advance(near, 0L, 0L, context, events);
		check("close to town: walks and keeps the scroll", ColdLife.WALKING_TO_TOWN.equals(near.getActivity()) && (near.getEscapes() == 1L));
		check("the short walk is logged", events.stream().anyMatch(e -> e.text().startsWith("Alpha is close, so walks there")));
	}

	private static void testAfkAndStuck() throws Exception
	{
		final ColdLife.Context afkContext = context(100);
		final ColdBot bot = bot(15, "Near Woods", 20_000L, 0L, 1L, 5000);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, afkContext, events);
		ColdLife.advance(bot, 20_000L, 20_000L, afkContext, events);
		check("arrival always starts the errands", ColdLife.IN_TOWN.equals(bot.getActivity()));
		final long errandsEnd = bot.getLeg().endAt();
		ColdLife.advance(bot, errandsEnd, errandsEnd - 20_000L, afkContext, events);
		check("a sure AFK roll goes AFK after shopping, by the gatekeeper", ColdLife.AFK.equals(bot.getActivity()) && (bot.getLeg().durationMs() == 60_000L) && (bot.getPotions() == 10L) && (bot.getX() == 100) && (bot.getY() == 0));
		ColdLife.advance(bot, errandsEnd + 60_000L, 60_000L, afkContext, events);
		check("back from AFK it heads out", ColdLife.TO_ZONE.equals(bot.getActivity()) && events.stream().anyMatch(e -> e.text().equals("Back from AFK")));

		// Level 25 in a zone it outleveled, and too poor for any fitting zone's fees.
		final ColdLife.Context context = context(0);
		final ColdBot poor = bot(25, "Near Woods", 600L, 10L, 2L, 0);
		poor.setActivity(ColdLife.IN_TOWN);
		poor.setTown("Alpha");
		poor.setGearTier(2);
		poor.setLeg(TravelLeg.stay(new Point(0, 0, 0), 0L, 0L));
		events.clear();
		ColdLife.advance(poor, 1L, 1L, context, events);
		check("with nothing better it can afford, it goes back to its old zone", ColdLife.TO_ZONE.equals(poor.getActivity()) && "Near Woods".equals(poor.getZone()));

		// Too poor for any gatekeeper: it walks, however far, to the nearest zone that fits, instead of waiting in town.
		final ColdBot broke = bot(21, "Far Hills", 600L, 10L, 2L, 0);
		broke.setActivity(ColdLife.IN_TOWN);
		broke.setTown("Alpha");
		broke.setLevel(30);
		broke.setGearTier(2);
		broke.setLeg(TravelLeg.stay(new Point(0, 0, 0), 0L, 0L));
		events.clear();
		ColdLife.advance(broke, 1L, 1L, context, events);
		check("too poor for a gatekeeper it walks to the nearest fitting zone", ColdLife.TO_ZONE.equals(broke.getActivity()) && "Beyond".equals(broke.getZone()) && (broke.getAdena() == 600L) && (broke.getLeg().to().x() == 121000));
		check("the walk says why", events.stream().anyMatch(e -> e.text().contains("on foot, as it cannot afford a gatekeeper")));

		// No zone fits its level at all: then it waits in town.
		final ColdBot stuck = bot(21, "Far Hills", 600L, 10L, 2L, 0);
		stuck.setActivity(ColdLife.IN_TOWN);
		stuck.setTown("Alpha");
		stuck.setLevel(31);
		stuck.setGearTier(2);
		stuck.setLeg(TravelLeg.stay(new Point(0, 0, 0), 0L, 0L));
		events.clear();
		ColdLife.advance(stuck, 1L, 1L, context, events);
		check("with no fitting zone it waits in town", ColdLife.IN_TOWN.equals(stuck.getActivity()) && (stuck.getLeg().durationMs() == 300_000L));
		check("the wait is a keyed decision", events.stream().anyMatch(e -> "stuck-Alpha".equals(e.key())));
	}

	private static void testErrandPosition() throws Exception
	{
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 0L, 0L, 0.0, 0, 1000L, 2500.0);
		final ZoneCatalog.Town alpha = catalog().town("Alpha");
		final Point start = new Point(0, -1000, 0);
		// 1100 units to the grocer at (0, 100): 11 s walking, then a 10 s stop, then 1.4 s on to the gatekeeper.
		check("starts where it arrived", ColdLife.errandPosition(alpha, start, 0L, 0L, travel).equals(start));
		check("walks toward the grocer", ColdLife.errandPosition(alpha, start, 0L, 5_500L, travel).equals(new Point(0, -450, 0)));
		check("stops at the grocer", ColdLife.errandPosition(alpha, start, 0L, 15_000L, travel).equals(new Point(0, 100, 0)));
		check("ends by the gatekeeper", ColdLife.errandPosition(alpha, start, 0L, 60_000L, travel).equals(new Point(100, 0, 0)));

		final ColdLife.Context context = context(0);
		final ColdBot bot = bot(15, "Near Woods", 20_000L, 0L, 1L, 5000);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, events);
		ColdLife.advance(bot, 20_000L, 20_000L, context, events);
		ColdLife.advance(bot, 25_000L, 5_000L, context, events);
		check("a cold bot in town is at the grocer, not the arrival point", (bot.getX() == 0) && (bot.getY() == 100) && ColdLife.IN_TOWN.equals(bot.getActivity()));
	}

	private static void testStarterZone() throws Exception
	{
		final ColdBot human = new ColdBot();
		human.setRace("Human");
		human.setLevel(1);
		check("a new human starts in its newbie grounds", ColdLife.adoptStartingZone(human, catalog()) && "Newbie Field".equals(human.getZone()));
		final ColdBot elf = new ColdBot();
		elf.setRace("Elf");
		elf.setLevel(1);
		check("no starter zone for a race without one", !ColdLife.adoptStartingZone(elf, catalog()) && (elf.getZone() == null));
		check("hunting counts only while hunting", ColdLife.isHunting(null) && ColdLife.isHunting(ColdLife.HUNTING) && !ColdLife.isHunting(ColdLife.RESTING) && !ColdLife.isHunting(ColdLife.DEAD) && !ColdLife.isHunting(ColdLife.IN_TOWN));
		check("shipped travel defaults", TravelConfig.defaults().enabled() && (TravelConfig.defaults().escapeStock() == 2) && (TravelConfig.defaults().potionStock() == 30));
	}

	private static final DropYield.Items ITEMS = new DropYield.Items()
	{
		@Override
		public long sellPrice(int itemId)
		{
			return (itemId == 900) ? 0L : 100L; // 900 is a quest item
		}

		@Override
		public boolean herb(int itemId)
		{
			return itemId == 8600;
		}
	};

	private static boolean near(double value, double expected)
	{
		return Math.abs(value - expected) < 1e-6;
	}

	private static void testDropYield()
	{
		final DropYield.Rates plain = DropYield.Rates.plain();
		// Adena: a 70% group, 19 to 29 each (24 on average). Loot: 10% x 50% for an item a shop pays 100 for. A herb and a
		// quest item drop too but are worth nothing.
		final List<DropYield.Drop> drops = List.of(new DropYield.Drop(57, 70, 100, 19, 29), new DropYield.Drop(1, 10, 50, 1, 1), new DropYield.Drop(8600, 20, 100, 1, 1), new DropYield.Drop(900, 100, 100, 2, 2));
		final DropYield.Yield yield = DropYield.perKill(drops, List.of(), false, 20, 20, plain, ITEMS);
		check("adena per kill is chance times the average amount", near(yield.adena(), 0.7 * 24));
		check("loot per kill is chance times the shop price, herbs and quest items excluded", near(yield.loot(), 0.05 * 100));

		// Three likely drops but a kill gives at most two: each is scaled down to fit.
		final List<DropYield.Drop> many = List.of(new DropYield.Drop(1, 90, 100, 1, 1), new DropYield.Drop(2, 90, 100, 1, 1), new DropYield.Drop(3, 90, 100, 1, 1));
		check("capped at two chance drops per kill", near(DropYield.perKill(many, List.of(), false, 20, 20, plain, ITEMS).loot(), 200));
		check("a sure drop is never capped", near(DropYield.perKill(List.of(new DropYield.Drop(1, 100, 100, 3, 3)), List.of(), false, 20, 20, plain, ITEMS).loot(), 300));

		final DropYield.Rates rated = new DropYield.Rates(2, 3, 1, 1, Map.of(57, 1f), Map.of(57, 5f), 0, DropYield.Gap.NONE, DropYield.Gap.NONE);
		final DropYield.Yield boosted = DropYield.perKill(drops, List.of(), false, 20, 20, rated, ITEMS);
		check("adena follows its own rates", near(boosted.adena(), 0.7 * 24 * 5));
		check("other drops follow the drop rates", near(boosted.loot(), 0.1 * 3 * 100));

		final DropYield.Gap gap = new DropYield.Gap(5, 10, 10);
		check("no gap penalty close in level", near(gap.chance(0), 100) && near(gap.chance(-5), 100));
		check("full gap penalty far below", near(gap.chance(-10), 10) && near(gap.chance(-30), 10));
		check("the gap penalty scales between", near(gap.chance(-7), 64));

		final List<DropYield.Drop> spoil = List.of(new DropYield.Drop(1, 100, 50, 2, 2));
		check("spoils count only for a spoiler", near(DropYield.perKill(List.of(), spoil, true, 20, 20, plain, ITEMS).loot(), 100) && near(DropYield.perKill(List.of(), spoil, false, 20, 20, plain, ITEMS).loot(), 0));
		check("spoiler classes", LivingSupplies.isSpoiler(54) && LivingSupplies.isSpoiler(55) && LivingSupplies.isSpoiler(117) && !LivingSupplies.isSpoiler(56));

		final DropYield.Yield average = DropYield.average(List.of(new DropYield.Yield(100, 10), new DropYield.Yield(20, 50)), List.of(3, 1));
		check("zone yield is weighted by spawn count", near(average.adena(), 80) && near(average.loot(), 20));
		check("nothing to average", DropYield.average(List.of(), List.of()) == DropYield.Yield.NONE);
	}

	private static void testLootSale() throws Exception
	{
		// 600 adena alone buys nothing, but it carries loot worth 20,000: it plans with what it will have after selling.
		final ColdLife.Context context = context(0);
		final ColdBot bot = bot(15, "Near Woods", 600L, 0L, 1L, 5000);
		bot.setLoot(20_000L);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, events);
		check("loot counts toward a supply trip", ColdLife.ESCAPING.equals(bot.getActivity()));
		ColdLife.advance(bot, 20_000L, 20_000L, context, events);
		final long errandsEnd = bot.getLeg().endAt();
		ColdLife.advance(bot, errandsEnd, errandsEnd - 20_000L, context, events);
		check("the loot is sold in town", (bot.getLoot() == 0L) && events.stream().anyMatch(e -> e.text().equals("Sold its loot in Alpha for 20,000 adena")));
		check("and the proceeds buy supplies", (bot.getPotions() == 10L) && (bot.getAdena() > 600L) && (bot.getAdena() < 20_600L));
	}

	private static void testColdRisk()
	{
		final ColdRisk.Params params = new ColdRisk.Params(0.3, 90_000L, 600_000L, 60_000L, 3_600_000L);
		// Melee (class 0), zone 10 to 20, stocked and geared.
		final ColdRisk.Danger middle = ColdRisk.danger(params, 15, 10, 20, 10, 1, 1, 0);
		check("the base rate in the middle of a zone", near(middle.deathsPerHour(), 0.3) && middle.reasons().isEmpty());
		final ColdRisk.Danger bottom = ColdRisk.danger(params, 10, 10, 20, 10, 1, 1, 0);
		check("twice as risky at the bottom of the zone", near(bottom.deathsPerHour(), 0.6) && bottom.reasons().contains("at the low end of the zone's levels"));
		check("half as risky at the top", near(ColdRisk.danger(params, 20, 10, 20, 10, 2, 2, 0).deathsPerHour(), 0.15));
		final ColdRisk.Danger bare = ColdRisk.danger(params, 15, 10, 20, 0, 0, 1, 0);
		check("no potions and old gear add up", near(bare.deathsPerHour(), 0.3 * 2 * 1.5) && bare.reasons().contains("out of potions") && bare.reasons().contains("gear behind its level"));
		check("tanks survive better, mages worse", near(ColdRisk.danger(params, 15, 10, 20, 10, 1, 1, 4).deathsPerHour(), 0.225) && near(ColdRisk.danger(params, 15, 10, 20, 10, 1, 1, 10).deathsPerHour(), 0.375));
		check("death chance over time", near(ColdRisk.deathChance(1.0, 3_600_000L), 1 - Math.exp(-1)) && near(ColdRisk.deathChance(0.3, 0L), 0));
		check("exp loss by level", near(ColdRisk.expLossPercent(10), 5.8) && near(ColdRisk.expLossPercent(80), 0.9) && near(ColdRisk.expLossPercent(95), 0));
		check("mages rest half again as long", (ColdRisk.restMs(params, 10) == 90_000L) && (ColdRisk.restMs(params, 0) == 60_000L));

		final ColdRisk.State once = ColdRisk.died(ColdRisk.State.EMPTY, "Near Woods", 1_000L, params);
		check("one death is remembered, no avoidance yet", "Near Woods".equals(once.deathZone()) && (once.zoneDeaths() == 1) && !once.avoiding(1_000L));
		final ColdRisk.State twice = ColdRisk.died(once, "Near Woods", 2_000_000L, params);
		check("a second death there within the hour avoids the zone", twice.avoids("Near Woods", 2_000_000L) && !twice.avoids("Near Woods", 2_000_000L + 3_600_000L) && !twice.avoids("Far Hills", 2_000_000L));
		final ColdRisk.State apart = ColdRisk.died(once, "Near Woods", 1_000L + 3_600_000L, params);
		check("deaths far apart do not", !apart.avoiding(1_000L + 3_600_000L) && (apart.zoneDeaths() == 1));
		check("elsewhere starts a new count", ColdRisk.died(once, "Far Hills", 2_000L, params).zoneDeaths() == 1);
		check("round trip through the row text", ColdRisk.State.decode(twice.encode()).equals(twice) && ColdRisk.State.decode(once.withHunted(5L).encode()).equals(once.withHunted(5L)));
		check("bad text gives an empty state", (ColdRisk.State.decode("x|y") == ColdRisk.State.EMPTY) && (ColdRisk.State.decode(null) == ColdRisk.State.EMPTY) && (ColdRisk.State.decode("a|b|c|d|e|f") == ColdRisk.State.EMPTY));
	}

	private static ColdLife.Context riskyContext(double deathsPerHour, long restEveryMs) throws Exception
	{
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 60_000L, 60_000L, 0.0, 8, 300_000L, 2500.0);
		final ColdRisk.Params risk = new ColdRisk.Params(deathsPerHour, 90_000L, restEveryMs, 60_000L, 3_600_000L);
		return new ColdLife.Context(catalog(), supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7), risk, level -> 100_000L);
	}

	private static void testLivingChat()
	{
		check("a hunting bot says where it hunts", LivingChat.activity(ColdLife.HUNTING, "Ruins of Agony", null).equals("hunting in Ruins of Agony") && LivingChat.activity(null, null, null).equals("hunting"));
		check("a bot heading to town says so", LivingChat.activity(ColdLife.WALKING_TO_TOWN, "Ruins of Agony", "Gludio").equals("on the way to Gludio to sell loot and restock"));
		check("a bot heading out names its zone", LivingChat.activity(ColdLife.TO_ZONE, "Ant Nest", "Dion").equals("on the way to Ant Nest to hunt"));
		check("a bot at the trainer says where", LivingChat.activity(ColdLife.TRAINER, null, "Giran").equals("learning new skills at the trainer in Giran"));
	}

	private static void testColdDeathAndRest() throws Exception
	{
		// A sure death: 1,000 deaths an hour over a minute.
		final ColdLife.Context deadly = riskyContext(1000.0, 0L);
		final ColdBot bot = bot(15, "Near Woods", 20_000L, 10L, 2L, 5000);
		bot.setExpIntoLevel(50_000L);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, 60_000L, 60_000L, deadly, events);
		// Level 15 loses 6.5 - 1.05 = 5.45% of 100,000.
		check("a cold death costs experience", ColdLife.DEAD.equals(bot.getActivity()) && (bot.getExpIntoLevel() == 50_000L - 5_450L) && (bot.getDeaths() == 1));
		check("and sends it to the nearest town", "Alpha".equals(bot.getTown()) && (bot.getX() == 0) && (bot.getY() == 0) && (bot.getLeg().durationMs() == 90_000L));
		check("the death is logged", events.stream().anyMatch(e -> e.text().startsWith("Died in Near Woods") && e.text().contains("lost 5,450 exp. Went back to Alpha to recover")));
		ColdLife.advance(bot, 150_000L, 90_000L, deadly, events);
		check("back on its feet it runs its errands", ColdLife.IN_TOWN.equals(bot.getActivity()) && events.stream().anyMatch(e -> e.text().equals("Back on its feet in Alpha. Heading to the shops")));

		final ColdBot poor = bot(15, "Near Woods", 20_000L, 10L, 2L, 5000);
		poor.setExpIntoLevel(1_000L);
		ColdLife.advance(poor, 60_000L, 60_000L, deadly, new ArrayList<>());
		check("it never loses a level", poor.getExpIntoLevel() == 0L);

		// Two deaths in the same zone: it stays away from it.
		final ColdBot twice = bot(15, "Near Woods", 20_000L, 10L, 2L, 5000);
		final List<DecisionLog.Event> log = new ArrayList<>();
		ColdLife.advance(twice, 60_000L, 60_000L, deadly, log);
		twice.setActivity(ColdLife.HUNTING);
		twice.setLeg(null);
		twice.setX(5000);
		twice.setY(0);
		ColdLife.advance(twice, 120_000L, 60_000L, deadly, log);
		check("a second death there avoids the zone", twice.getRisk().avoids("Near Woods", 120_000L) && log.stream().anyMatch(e -> e.text().startsWith("Died twice in Near Woods")));

		// With the zone avoided, the chooser skips it even while it still fits.
		final ZoneCatalog catalog = catalog();
		final ZoneChooser.Choice elsewhere = ZoneChooser.choose(new ZoneChooser.Situation(16, "Elf", catalog.zone("Near Woods"), catalog.town("Alpha"), 100_000L, null, 0, "Near Woods"), catalog, null);
		check("the chooser skips an avoided zone", "Far Hills".equals(elsewhere.zone().name()));

		// Rest: after ten minutes of hunting it sits down for a minute, then carries on.
		final ColdLife.Context calm = riskyContext(0.0, 600_000L);
		final ColdBot tired = bot(15, "Near Woods", 1_000L, 10L, 2L, 5000);
		ColdLife.advance(tired, 300_000L, 300_000L, calm, new ArrayList<>());
		check("hunts until it is due a rest", ColdLife.HUNTING.equals(tired.getActivity()) && (tired.getRisk().huntedSinceRestMs() == 300_000L));
		ColdLife.advance(tired, 600_000L, 300_000L, calm, new ArrayList<>());
		check("then sits down to rest", ColdLife.RESTING.equals(tired.getActivity()) && (tired.getLeg().durationMs() == 60_000L) && (tired.getRisk().huntedSinceRestMs() == 0L));
		ColdLife.advance(tired, 660_000L, 60_000L, calm, new ArrayList<>());
		check("and gets back to hunting", ColdLife.HUNTING.equals(tired.getActivity()) && (tired.getLeg() == null));

		// A hot bot is never killed or sat down by the cold rules.
		final ColdBot hot = bot(15, "Near Woods", 20_000L, 10L, 2L, 5000);
		hot.setHotLock(true);
		ColdLife.advance(hot, 60_000L, 60_000L, deadly, new ArrayList<>());
		check("hot bots live and die for real", ColdLife.HUNTING.equals(hot.getActivity()) && (hot.getDeaths() == 0));

		// A hot bot that died for real stands up in a town and recovers there; the game already took the exp.
		final ColdBot fallen = bot(15, "Near Woods", 20_000L, 10L, 2L, 5000);
		fallen.setExpIntoLevel(50_000L);
		final List<DecisionLog.Event> fell = new ArrayList<>();
		final ZoneCatalog.Town where = ColdLife.diedHot(fallen, new ZoneCatalog.Point(100, 0, 0), 60_000L, deadly, fell);
		check("a hot death recovers in the nearest town", (where != null) && "Alpha".equals(where.name()) && ColdLife.DEAD.equals(fallen.getActivity()) && "Alpha".equals(fallen.getTown()));
		check("where it stood up, for the recovery time", (fallen.getX() == 100) && (fallen.getLeg().durationMs() == 90_000L) && (fallen.getDeaths() == 1) && (fallen.getExpIntoLevel() == 50_000L));
		check("a hot death is logged", fell.stream().anyMatch(e -> e.text().equals("Died in Near Woods while hot and went back to Alpha to recover")));
		ColdLife.advance(fallen, 150_000L, 90_000L, deadly, fell);
		check("then runs its errands", ColdLife.IN_TOWN.equals(fallen.getActivity()));
	}

	private static void testClassPath() throws Exception
	{
		check("class tiers", (ClassPath.tier(18) == 0) && (ClassPath.tier(19) == 1) && (ClassPath.tier(20) == 2) && (ClassPath.tier(99) == 3) && (ClassPath.tier(9999) == -1));
		check("due at 20, 40 and 76", ClassPath.due(18, 20) && !ClassPath.due(18, 19) && !ClassPath.due(19, 39) && ClassPath.due(19, 40) && ClassPath.due(20, 76) && !ClassPath.due(99, 80));
		check("the same bot always takes the same branch", ClassPath.next(18, 5L) == ClassPath.next(18, 5L));
		final Set<Integer> branches = new HashSet<>();
		final Set<Integer> wizards = new HashSet<>();
		for (long id = 1; id <= 200; id++)
		{
			branches.add(ClassPath.next(18, id));
			wizards.add(ClassPath.next(11, id));
		}
		check("bots spread over the branches", branches.equals(Set.of(19, 22)));
		check("summoners are included", wizards.equals(Set.of(12, 13, 14)));
		check("no class after the third", ClassPath.next(99, 1L) == -1);
		check("class masters by line", "ElfHumanFighterChange1".equals(ClassPath.master(18, 19)) && "ElfHumanFighterChange2".equals(ClassPath.master(19, 20)) && "ElfHumanFighterChange2".equals(ClassPath.master(20, 99)));
		check("mystics and priests", "ElfHumanWizardChange1".equals(ClassPath.master(10, 15)) && "ElfHumanClericChange2".equals(ClassPath.master(15, 16)) && "ElfHumanWizardChange2".equals(ClassPath.master(11, 14)));
		check("dwarves by branch", "DwarfBlacksmithChange1".equals(ClassPath.master(53, 56)) && "DwarfWarehouseChange1".equals(ClassPath.master(53, 54)) && "DwarfWarehouseChange2".equals(ClassPath.master(55, 117)));
		check("class names", "Elven Knight".equals(ClassPath.name(19)) && "Arcana Lord".equals(ClassPath.name(96)));
		final ClassPath.Quest quest = new ClassPath.Quest(ClassPath.Stage.HUNT, 19, 123L);
		check("quest round trip", quest.equals(ClassPath.Quest.decode(quest.encode())) && !quest.needsMaster());
		check("bad quest text", (ClassPath.Quest.decode("x|1|2") == null) && (ClassPath.Quest.decode(null) == null) && (ClassPath.Quest.decode("TAKE|1") == null));

		final ZoneCatalog catalog = catalog();
		final ZoneCatalog.Town alpha = catalog.town("Alpha");
		check("a master in town", catalog.masterTown("ElfHumanFighterChange1", alpha) == alpha);
		check("or the nearest gatekeeper town with one", catalog.masterTown("ElfHumanFighterChange2", alpha) == catalog.town("Beta"));
		check("or none", catalog.masterTown("OrcChange1", alpha) == null);

		// The class errand is a town goal of its own, blocked when it cannot go.
		final GoalPlanner.View ready = new GoalPlanner.View(20, 20_000L, 10L, 5000L, 2L, 2, true, "Near Woods", 10, 20, null, "take the class change quest for Elven Knight", null);
		check("a class errand sends it to town", "class".equals(GoalPlanner.plan(ready, prices(), supply()).chosen().key()));
		final GoalPlanner.View blocked = new GoalPlanner.View(20, 20_000L, 10L, 5000L, 2L, 2, true, "Near Woods", 10, 20, null, "take the class change quest for Elven Knight", "cannot afford the gatekeeper to Beta");
		check("unless it cannot go", GoalPlanner.plan(blocked, prices(), supply()).chosen().goal() == GoalPlanner.Goal.HUNT);
	}

	private static ColdLife.Context classContext() throws Exception
	{
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 60_000L, 60_000L, 0.0, 8, 300_000L, 2500.0);
		return new ColdLife.Context(catalog(), supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7), null, level -> 0L, new long[]
		{
			600_000L,
			1_200_000L,
			1_800_000L
		});
	}

	/** Advances the bot, a leg or a minute at a time, until {@code done} holds (at most 50 steps). */
	private static long runUntil(ColdBot bot, long now, ColdLife.Context context, List<DecisionLog.Event> events, Predicate<ColdBot> done)
	{
		long time = now;
		for (int i = 0; (i < 50) && !done.test(bot); i++)
		{
			final long next = (bot.getLeg() != null) ? Math.max(time, bot.getLeg().endAt()) : (time + 60_000L);
			ColdLife.advance(bot, next, next - time, context, events);
			time = next;
		}
		return time;
	}

	private static void testClassChange() throws Exception
	{
		// Without the class-change tuning a bot keeps its class.
		final ColdBot plain = bot(20, "Near Woods", 20_000L, 10L, 2L, 5000);
		plain.setClassId(18);
		ColdLife.advance(plain, 0L, 0L, context(0), new ArrayList<>());
		check("no class changes when they are off", (plain.getQuest() == null) && (plain.getClassId() == 18));

		// First change: the master is in its own town.
		final ColdLife.Context context = classContext();
		final ColdBot bot = bot(20, "Near Woods", 20_000L, 10L, 2L, 5000);
		bot.setClassId(18);
		final int target = ClassPath.next(18, bot.getId());
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, events);
		check("reaching 20 starts the class change", (bot.getQuest() != null) && (bot.getQuest().stage() == ClassPath.Stage.TAKE) && (bot.getQuest().target() == target) && events.stream().anyMatch(e -> e.text().startsWith("Reached level 20: time to become a " + ClassPath.name(target))));
		check("and sends it to town", ColdLife.ESCAPING.equals(bot.getActivity()) && "town".equals(bot.getGoal()));
		long now = runUntil(bot, 0L, context, events, b -> ColdLife.CLASS_MASTER.equals(b.getActivity()));
		check("after its errands it walks to the class master", ColdLife.CLASS_MASTER.equals(bot.getActivity()) && (bot.getLeg().to().y() == 300));
		now = runUntil(bot, now, context, events, b -> !ColdLife.CLASS_MASTER.equals(b.getActivity()));
		check("takes the quest and goes hunting for it", (bot.getQuest().stage() == ClassPath.Stage.HUNT) && (bot.getQuest().endAt() == now + 600_000L) && ColdLife.TO_ZONE.equals(bot.getActivity()) && events.stream().anyMatch(e -> e.text().contains("Hunts for it for about 10 min")));
		check("still in its old class meanwhile", bot.getClassId() == 18);
		now = runUntil(bot, now, context, events, b -> (b.getQuest().stage() == ClassPath.Stage.RETURN));
		check("the quest hunt ends on time", (now >= bot.getQuest().endAt()) && events.stream().anyMatch(e -> e.text().startsWith("Finished the quest hunt for ")));
		now = runUntil(bot, now, context, events, b -> (b.getQuest() == null));
		check("hands it in and changes class", (bot.getClassId() == target) && events.stream().anyMatch(e -> e.text().equals("Became a " + ClassPath.name(target) + " at the class master in Alpha")));
		check("then heads back out", ColdLife.TO_ZONE.equals(bot.getActivity()));

		// Second change: the master is a gatekeeper hop away.
		final ColdBot knight = bot(40, "Near Woods", 20_000L, 10L, 2L, 5000);
		knight.setClassId(19);
		knight.setQuest(new ClassPath.Quest(ClassPath.Stage.TAKE, 20, 0L));
		final List<DecisionLog.Event> log = new ArrayList<>();
		runUntil(knight, 0L, context, log, b -> ColdLife.TO_TOWN.equals(b.getActivity()));
		check("pays the gatekeeper to the master's town", ColdLife.TO_TOWN.equals(knight.getActivity()) && "Beta".equals(knight.getTown()) && log.stream().anyMatch(e -> e.text().equals("Paid the Alpha gatekeeper 1,000 adena to go to the class master in Beta")));
		runUntil(knight, 0L, context, log, b -> (b.getQuest().stage() == ClassPath.Stage.HUNT));
		check("and takes the quest there", (knight.getQuest().stage() == ClassPath.Stage.HUNT) && log.stream().anyMatch(e -> e.text().startsWith("Took the quest for Temple Knight from the class master in Beta")));

		// A quest length of 0 changes class on the first visit.
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 60_000L, 60_000L, 0.0, 8, 300_000L, 2500.0);
		final ColdLife.Context instant = new ColdLife.Context(catalog(), supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7), null, level -> 0L, new long[3]);
		final ColdBot quick = bot(20, "Near Woods", 20_000L, 10L, 2L, 5000);
		quick.setClassId(18);
		runUntil(quick, 0L, instant, new ArrayList<>(), b -> (b.getClassId() != 18));
		check("no quest hunt when its length is 0", (quick.getClassId() == ClassPath.next(18, quick.getId())) && (quick.getQuest() == null));
	}

	/** A small skill tree: a free skill, two levels of one skill, a spellbook skill, an unsold book, and a level 30 one. */
	private static final List<SkillPlanner.Entry> TREE = List.of( //
		new SkillPlanner.Entry(100, 1, 1, 0L, 0, 0L, true), //
		new SkillPlanner.Entry(101, 1, 5, 100L, 0, 0L, false), //
		new SkillPlanner.Entry(101, 2, 10, 200L, 0, 0L, false), //
		new SkillPlanner.Entry(102, 1, 10, 300L, 1049, 500L, false), //
		new SkillPlanner.Entry(103, 1, 10, 50L, 999, 0L, false), //
		new SkillPlanner.Entry(104, 1, 12, 150L, 0, 0L, false), //
		new SkillPlanner.Entry(105, 1, 30, 10L, 0, 0L, false));

	private static void testSkillPlanner()
	{
		final Map<Integer, Integer> rich = new HashMap<>();
		final SkillPlanner.Lesson all = SkillPlanner.learn(TREE, rich, 12, 1000L, 1000L);
		check("learns everything its level allows", (all.learned().size() == 5) && (rich.get(101) == 2) && rich.containsKey(102) && !rich.containsKey(105));
		check("pays SP and buys the book", (all.sp() == 750L) && (all.adena() == 500L) && (all.books() == 1));
		check("a book no shop sells waits", !rich.containsKey(103) && (all.shortSp() == 0) && (all.shortAdena() == 0));

		final Map<Integer, Integer> poor = new HashMap<>();
		final SkillPlanner.Lesson some = SkillPlanner.learn(TREE, poor, 12, 250L, 0L);
		check("learns what it can pay for", (some.sp() == 250L) && poor.containsKey(100) && (poor.get(101) == 1) && poor.containsKey(104) && !poor.containsKey(102));
		check("and counts what waits", (some.shortSp() == 2) && (some.shortAdena() == 0));
		final SkillPlanner.Lesson noBook = SkillPlanner.learn(TREE, new HashMap<>(), 12, 10_000L, 100L);
		check("a book it cannot afford waits for money", (noBook.shortAdena() == 1) && (noBook.adena() == 0L));
		check("free skills cost nothing", SkillPlanner.learn(TREE, new HashMap<>(), 1, 0L, 0L).learned().size() == 1);
		check("SP wanted", SkillPlanner.spWanted(TREE, new HashMap<>(), 12) == 550L);

		final Map<Integer, Integer> granted = new HashMap<>();
		SkillPlanner.grantAll(TREE, granted, 12);
		check("an old row keeps its level's skills", granted.equals(Map.of(100, 1, 101, 2, 102, 1, 104, 1)));
		check("row text round trip", SkillPlanner.decode(SkillPlanner.encode(granted)).equals(granted) && "100:1,101:2,102:1,104:1".equals(SkillPlanner.encode(granted)));
		check("untracked and damaged text", (SkillPlanner.decode(null) == null) && SkillPlanner.decode("").isEmpty() && SkillPlanner.decode("1:2,x,3:y").equals(Map.of(1, 2)));

		check("trainers by line", "ElfHumanFighterChange1".equals(ClassPath.trainer(18)) && "ElfHumanFighterChange2".equals(ClassPath.trainer(19)) && "ElfHumanFighterChange2".equals(ClassPath.trainer(99)));
		check("trainers for casters and dwarves", "ElfHumanWizardChange2".equals(ClassPath.trainer(96)) && "DwarfWarehouseChange1".equals(ClassPath.trainer(53)) && "DwarfBlacksmithChange2".equals(ClassPath.trainer(57)) && (ClassPath.trainer(9999) == null));
	}

	private static ColdLife.Context skillContext() throws Exception
	{
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 60_000L, 60_000L, 0.0, 8, 300_000L, 2500.0);
		return new ColdLife.Context(catalog(), supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7), null, level -> 0L, null, classId -> TREE);
	}

	private static void testTrainerVisit() throws Exception
	{
		final ColdLife.Context context = skillContext();

		// A row from before skills were tracked keeps the skills of its level.
		final ColdBot old = bot(12, "Near Woods", 20_000L, 10L, 2L, 5000);
		old.setClassId(18);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(old, 0L, 0L, context, events);
		check("an old row starts with its level's skills", "100:1,101:2,102:1,104:1".equals(old.getSkills()) && events.stream().anyMatch(e -> e.text().equals("Starts learning at the trainer, knowing the 4 skills of a level 12 Elven Fighter")));

		// Five skill levels waiting and the SP for them: a trip to the trainer.
		final ColdBot bot = bot(12, "Near Woods", 20_000L, 10L, 2L, 5000);
		bot.setClassId(18);
		bot.setSkills("");
		bot.setSp(1000L);
		final List<DecisionLog.Event> log = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, log);
		check("skills waiting send it to town", ColdLife.ESCAPING.equals(bot.getActivity()) && log.stream().anyMatch(e -> e.text().contains("learn 5 skill levels at its trainer (750 SP, 1 spellbook for 500 adena)")));
		long now = runUntil(bot, 0L, context, log, b -> ColdLife.TRAINER.equals(b.getActivity()));
		check("after its errands it walks to its trainer", ColdLife.TRAINER.equals(bot.getActivity()) && (bot.getLeg().to().y() == 300));
		final long adena = bot.getAdena();
		runUntil(bot, now, context, log, b -> !ColdLife.TRAINER.equals(b.getActivity()));
		check("learns and pays", (bot.getSp() == 250L) && (bot.getAdena() == adena - 500L) && "100:1,101:2,102:1,104:1".equals(bot.getSkills()));
		check("the lesson is logged", log.stream().anyMatch(e -> e.text().equals("Learned 5 skill levels from its trainer in Alpha for 750 SP, buying 1 spellbook for 500 adena")));
		check("then heads back out", ColdLife.TO_ZONE.equals(bot.getActivity()));

		// A skill or two is no reason for a trip.
		final ColdBot few = bot(12, "Near Woods", 20_000L, 10L, 2L, 5000);
		few.setClassId(18);
		few.setSkills("100:1,101:2,102:1");
		few.setSp(1000L);
		ColdLife.advance(few, 0L, 0L, context, new ArrayList<>());
		check("one skill waits for the next town visit", ColdLife.HUNTING.equals(few.getActivity()));
	}

	private static LivingGear.Piece weapon(int id, String name, int grade, String type, boolean magic, boolean twoHanded, int pAtk, int mAtk, long sell)
	{
		return new LivingGear.Piece(id, name, LivingGear.Kind.WEAPON, grade, type, magic, twoHanded, false, pAtk, mAtk, 0, 0, sell);
	}

	private static LivingGear.Piece armor(int id, String name, LivingGear.Kind kind, String type, boolean fullBody, int pDef, int mDef, long sell)
	{
		return new LivingGear.Piece(id, name, kind, 0, type, false, false, fullBody, 0, 0, pDef, mDef, sell);
	}

	private static final Map<Integer, LivingGear.Piece> PIECES = new HashMap<>();
	static
	{
		for (LivingGear.Piece piece : List.of( //
			weapon(10, "Short Sword", 0, "SWORD", false, false, 20, 10, 100), //
			weapon(11, "Long Sword", 0, "SWORD", false, false, 30, 15, 2500), //
			weapon(12, "Bow", 0, "BOW", false, true, 40, 15, 2000), //
			weapon(13, "Sword of D", 1, "SWORD", false, false, 60, 30, 25000), //
			weapon(14, "Staff", 0, "BLUNT", true, true, 10, 30, 1000), //
			weapon(15, "Two-Hander", 0, "SWORD", false, true, 50, 20, 3000), //
			armor(20, "Shirt", LivingGear.Kind.CHEST, "LIGHT", false, 10, 0, 10), //
			armor(21, "Pants", LivingGear.Kind.LEGS, "LIGHT", false, 6, 0, 10), //
			armor(22, "Plate", LivingGear.Kind.CHEST, "HEAVY", false, 30, 0, 4000), //
			armor(23, "Plate Legs", LivingGear.Kind.LEGS, "HEAVY", false, 20, 0, 3000), //
			armor(24, "Full Plate", LivingGear.Kind.CHEST, "HEAVY", true, 55, 0, 10000), //
			armor(30, "Shield", LivingGear.Kind.SHIELD, "SHIELD", false, 15, 0, 1500), //
			armor(40, "Ring", LivingGear.Kind.RING, "NONE", false, 0, 5, 500), //
			armor(41, "Better Ring", LivingGear.Kind.RING, "NONE", false, 0, 8, 1000), //
			armor(50, "Helmet", LivingGear.Kind.HEAD, "NONE", false, 8, 0, 750)))
		{
			PIECES.put(piece.itemId(), piece);
		}
	}

	private static final LivingGear.Items GEAR_ITEMS = PIECES::get;
	private static final LivingGear.Fit WARRIOR = new LivingGear.Fit("HEAVY", Set.of("SWORD"), false, true, 0);

	private static Map<LivingGear.Slot, Integer> gear(String text)
	{
		return LivingGear.decode(text);
	}

	private static void testGearRules()
	{
		check("a bow is not a warrior's weapon", !LivingGear.fits(PIECES.get(12), WARRIOR));
		check("a staff is not a fighter's weapon", !LivingGear.fits(PIECES.get(14), WARRIOR) && LivingGear.fits(PIECES.get(14), new LivingGear.Fit("MAGIC", Set.of(), true, true, 0)));
		check("heavy armor fits a heavy class, light armor does not", LivingGear.fits(PIECES.get(22), WARRIOR) && !LivingGear.fits(PIECES.get(20), WARRIOR));
		check("a one-handed class takes no two-hander", !LivingGear.fits(PIECES.get(15), new LivingGear.Fit("HEAVY", Set.of("SWORD"), false, true, 1)));

		check("a better sword goes in the weapon slot", LivingGear.target(gear("weapon:10"), PIECES.get(11), WARRIOR, 15, GEAR_ITEMS) == LivingGear.Slot.WEAPON);
		check("a worse sword does not", LivingGear.target(gear("weapon:11"), PIECES.get(10), WARRIOR, 15, GEAR_ITEMS) == null);
		check("a D-grade sword waits for level 20", (LivingGear.target(gear("weapon:11"), PIECES.get(13), WARRIOR, 19, GEAR_ITEMS) == null) && (LivingGear.target(gear("weapon:11"), PIECES.get(13), WARRIOR, 20, GEAR_ITEMS) == LivingGear.Slot.WEAPON));
		check("any fitting armor beats armor the class does not use", LivingGear.target(gear("chest:20"), PIECES.get(22), WARRIOR, 15, GEAR_ITEMS) == LivingGear.Slot.CHEST);

		final Map<LivingGear.Slot, Integer> plate = gear("chest:22,legs:23");
		check("a full plate must beat chest and legs together", LivingGear.target(plate, armor(25, "Weak Full", LivingGear.Kind.CHEST, "HEAVY", true, 45, 0, 1), WARRIOR, 15, GEAR_ITEMS) == null);
		final List<LivingGear.Change> full = LivingGear.wearDrops(plate, List.of(PIECES.get(24)), WARRIOR, 15, GEAR_ITEMS);
		check("a better full plate replaces both", (full.size() == 1) && full.get(0).removed().containsAll(List.of(22, 23)) && "chest:24".equals(LivingGear.encode(plate)));
		check("no legs under a full plate", LivingGear.target(plate, PIECES.get(23), WARRIOR, 15, GEAR_ITEMS) == null);

		final Map<LivingGear.Slot, Integer> sword = gear("weapon:10,shield:30");
		final List<LivingGear.Change> twoHands = LivingGear.wearDrops(sword, List.of(PIECES.get(15)), WARRIOR, 15, GEAR_ITEMS);
		check("a two-hander takes the shield off", (twoHands.size() == 1) && twoHands.get(0).removed().equals(List.of(10, 30)) && "weapon:15".equals(LivingGear.encode(sword)));
		check("no shield with a two-hander", LivingGear.target(sword, PIECES.get(30), WARRIOR, 15, GEAR_ITEMS) == null);

		final Map<LivingGear.Slot, Integer> rings = gear("ring1:40");
		LivingGear.wearDrops(rings, List.of(PIECES.get(41), PIECES.get(40)), WARRIOR, 15, GEAR_ITEMS);
		check("a ring fills the empty ring slot and a same ring is no gain", "ring1:40,ring2:41".equals(LivingGear.encode(rings)));

		final Map<LivingGear.Slot, Integer> kit = gear("weapon:10,chest:20,legs:21");
		final List<LivingGear.Change> bought = LivingGear.shop(kit, WARRIOR, 15, 19_000L, List.of( //
			new LivingGear.Offer(PIECES.get(11), 5000L, true), new LivingGear.Offer(PIECES.get(22), 8000L, true), new LivingGear.Offer(PIECES.get(23), 6000L, false), //
			new LivingGear.Offer(PIECES.get(30), 3000L, true), new LivingGear.Offer(PIECES.get(40), 1000L, true), new LivingGear.Offer(PIECES.get(40), 900L, false)), GEAR_ITEMS);
		check("shops weapon, chest, legs in order within budget", "weapon:11,chest:22,legs:23".equals(LivingGear.encode(kit)) && (bought.size() == 3) && bought.get(2).removed().equals(List.of(21)));
		final List<LivingGear.Change> ring = LivingGear.shop(gear("weapon:11"), WARRIOR, 15, 1000L, List.of(new LivingGear.Offer(PIECES.get(40), 1000L, true), new LivingGear.Offer(PIECES.get(40), 1000L, false)), GEAR_ITEMS);
		check("a shop's offer before a player's at the same price", (ring.size() == 1) && ring.get(0).shop());

		check("a small gain is not worth a trip", !LivingGear.good(gear("weapon:10"), LivingGear.Slot.WEAPON, weapon(16, "Sword+", 0, "SWORD", false, false, 21, 0, 1), WARRIOR, GEAR_ITEMS));
		check("a big gain is", LivingGear.good(gear("weapon:10"), LivingGear.Slot.WEAPON, PIECES.get(11), WARRIOR, GEAR_ITEMS));

		check("row text round trip", "weapon:11,chest:22,ring1:40,ring2:41".equals(LivingGear.encode(gear("ring2:41,weapon:11,chest:22,ring1:40"))));
		check("no row text means not converted yet", LivingGear.decode(null) == null);
		check("a damaged pair is skipped", "weapon:11".equals(LivingGear.encode(gear("weapon:11,hat:x,:5,bogus:3"))));
		check("gear behind its level", (LivingGear.behind(gear("weapon:11,chest:22"), 25, GEAR_ITEMS) == 1) && (LivingGear.behind(gear("weapon:13,chest:22"), 15, GEAR_ITEMS) == 0));
		check("tier follows the weapon grade", (LivingGear.tierFor(0, 10) == 0) && (LivingGear.tierFor(1, 10) == 2) && (LivingGear.tierFor(3, 10) == 6) && (LivingGear.tierFor(5, 10) == 8));
		check("starter items sort into slots", "weapon:10,chest:20,legs:21".equals(LivingGear.encode(LivingGear.assign(List.of(20, 10, 21, 99), GEAR_ITEMS))));

		final Random random = new Random(3);
		check("a sure drop always drops, an impossible one never", LivingGear.roll(Map.of(1, 1.0, 2, 0.0), 5, random).equals(List.of(1)) && LivingGear.roll(Map.of(1, 1.0), 0, random).isEmpty());

		final List<DropYield.Drop> drops = List.of(new DropYield.Drop(DropYield.ADENA, 100, 100, 10, 20), new DropYield.Drop(5, 100, 50, 1, 1), new DropYield.Drop(5, 50, 20, 1, 1));
		final Map<Integer, Double> chances = DropYield.chances(drops, List.of(new DropYield.Drop(6, 100, 30, 1, 1)), true, 10, 10, DropYield.Rates.plain(), plainItems());
		check("drop chances per item, merged and with spoils", (chances.size() == 2) && (Math.abs(chances.get(5) - (1 - (0.5 * 0.9))) < 1e-9) && (Math.abs(chances.get(6) - 0.3) < 1e-9));
	}

	private static DropYield.Items plainItems()
	{
		return new DropYield.Items()
		{
			@Override
			public long sellPrice(int itemId)
			{
				return 10L;
			}

			@Override
			public boolean herb(int itemId)
			{
				return false;
			}
		};
	}

	private static final List<LivingGear.Offer> OFFERS = List.of( //
		new LivingGear.Offer(PIECES.get(11), 5000L, true), //
		new LivingGear.Offer(PIECES.get(13), 50_000L, false), //
		new LivingGear.Offer(PIECES.get(22), 8000L, true), //
		new LivingGear.Offer(PIECES.get(23), 6000L, false), //
		new LivingGear.Offer(PIECES.get(24), 20_000L, false), //
		new LivingGear.Offer(PIECES.get(30), 3000L, true), //
		new LivingGear.Offer(PIECES.get(40), 1000L, true), //
		new LivingGear.Offer(PIECES.get(41), 2000L, false), //
		new LivingGear.Offer(PIECES.get(50), 1500L, true));

	private static ColdLife.Context gearContext() throws Exception
	{
		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 60_000L, 60_000L, 0.0, 8, 300_000L, 2500.0);
		final ColdLife.GearShop shop = new ColdLife.GearShop()
		{
			@Override
			public LivingGear.Items items()
			{
				return GEAR_ITEMS;
			}

			@Override
			public LivingGear.Fit fit(int classId)
			{
				return WARRIOR;
			}

			@Override
			public List<LivingGear.Offer> offers(ZoneCatalog.Town town)
			{
				return OFFERS;
			}

			@Override
			public boolean wearable(int itemId)
			{
				return itemId != 50;
			}

			@Override
			public int tierStep()
			{
				return 10;
			}
		};
		return new ColdLife.Context(catalog(), supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7), null, level -> 0L, null, null, shop);
	}

	private static void testGearShopping() throws Exception
	{
		final ColdLife.Context context = gearContext();

		// Starter gear and 30,000 adena: a much better sword it can afford is worth the trip.
		final ColdBot bot = bot(15, "Near Woods", 30_000L, 10L, 2L, 5000);
		bot.setGear("weapon:10,chest:20,legs:21");
		final List<DecisionLog.Event> log = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, log);
		check("a good upgrade it can afford sends it to town", ColdLife.ESCAPING.equals(bot.getActivity()) && log.stream().anyMatch(e -> e.text().contains("Can afford a better weapon: Long Sword for 5,000 adena")));
		runUntil(bot, 0L, context, log, b -> ColdLife.TO_ZONE.equals(b.getActivity()));
		// A spare scroll first (400), then gear with 29,600 less a reserve of max(500, 15 x 250, 10%) = 3,750: 25,850. The
		// sword (5,000), then for the chest the best it can afford, the full plate (20,000, better than plate and legs
		// together). 850 is left, too little for anything else.
		check("buys piece by piece in shop order, the best it can afford", "weapon:11,chest:24".equals(bot.getGear()));
		check("sells what it replaced and pays the rest", bot.getAdena() == (30_000L - 400L - 25_000L + 120L));
		check("a town piece and a traded piece are logged", log.stream().anyMatch(e -> e.text().equals("Bought Long Sword (weapon, no grade, P.Atk 30, +10) in Alpha for 5,000 adena")) && log.stream().anyMatch(e -> e.text().equals("Bought Full Plate (chest, no grade, P.Def 55, +55) from another player for 20,000 adena")));
		check("the old pieces are sold", log.stream().anyMatch(e -> e.text().equals("Sold its old Short Sword, Shirt, Pants for 120 adena")));
		check("the visit is remembered", bot.getShoppedAt() > 0L);

		// Right after a visit, an upgrade alone does not send it back: it hunts a while first.
		final ColdBot again = bot(15, "Near Woods", 30_000L, 10L, 2L, 5000);
		again.setGear("weapon:10,chest:20,legs:21");
		again.setShoppedAt(1_000_000L);
		final List<DecisionLog.Event> soon = new ArrayList<>();
		ColdLife.advance(again, 1_000_000L + 300_000L, 0L, context, soon);
		check("no gear trip right after shopping", ColdLife.HUNTING.equals(again.getActivity()) && soon.stream().anyMatch(e -> e.text().contains("between gear trips")));
		ColdLife.advance(again, 1_000_000L + ColdLife.GEAR_TRIP_GAP_MS, 0L, context, soon);
		check("but goes once it has hunted long enough", ColdLife.ESCAPING.equals(again.getActivity()));
		final ColdBot reloaded = bot(15, "Near Woods", 0L, 0L, 0L, 5000);
		reloaded.setStatsJson(again.getStatsJson());
		check("the visit time survives a restart", reloaded.getShoppedAt() == 1_000_000L);

		// Nothing good within reach: it keeps hunting and says what it is saving for.
		final ColdBot poor = bot(15, "Near Woods", 4000L, 10L, 2L, 5000);
		poor.setGear("weapon:10,chest:22,legs:23");
		final List<DecisionLog.Event> wait = new ArrayList<>();
		ColdLife.advance(poor, 0L, 0L, context, wait);
		check("saves up for a better weapon", ColdLife.HUNTING.equals(poor.getActivity()) && wait.stream().anyMatch(e -> e.text().contains("Wants a better weapon: Long Sword but it costs 5,000 and it can spend 250")));
	}

	private static void testGearDrops() throws Exception
	{
		final ColdLife.Context context = gearContext();
		final ColdBot bot = bot(15, "Near Woods", 0L, 10L, 2L, 5000);
		bot.setGear("weapon:10,chest:22");
		final List<DecisionLog.Event> log = new ArrayList<>();
		final long loot = ColdLife.findDrops(bot, List.of(11, 12, 50, 999), context, log);
		check("wears the better drop that fits its class", "weapon:11,chest:22".equals(bot.getGear()) && log.stream().anyMatch(e -> e.text().equals("Found Long Sword (weapon, no grade, P.Atk 30, +10) while hunting and put it on")));
		check("sells the rest with what it took off", loot == (2000L + 750L + 100L));
		check("lists what it keeps to sell", log.stream().anyMatch(e -> e.text().equals("Picked up Bow, Helmet while hunting, to sell in town")));
		check("a gear tier follows the weapon", bot.getGearTier() == 0);
	}

	private static void testGearShopSafety() throws Exception
	{
		// Plenty of adena and an empty head slot: the Helmet on sale is gear the filter keeps off players, so it is never
		// planned for and never bought, even though everything else it could afford is.
		final ColdLife.Context context = gearContext();
		final ColdBot bot = bot(15, "Near Woods", 60_000L, 10L, 2L, 5000);
		bot.setGear("weapon:10,chest:20,legs:21");
		final List<DecisionLog.Event> log = new ArrayList<>();
		ColdLife.advance(bot, 0L, 0L, context, log);
		runUntil(bot, 0L, context, log, b -> ColdLife.TO_ZONE.equals(b.getActivity()));
		check("the rich bot went shopping", bot.getGear().contains("weapon:11"));
		check("gear the filter rejects is never bought from a shop", !bot.getGear().contains("head:") && log.stream().noneMatch(e -> e.text().contains("Helmet") && !e.text().startsWith("Sold")));

		final ColdBot helmetOnly = bot(15, "Near Woods", 60_000L, 10L, 2L, 5000);
		helmetOnly.setGear("weapon:11,chest:24,shield:30,ring1:41,ring2:41");
		final List<DecisionLog.Event> wait = new ArrayList<>();
		ColdLife.advance(helmetOnly, 0L, 0L, context, wait);
		check("nor does it plan a trip for it", ColdLife.HUNTING.equals(helmetOnly.getActivity()) && wait.stream().noneMatch(e -> e.text().contains("Helmet")));
	}

	private static void testGearTripBudget() throws Exception
	{
		// The same bot saving up for a sword, far from town and close to it. Far away the trip costs its one scroll, so
		// the plan sets 400 aside for a new one; close by it walks, keeps the scroll and has those 400 to spend.
		final ColdLife.Context context = gearContext();
		final ColdBot far = bot(15, "Near Woods", 4800L, 10L, 1L, 5000);
		far.setGear("weapon:10,chest:22,legs:23");
		final List<DecisionLog.Event> farLog = new ArrayList<>();
		ColdLife.advance(far, 0L, 0L, context, farLog);
		final ColdBot near = bot(15, "Near Woods", 4800L, 10L, 1L, 2000);
		near.setGear("weapon:10,chest:22,legs:23");
		final List<DecisionLog.Event> nearLog = new ArrayList<>();
		ColdLife.advance(near, 0L, 0L, context, nearLog);
		check("a trip by scroll sets a new scroll aside", farLog.stream().anyMatch(e -> e.text().endsWith("it can spend 250")));
		check("a walk to a close town keeps the scroll and its price", nearLog.stream().anyMatch(e -> e.text().endsWith("it can spend 650")));
	}

	private static void testDecisionGuardrails() throws Exception
	{
		// Potions: no stock means none for anyone, and light classes drink in step with what they carry.
		check("no potion stock gives light classes none either", (LivingSupplies.potionStockFor(10, 0) == 0) && (LivingSupplies.potionStockFor(9, 1) == 1));
		check("potion use follows the role", (LivingSupplies.potionUseFactor(4) == 1.5) && (LivingSupplies.potionUseFactor(0) == 1.0) && (LivingSupplies.potionUseFactor(10) == 0.5) && (LivingSupplies.potionUseFactor(9) == 0.5));

		// Mystics fire spiritshots of their grade, at their own rate.
		check("mystics are told apart from archers", LivingSupplies.isMystic(10) && !LivingSupplies.isMystic(9) && LivingSupplies.isLight(9));
		check("a mystic buys spiritshots of its grade", (LivingSupplies.shotIdFor(10, 0) == 2509) && (LivingSupplies.shotIdFor(10, 40) == 2511) && (LivingSupplies.shotIdFor(0, 40) == 1464));
		check("its stock follows its own rate", SupplyPlanner.soulshotTarget(true, supply().withSoulshotsPerKill(2.0)) == 720L);
		final GoalPlanner.Plan spirits = GoalPlanner.plan(new GoalPlanner.View(20, 20_000L, 10L, 10L, 2L, 2, true, "Near Woods", 10, 20, null), prices(), supply().withSoulshotsPerKill(2.0), true);
		check("a mystic's plan says spiritshots", GoalPlanner.describe(spirits).contains("Spiritshots low"));

		// A shot minimum above half the stock shrinks to half, so a small stock still restocks.
		final SupplyPlanner.Params small = new SupplyPlanner.Params(10, 0.25, 1, 0.2, 2, 500L, 250L, 0.10, 12.0, 6.0, 50_000L, 10, 200L);
		check("the shot minimum is at most half the stock", SupplyPlanner.soulshotMinPurchase(true, small) == 36L);
		check("a small stock still buys shots", SupplyPlanner.shop(10, 100_000L, 2L, 10L, 0L, 1, true, prices(), small).soulshots() == 72L);

		// Zones: a bot stays in a zone at its limit, and leaves only once it is clearly over.
		final ZoneCatalog catalog = catalog();
		final ZoneCatalog.Town alpha = catalog.town("Alpha");
		final Map<String, Integer> atLimit = new HashMap<>();
		atLimit.put("Near Woods", 8);
		check("keeps a zone at its limit", "Near Woods".equals(ZoneChooser.choose(new ZoneChooser.Situation(16, "Elf", catalog.zone("Near Woods"), alpha, 100_000L, atLimit, 8), catalog, null).zone().name()));
		atLimit.put("Near Woods", 10);
		check("leaves one a quarter over", "Far Hills".equals(ZoneChooser.choose(new ZoneChooser.Situation(16, "Elf", catalog.zone("Near Woods"), alpha, 100_000L, atLimit, 8), catalog, null).zone().name()));
		final ZoneChooser.Choice walk = ZoneChooser.walkFallback(new ZoneChooser.Situation(25, "Elf", null, alpha, 0L, null, 8), catalog);
		check("the broke walk goes to the nearest fitting zone", (walk != null) && "Far Hills".equals(walk.zone().name()) && (walk.way() == ZoneChooser.Way.WALK) && (walk.fee() == 0L));
		final Map<String, Integer> fullHills = new HashMap<>();
		fullHills.put("Far Hills", 8);
		check("and prefers one that is not full", "Beyond".equals(ZoneChooser.walkFallback(new ZoneChooser.Situation(25, "Elf", null, alpha, 0L, fullHills, 8), catalog).zone().name()));
		check("no fitting zone, no walk", ZoneChooser.walkFallback(new ZoneChooser.Situation(31, "Elf", null, alpha, 0L, null, 8), catalog) == null);

		// A bot that picks a zone is counted there at once, so the next one leaving in the same tick sees it.
		final ColdLife.Context context = context(0);
		context.occupancy().put("Near Woods", 3);
		final ColdBot mover = bot(25, "Near Woods", 600L, 10L, 2L, 0);
		mover.setActivity(ColdLife.IN_TOWN);
		mover.setTown("Alpha");
		mover.setGearTier(2);
		mover.setLeg(TravelLeg.stay(new Point(0, 0, 0), 0L, 0L));
		ColdLife.advance(mover, 1L, 1L, context, new ArrayList<>());
		check("a zone pick moves the head count at once", ColdLife.TO_ZONE.equals(mover.getActivity()) && "Near Woods".equals(mover.getZone()) && (context.occupancy().get("Near Woods") == 3));
		final ColdBot changer = bot(30, "Near Woods", 100_000L, 10L, 2L, 0);
		changer.setActivity(ColdLife.IN_TOWN);
		changer.setTown("Alpha");
		changer.setGearTier(3);
		changer.setLeg(TravelLeg.stay(new Point(0, 0, 0), 0L, 0L));
		ColdLife.advance(changer, 1L, 1L, context, new ArrayList<>());
		check("moving on counts it out of the old zone and into the new one", !"Near Woods".equals(changer.getZone()) && (context.occupancy().get("Near Woods") == 2) && (context.occupancy().get(changer.getZone()) == 1));

		// A trickle of loot is still credited but not logged.
		final ColdBot crumbs = bot(15, "Near Woods", 20_000L, 0L, 1L, 5000);
		crumbs.setLoot(40L);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(crumbs, 0L, 0L, context, events);
		ColdLife.advance(crumbs, 20_000L, 20_000L, context, events);
		final long errandsEnd = crumbs.getLeg().endAt();
		ColdLife.advance(crumbs, errandsEnd, errandsEnd - 20_000L, context, events);
		check("loot worth a few adena is sold without a log line", (crumbs.getLoot() == 0L) && events.stream().noneMatch(e -> e.text().startsWith("Sold its loot")));
	}

	private static void testGearGuardrails() throws Exception
	{
		// Only upgrades worth it are bought, and an offer with no price is never taken.
		final Map<LivingGear.Slot, Integer> kit = gear("weapon:10");
		final LivingGear.Piece slightly = weapon(16, "Sword+", 0, "SWORD", false, false, 21, 0, 1);
		check("a barely better piece is not bought", LivingGear.shop(kit, WARRIOR, 15, 100_000L, List.of(new LivingGear.Offer(slightly, 100L, true)), GEAR_ITEMS).isEmpty() && "weapon:10".equals(LivingGear.encode(kit)));
		check("a free offer is not taken", LivingGear.shop(kit, WARRIOR, 15, 100_000L, List.of(new LivingGear.Offer(PIECES.get(11), 0L, true)), GEAR_ITEMS).isEmpty());
		check("a slot bought this visit is left alone", LivingGear.shop(kit, WARRIOR, 15, 100_000L, List.of(new LivingGear.Offer(PIECES.get(11), 5000L, true)), GEAR_ITEMS, Set.of(LivingGear.Slot.WEAPON)).isEmpty());

		// An empty ring slot and only the cheap ring within reach: by scroll the trip costs more than the ring, so it waits
		// for a visit made for something else; walking to a close town it is worth it.
		final ColdLife.Context context = gearContext();
		final ColdBot far = bot(15, "Near Woods", 5650L, 10L, 2L, 5000);
		far.setGear("weapon:11,chest:24,shield:30,ring1:41");
		final List<DecisionLog.Event> farLog = new ArrayList<>();
		ColdLife.advance(far, 0L, 0L, context, farLog);
		check("no trip of its own for a cheap piece in an empty slot", ColdLife.HUNTING.equals(far.getActivity()) && farLog.stream().noneMatch(e -> e.text().contains("Ring")));
		final ColdBot near = bot(15, "Near Woods", 5650L, 10L, 2L, 2000);
		near.setGear("weapon:11,chest:24,shield:30,ring1:41");
		final List<DecisionLog.Event> nearLog = new ArrayList<>();
		ColdLife.advance(near, 0L, 0L, context, nearLog);
		check("but one worth more than the trip is", nearLog.stream().anyMatch(e -> e.text().contains("Can afford a better")));
	}

	// FPC-277. Isle sits on an island; Shore is on the mainland but closer to Isle in a straight line than to Main; Rock is
	// an island with no town, reached by Main's gatekeeper.
	private static final String ISLAND_XML = "<zones>" //
		+ "<town name=\"Isle\" x=\"0\" y=\"0\" z=\"0\"><gatekeeper npcId=\"1\" x=\"100\" y=\"0\" z=\"0\"/><grocer npcId=\"2\" x=\"0\" y=\"100\" z=\"0\"/><route town=\"Main\" fee=\"500\"/></town>" //
		+ "<town name=\"Main\" x=\"40000\" y=\"0\" z=\"0\"><gatekeeper npcId=\"3\" x=\"40100\" y=\"0\" z=\"0\"/><grocer npcId=\"4\" x=\"40000\" y=\"100\" z=\"0\"/><route town=\"Isle\" fee=\"500\"/></town>" //
		+ "<island name=\"The Isle\" minX=\"-10000\" maxX=\"10000\" minY=\"-10000\" maxY=\"10000\"/>" //
		+ "<island name=\"The Rock\" minX=\"60000\" maxX=\"70000\" minY=\"-5000\" maxY=\"5000\"/>" //
		+ "<zone name=\"Isle Newbies\" minLevel=\"1\" maxLevel=\"10\" starterRace=\"Human\"><spot x=\"2000\" y=\"0\" z=\"0\"/></zone>" //
		+ "<zone name=\"Shore\" minLevel=\"15\" maxLevel=\"25\"><teleport town=\"Main\" x=\"14000\" y=\"0\" z=\"0\" fee=\"100\"/><spot x=\"14000\" y=\"0\" z=\"0\"/></zone>" //
		+ "<zone name=\"Rock\" minLevel=\"30\" maxLevel=\"40\"><teleport town=\"Main\" x=\"65000\" y=\"0\" z=\"0\" fee=\"300\"/><spot x=\"65000\" y=\"0\" z=\"0\"/></zone>" //
		+ "</zones>";

	private static void testIslands() throws Exception
	{
		final ZoneCatalog catalog = ZoneCatalog.parse(ISLAND_XML);
		check("islands parse", (catalog.islands().size() == 2) && "The Isle".equals(catalog.islandAt(new Point(0, 0, 0)).name()) && (catalog.islandAt(new Point(14000, 0, 0)) == null));
		check("the mainland and an island are different land", !catalog.sameLand(new Point(14000, 0, 0), new Point(0, 0, 0)) && catalog.sameLand(new Point(14000, 0, 0), new Point(40000, 0, 0)));
		check("the nearest town is the one on the same land, not across the water", "Main".equals(catalog.nearestShoppingTown(new Point(14000, 0, 0)).name()));
		check("on an island without a town the nearest town across the water is still given", catalog.nearestShoppingTown(new Point(65000, 0, 0)) != null);
		check("never on foot to newbie grounds across the water", ZoneChooser.route(catalog.zone("Isle Newbies"), catalog.town("Main"), catalog).way() == ZoneChooser.Way.GATEKEEPER);
		check("the newbie grounds trip pays the gatekeeper to the island's town", (ZoneChooser.route(catalog.zone("Isle Newbies"), catalog.town("Main"), catalog).fee() == 500L) && ZoneChooser.route(catalog.zone("Isle Newbies"), catalog.town("Main"), catalog).arrival().equals(new Point(0, 0, 0)));
		check("newbie grounds on the same island are walked to", ZoneChooser.route(catalog.zone("Isle Newbies"), catalog.town("Isle"), catalog).way() == ZoneChooser.Way.WALK);

		final ColdLife.Params travel = new ColdLife.Params(100.0, 20_000L, 10_000L, 0, 60_000L, 60_000L, 4.0, 8, 300_000L, 2500.0);
		final ColdLife.Context context = new ColdLife.Context(catalog, supply(), travel, (level, tier) -> prices(), new HashMap<>(), new Random(7));

		final ColdBot shore = bot(20, "Shore", 20_000L, 0L, 0L, 14000);
		final List<DecisionLog.Event> events = new ArrayList<>();
		ColdLife.advance(shore, 0L, 0L, context, events);
		check("no scroll on the shore: walks to the town on its land", ColdLife.WALKING_TO_TOWN.equals(shore.getActivity()) && "Main".equals(shore.getTown()) && shore.getLeg().to().equals(new Point(40000, 100, 0)));

		final ColdBot rock = bot(35, "Rock", 20_000L, 0L, 0L, 65000);
		events.clear();
		ColdLife.advance(rock, 0L, 0L, context, events);
		check("no scroll on a townless island: takes the gatekeeper's way back, no swim", ColdLife.TO_TOWN.equals(rock.getActivity()) && "Main".equals(rock.getTown()) && (rock.getAdena() == 19_700L));
		check("the way back is logged", events.stream().anyMatch(e -> e.text().startsWith("Has no Scroll of Escape and water lies between it and Main")));
		ColdLife.advance(rock, 20_000L, 20_000L, context, events);
		check("it lands in the town and shops", ColdLife.IN_TOWN.equals(rock.getActivity()) && (rock.getX() >= 39000));

		final ColdBot scroll = bot(35, "Rock", 20_000L, 0L, 1L, 65000);
		ColdLife.advance(scroll, 0L, 0L, context, new ArrayList<>());
		check("with a scroll it reads it instead", ColdLife.ESCAPING.equals(scroll.getActivity()) && (scroll.getEscapes() == 0L));
	}

	/**
	 * A river runs north to south at x 1000 to 1400 (water), with a bridge at y 3000 to 3200 over it. Walking from x 0 to
	 * x 2400 at y 0 must take the bridge, not the water.
	 */
	private static final LivingRoute.Terrain RIVER = new LivingRoute.Terrain()
	{
		@Override
		public boolean known(int x, int y)
		{
			return (Math.abs(x) < 20000) && (Math.abs(y) < 20000);
		}

		@Override
		public int height(int x, int y, int z)
		{
			return (river(x) && !bridge(y)) ? -200 : 0;
		}

		@Override
		public boolean water(int x, int y, int z)
		{
			return river(x) && (z < -50);
		}

		@Override
		public boolean canWalk(int x, int y, int z, int tx, int ty, int tz)
		{
			return true; // the river bed is walkable ground, as it is in the geodata
		}

		private boolean river(int x)
		{
			return (x >= 1000) && (x <= 1400);
		}

		private boolean bridge(int y)
		{
			return (y >= 3000) && (y <= 3200);
		}
	};

	private static void testRouteOverBridge()
	{
		final Point from = new Point(0, 0, 0);
		final Point to = new Point(2400, 0, 0);
		final List<Point> route = LivingRoute.plan(RIVER, from, to);
		check("a route is found", (route != null) && !route.isEmpty() && route.get(route.size() - 1).equals(to));
		boolean wet = false;
		boolean crossed = false;
		Point previous = from;
		for (Point point : (route == null) ? List.<Point> of() : route)
		{
			wet |= RIVER.water(point.x(), point.y(), RIVER.height(point.x(), point.y(), point.z()));
			// Every straight stretch stays dry, and the one that crosses the river does so on the bridge.
			for (int s = 1; s <= 50; s++)
			{
				final int x = previous.x() + (((point.x() - previous.x()) * s) / 50);
				final int y = previous.y() + (((point.y() - previous.y()) * s) / 50);
				wet |= RIVER.water(x, y, RIVER.height(x, y, 0));
				crossed |= (x >= 1000) && (x <= 1400) && (y >= 3000) && (y <= 3200);
			}
			previous = point;
		}
		check("the route never goes into the river", !wet);
		check("the route crosses on the bridge", crossed);

		final List<Point> open = LivingRoute.plan(RIVER, new Point(0, 5000, 0), new Point(0, 9000, 0));
		check("open ground is one straight stretch per leg", (open != null) && (open.size() <= 3));
		check("no terrain data gives no plan", LivingRoute.plan(RIVER, new Point(30000, 0, 0), new Point(31000, 0, 0)) == null);
		final List<Point> out = LivingRoute.plan(RIVER, new Point(1200, 0, -200), new Point(0, 0, 0));
		check("a bot already in the water finds its way out", (out != null) && out.get(out.size() - 1).equals(new Point(0, 0, 0)));
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
