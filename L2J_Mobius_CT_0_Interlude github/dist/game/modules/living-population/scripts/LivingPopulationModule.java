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
package modules.livingpopulation;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.livingpop.LivingPopulationConfig;
import org.l2jmobius.gameserver.livingpop.LivingPopulationManager;
import org.l2jmobius.gameserver.livingpop.TravelConfig;
import org.l2jmobius.gameserver.livingpop.ZoneCombat;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * The thin entry point for the Living Population module (architecture B: the heavy simulation lives in the core
 * {@code org.l2jmobius.gameserver.livingpop} package, gated by this switch). On enable it reads {@code config/module.ini},
 * builds an immutable {@link LivingPopulationConfig}, and hands it to {@link LivingPopulationManager}. When the switch is
 * off, or the directory is removed, the manager is never started and the server is stock. It also registers a
 * GM-only {@code .livingpop} voiced command that prints a one-line population status.
 */
public class LivingPopulationModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", true))
		{
			return; // Switch off: start nothing, behave as stock.
		}

		final LivingPopulationConfig config = new LivingPopulationConfig( //
			true, //
			Math.max(0, context.config().getInt("PopulationSize", 100)), //
			Math.max(1000L, context.config().getLong("ColdResolveIntervalSeconds", 30L) * 1000L), //
			Math.max(1, context.config().getInt("ColdResolveBatch", 64)), //
			Math.max(0.0, context.config().getDouble("ColdExpPerMobLevel", 13.0)), //
			Math.max(0.0, context.config().getDouble("ColdKillsPerMinute", 12.0)), //
			Math.max(1, context.config().getInt("MaxLevel", 80)), //
			context.config().getBoolean("DirectorEnabled", true), //
			Math.max(0, context.config().getInt("DirectorBandRadius", 2)), //
			Math.max(1.0, context.config().getDouble("DirectorMaxCatchUp", 1.35)), //
			Math.max(0.1, Math.min(1.0, context.config().getDouble("DirectorSlowdown", 0.85))), // 0 would stop leveling
			Math.max(0.0, context.config().getDouble("DirectorCatchUpSlope", 0.06)), //
			context.config().getBoolean("HandoffEnabled", true), //
			Math.max(1000L, context.config().getLong("HandoffCheckSeconds", 5L) * 1000L), //
			Math.max(0.0, context.config().getDouble("HandoffActivationRadius", 3000.0)), //
			Math.max(0.0, context.config().getDouble("HandoffDeactivationRadius", 4000.0)), //
			Math.max(0L, context.config().getLong("HandoffCooldownGraceSeconds", 30L) * 1000L), //
			Math.max(0, context.config().getInt("HandoffMaxHotBots", 0)), // 0 = same as PopulationSize
			context.config().getBoolean("EconomyEnabled", true), //
			Math.max(0.0, context.config().getDouble("AdenaPerMobLevel", 5.0)), //
			Math.max(1, context.config().getInt("SoulshotMilestoneLevel", 6)), //
			Math.max(0L, context.config().getLong("SoulshotMilestoneGrant", 1000L)), //
			Math.max(0.0, context.config().getDouble("SoulshotsPerKill", 6.0)), //
			Math.max(0L, context.config().getLong("SoulshotRestockThreshold", 500L)), //
			Math.max(0L, context.config().getLong("SoulshotRestockBatch", 1000L)), //
			Math.max(0, context.config().getInt("SoulshotCost", 12)), //
			Math.max(0L, context.config().getLong("PotionRestockThreshold", 100L)), //
			Math.max(0L, context.config().getLong("PotionRestockBatch", 500L)), //
			Math.max(0, context.config().getInt("PotionCost", 60)), //
			Math.max(0, context.config().getInt("GearTierLevelStep", 10)), //
			Math.max(0L, context.config().getLong("GearUpgradeCost", 50000L)), //
			Math.max(1000L, context.config().getLong("SnapshotIntervalSeconds", 5L) * 1000L), //
			context.config().getString("SnapshotFile", "log/LivingPopulation.json"), //
			Math.max(0, context.config().getInt("BirthBatch", 10)), //
			Math.max(60_000L, context.config().getLong("BirthIntervalMinutes", 20L) * 60_000L), //
			Math.max(0, context.config().getInt("DirectorLevelGoal", 0)));

		// Phase 5: travel between zones and towns, shopping only in town, player-like supplies.
		final TravelConfig travel = new TravelConfig( //
			context.config().getBoolean("TravelEnabled", true), //
			context.config().getString("ZonesFile", "modules/living-population/data/zones.xml"), //
			Math.max(0, context.config().getInt("PotionStock", 30)), //
			Math.max(0.0, Math.min(1.0, context.config().getDouble("PotionRestockFraction", 0.25))), //
			Math.max(0, context.config().getInt("SoulshotStockMinutes", 30)), //
			Math.max(0.0, Math.min(1.0, context.config().getDouble("SoulshotRestockFraction", 0.2))), //
			Math.max(0L, context.config().getLong("SoulshotMinPurchase", 200L)), //
			Math.max(0.0, context.config().getDouble("SpiritshotsPerKill", 2.0)), //
			Math.max(0, context.config().getInt("EscapeStock", 2)), //
			Math.max(0L, context.config().getLong("ReserveFloor", 500L)), //
			Math.max(0L, context.config().getLong("ReservePerLevel", 250L)), //
			Math.max(0.0, Math.min(0.5, context.config().getDouble("ReserveFraction", 0.10))), // above half it would hardly spend
			Math.max(1.0, context.config().getDouble("MoveSpeed", 120.0)), //
			Math.max(0L, context.config().getLong("EscapeCastSeconds", 20L) * 1000L), //
			Math.max(0L, context.config().getLong("ErrandStopSeconds", 15L) * 1000L), //
			Math.max(0, Math.min(100, context.config().getInt("AfkChancePercent", 15))), //
			Math.max(0L, context.config().getLong("AfkMinMinutes", 2L) * 60_000L), //
			Math.max(0L, context.config().getLong("AfkMaxMinutes", 10L) * 60_000L), //
			Math.max(0.0, context.config().getDouble("ColdPotionsPerHour", 4.0)), //
			Math.max(0, context.config().getInt("ZoneCapacity", 16)), //
			Math.max(30_000L, context.config().getLong("RetrySeconds", 300L) * 1000L), // a shorter wait reruns errands nonstop
			Math.max(0.0, context.config().getDouble("WalkToTownWithin", 2500.0)), //
			context.config().getBoolean("DropIncome", true), //
			Math.max(0.0, context.config().getDouble("ColdDeathsPerHour", 0.3)), //
			Math.max(0L, context.config().getLong("DeathRecoverSeconds", 90L) * 1000L), //
			Math.max(0L, context.config().getLong("RestEveryMinutes", 10L) * 60_000L), //
			Math.max(0L, context.config().getLong("RestSeconds", 60L) * 1000L), //
			Math.max(0L, context.config().getLong("AvoidZoneMinutes", 60L) * 60_000L), //
			context.config().getBoolean("ClassChanges", true), //
			new long[]
			{
				Math.max(0L, context.config().getLong("ClassQuestMinutes1", 60L) * 60_000L),
				Math.max(0L, context.config().getLong("ClassQuestMinutes2", 180L) * 60_000L),
				Math.max(0L, context.config().getLong("ClassQuestMinutes3", 240L) * 60_000L)
			}, //
			context.config().getBoolean("SkillTraining", true), //
			context.config().getBoolean("GearSlots", true), //
			context.config().getBoolean("GearTrade", true));

		// Zone combat: each cold bot's kill and death rates come from its gear and skills against its zone's monsters.
		LivingPopulationManager.getInstance().setZoneCombat(new ZoneCombat.Params( //
			context.config().getBoolean("ZoneCombat", true), //
			config.killsPerMinute(), //
			Math.max(0.05, Math.min(0.95, context.config().getDouble("ColdFightShare", 0.5))), //
			Math.max(0.0, Math.min(1.0, context.config().getDouble("SkillDamageFloor", 0.5))), //
			Math.max(0.1, context.config().getDouble("MinKillsPerMinute", 3.0)), //
			Math.max(0.1, context.config().getDouble("MaxKillsPerMinute", 24.0)), //
			Math.max(0.01, context.config().getDouble("MinDeathFactor", 0.25)), //
			Math.max(0.01, context.config().getDouble("MaxDeathFactor", 4.0)), //
			config.gearTierLevelStep()), //
			context.config().getString("ZoneCombatFile", "modules/living-population/data/zone_combat.tsv"), //
			context.config().getBoolean("ExpLevelGap", true), //
			// Buffed leveling: emulate buffers keeping the bots buffed. Off gives every role a share of 0.
			context.config().getBoolean("BuffedLeveling", false)
				? new double[]
				{
					Math.max(0.0, Math.min(1.0, context.config().getDouble("BuffShareTank", 1.0))),
					Math.max(0.0, Math.min(1.0, context.config().getDouble("BuffShareMelee", 1.0))),
					Math.max(0.0, Math.min(1.0, context.config().getDouble("BuffShareBow", 1.0))),
					Math.max(0.0, Math.min(1.0, context.config().getDouble("BuffShareMage", 1.0)))
				}
				: new double[4]);
		// Shots: a bot without its soulshots (spiritshots for a mystic) does less damage. 1.0 turns a shot's bonus off.
		final boolean shotsMatter = context.config().getBoolean("ShotsMatter", true);
		LivingPopulationManager.getInstance().setShotDamage( //
			shotsMatter ? Math.max(1.0, context.config().getDouble("SoulshotDamage", 2.0)) : 1.0, //
			shotsMatter ? Math.max(1.0, context.config().getDouble("SpiritshotDamage", 1.41)) : 1.0);
		LivingPopulationManager.getInstance().start(config, travel);
		context.handlers().registerVoicedCommand(new LivingPopulationStatusCommand());
		context.logging().info("Living Population module enabled: " + LivingPopulationManager.getInstance().statusText());
	}

	private static class LivingPopulationStatusCommand implements IVoicedCommandHandler
	{
		private static final String[] COMMANDS =
		{
			"livingpop"
		};

		@Override
		public boolean onCommand(String command, Player player, String params)
		{
			if (player == null)
			{
				return false;
			}

			if (!player.isGM())
			{
				player.sendMessage("This command is for game masters only.");
				return false;
			}

			// ".livingpop" alone gives the population overview; ".livingpop <name>" shows one bot and its recent decisions.
			final String name = (params == null) ? "" : params.trim();
			if (name.isEmpty())
			{
				player.sendMessage(LivingPopulationManager.getInstance().statusText());
				return true;
			}
			for (String line : LivingPopulationManager.getInstance().botDetailLines(name, 8))
			{
				player.sendMessage(line);
			}
			return true;
		}

		@Override
		public String[] getCommandList()
		{
			return COMMANDS;
		}
	}
}
