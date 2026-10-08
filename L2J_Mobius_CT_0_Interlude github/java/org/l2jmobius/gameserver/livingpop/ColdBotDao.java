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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * Persistence for cold bots against the module-owned {@code living_population_bots} table.
 *
 * <p>The table is created by the module's own install script when the module is enabled and is never touched by stock
 * code, so the simulation's storage is fully separable from the server's. This is the only place the cold layer talks to
 * the database; it uses the shared connection pool and small, explicit statements.
 */
public class ColdBotDao
{
	private static final Logger LOGGER = Logger.getLogger(ColdBotDao.class.getName());

	private static final String TABLE = "living_population_bots";
	// The account PhantomManager creates Living Population characters on.
	private static final String LIVINGPOP_ACCOUNT = "living_population";

	private static final String SELECT_ALL = "SELECT id,name,race,class_id,level,level_exp,sp,adena,region,x,y,z,activity,phase,created_at,updated_at,last_resolved_at,next_resolve_at,hot_lock,char_id,soulshots,potions,gear_tier,goal,reward_claimed,stats_json,zone,town,escapes,leg,loot,deaths,risk,quest,skills,gear FROM " + TABLE;
	private static final String INSERT = "INSERT INTO " + TABLE + " (name,race,class_id,level,level_exp,sp,adena,region,x,y,z,activity,phase,created_at,updated_at,last_resolved_at,next_resolve_at,hot_lock,char_id,soulshots,potions,gear_tier,goal,reward_claimed,stats_json,zone,town,escapes,leg,loot,deaths,risk,quest,skills,gear) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
	private static final String UPDATE = "UPDATE " + TABLE + " SET class_id=?,level=?,level_exp=?,sp=?,adena=?,region=?,x=?,y=?,z=?,activity=?,phase=?,updated_at=?,last_resolved_at=?,next_resolve_at=?,hot_lock=?,char_id=?,soulshots=?,potions=?,gear_tier=?,goal=?,reward_claimed=?,stats_json=?,zone=?,town=?,escapes=?,leg=?,loot=?,deaths=?,risk=?,quest=?,skills=?,gear=? WHERE id=?";

	// Columns added after the table was first created, with their definitions. install.sql creates new tables with all of
	// them; CREATE TABLE IF NOT EXISTS never alters an existing table, so ensureColumns() adds any that are missing.
	private static final String[][] ADDED_COLUMNS =
	{
		{
			"char_id",
			"BIGINT NOT NULL DEFAULT 0"
		},
		{
			"soulshots",
			"BIGINT NOT NULL DEFAULT 0"
		},
		{
			"potions",
			"BIGINT NOT NULL DEFAULT 0"
		},
		{
			"gear_tier",
			"INT NOT NULL DEFAULT 0"
		},
		{
			"goal",
			"VARCHAR(16) NOT NULL DEFAULT 'hunting'"
		},
		{
			"reward_claimed",
			"TINYINT(1) NOT NULL DEFAULT 0"
		},
		{
			"stats_json",
			"TEXT DEFAULT NULL"
		},
		{
			"zone",
			"VARCHAR(64) DEFAULT NULL"
		},
		{
			"town",
			"VARCHAR(64) DEFAULT NULL"
		},
		{
			"escapes",
			"BIGINT NOT NULL DEFAULT 0"
		},
		{
			"leg",
			"VARCHAR(200) DEFAULT NULL"
		},
		{
			"loot",
			"BIGINT NOT NULL DEFAULT 0"
		},
		{
			"deaths",
			"INT NOT NULL DEFAULT 0"
		},
		{
			"risk",
			"VARCHAR(255) DEFAULT NULL"
		},
		{
			"quest",
			"VARCHAR(64) DEFAULT NULL"
		},
		{
			"skills",
			"TEXT DEFAULT NULL"
		},
		{
			"gear",
			"VARCHAR(255) DEFAULT NULL"
		},
	};

	/**
	 * Adds any column a newer version of the module needs to a table created by an older one, so testers never run a
	 * manual migration. Safe to call on every start: it only adds what is missing and never drops or changes data.
	 */
	public void ensureColumns()
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			final Set<String> existing = new HashSet<>();
			try (PreparedStatement ps = con.prepareStatement("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"))
			{
				ps.setString(1, TABLE);
				try (ResultSet rs = ps.executeQuery())
				{
					while (rs.next())
					{
						existing.add(rs.getString(1).toLowerCase(Locale.ROOT));
					}
				}
			}
			if (existing.isEmpty())
			{
				return; // no table yet (install.sql creates it complete) or no permission to read the schema
			}
			for (String[] column : ADDED_COLUMNS)
			{
				if (!existing.contains(column[0]))
				{
					try (PreparedStatement ps = con.prepareStatement("ALTER TABLE " + TABLE + " ADD COLUMN " + column[0] + " " + column[1]))
					{
						ps.executeUpdate();
					}
					LOGGER.info("LivingPopulation: added column " + column[0] + " to " + TABLE + ".");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not check or add table columns: " + e.getMessage(), e);
		}
	}

	/**
	 * Loads every cold bot row.
	 * @return the bots (an empty list when the table is empty), or null when the read fails (the failure is logged), so
	 *         a failed read is never mistaken for an empty population
	 */
	public List<ColdBot> loadAll()
	{
		final List<ColdBot> bots = new ArrayList<>();
		// Use a PreparedStatement, not a plain Statement.executeQuery(String): the latter trips MySQL
		// Connector/J's "cannot issue statements that do not produce result sets" pre-check when the server
		// is MariaDB, even for a valid SELECT. The prepared path returns the result set correctly and is the
		// dominant read idiom in this codebase (as insert/update here already use).
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_ALL);
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				final ColdBot bot = new ColdBot();
				bot.setId(rs.getLong("id"));
				bot.setName(rs.getString("name"));
				bot.setRace(rs.getString("race"));
				bot.setClassId(rs.getInt("class_id"));
				bot.setLevel(rs.getInt("level"));
				bot.setExpIntoLevel(rs.getLong("level_exp"));
				bot.setSp(rs.getLong("sp"));
				bot.setAdena(rs.getLong("adena"));
				bot.setRegion(rs.getString("region"));
				bot.setX(rs.getInt("x"));
				bot.setY(rs.getInt("y"));
				bot.setZ(rs.getInt("z"));
				bot.setActivity(rs.getString("activity"));
				bot.setPhase(rs.getString("phase"));
				bot.setCreatedAt(rs.getLong("created_at"));
				bot.setUpdatedAt(rs.getLong("updated_at"));
				bot.setLastResolvedAt(rs.getLong("last_resolved_at"));
				bot.setNextResolveAt(rs.getLong("next_resolve_at"));
				bot.setHotLock(rs.getBoolean("hot_lock"));
				bot.setCharId(rs.getLong("char_id"));
				bot.setSoulshots(rs.getLong("soulshots"));
				bot.setPotions(rs.getLong("potions"));
				bot.setGearTier(rs.getInt("gear_tier"));
				bot.setGoal(rs.getString("goal"));
				bot.setRewardClaimed(rs.getBoolean("reward_claimed"));
				bot.setStatsJson(rs.getString("stats_json"));
				bot.setZone(rs.getString("zone"));
				bot.setTown(rs.getString("town"));
				bot.setEscapes(rs.getLong("escapes"));
				bot.setLeg(TravelLeg.decode(rs.getString("leg")));
				bot.setLoot(rs.getLong("loot"));
				bot.setDeaths(rs.getInt("deaths"));
				bot.setRisk(ColdRisk.State.decode(rs.getString("risk")));
				bot.setQuest(ClassPath.Quest.decode(rs.getString("quest")));
				bot.setSkills(rs.getString("skills"));
				bot.setGear(rs.getString("gear"));
				bots.add(bot);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "LivingPopulation: failed to load cold bots: " + e.getMessage(), e);
			return null;
		}
		return bots;
	}

	/**
	 * Inserts a new cold bot and assigns its generated id back onto the object.
	 * @param bot the bot to insert
	 * @return true on success
	 */
	public boolean insert(ColdBot bot)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS))
		{
			int i = 1;
			ps.setString(i++, bot.getName());
			ps.setString(i++, bot.getRace());
			ps.setInt(i++, bot.getClassId());
			ps.setInt(i++, bot.getLevel());
			ps.setLong(i++, bot.getExpIntoLevel());
			ps.setLong(i++, bot.getSp());
			ps.setLong(i++, bot.getAdena());
			ps.setString(i++, bot.getRegion());
			ps.setInt(i++, bot.getX());
			ps.setInt(i++, bot.getY());
			ps.setInt(i++, bot.getZ());
			ps.setString(i++, bot.getActivity());
			ps.setString(i++, bot.getPhase());
			ps.setLong(i++, bot.getCreatedAt());
			ps.setLong(i++, bot.getUpdatedAt());
			ps.setLong(i++, bot.getLastResolvedAt());
			ps.setLong(i++, bot.getNextResolveAt());
			ps.setBoolean(i++, bot.isHotLock());
			ps.setLong(i++, bot.getCharId());
			ps.setLong(i++, bot.getSoulshots());
			ps.setLong(i++, bot.getPotions());
			ps.setInt(i++, bot.getGearTier());
			ps.setString(i++, bot.getGoal() == null ? "hunting" : bot.getGoal());
			ps.setBoolean(i++, bot.isRewardClaimed());
			ps.setString(i++, bot.getStatsJson());
			ps.setString(i++, bot.getZone());
			ps.setString(i++, bot.getTown());
			ps.setLong(i++, bot.getEscapes());
			ps.setString(i++, (bot.getLeg() == null) ? null : bot.getLeg().encode());
			ps.setLong(i++, bot.getLoot());
			ps.setInt(i++, bot.getDeaths());
			ps.setString(i++, (bot.getRisk() == ColdRisk.State.EMPTY) ? null : bot.getRisk().encode());
			ps.setString(i++, (bot.getQuest() == null) ? null : bot.getQuest().encode());
			ps.setString(i++, bot.getSkills());
			ps.setString(i++, bot.getGear());
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys())
			{
				if (keys.next())
				{
					bot.setId(keys.getLong(1));
				}
			}
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: failed to insert cold bot '" + bot.getName() + "': " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Deletes a bot's row for good.
	 * @param bot the bot
	 * @return true on success
	 */
	public boolean delete(ColdBot bot)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("DELETE FROM " + TABLE + " WHERE id=?"))
		{
			ps.setLong(1, bot.getId());
			ps.executeUpdate();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not delete bot '" + bot.getName() + "': " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * @param charId a character id
	 * @return whether that character exists and belongs to the Living Population account (only those may be deleted
	 *         with their bot)
	 */
	public boolean isLivingCharacter(long charId)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT account_name FROM characters WHERE charId=?"))
		{
			ps.setLong(1, charId);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() && LIVINGPOP_ACCOUNT.equals(rs.getString(1));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not look up character " + charId + ": " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Gives a bot a new name: its character first (when it has one), then its row, so the two never disagree. The
	 * character must not be in the world. Only a character on the Living Population account is touched.
	 * @param bot the bot, still carrying its old name
	 * @param name the new name
	 * @return true when both were renamed
	 */
	public boolean rename(ColdBot bot, String name)
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			if (bot.getCharId() > 0)
			{
				try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET char_name=? WHERE charId=? AND account_name=?"))
				{
					ps.setString(1, name);
					ps.setLong(2, bot.getCharId());
					ps.setString(3, LIVINGPOP_ACCOUNT);
					ps.executeUpdate();
				}
			}
			try (PreparedStatement ps = con.prepareStatement("UPDATE " + TABLE + " SET name=? WHERE id=?"))
			{
				ps.setString(1, name);
				ps.setLong(2, bot.getId());
				ps.executeUpdate();
			}
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not rename bot '" + bot.getName() + "' to '" + name + "': " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Persists the mutable fields of a resolved bot.
	 * @param bot the bot to update
	 * @return true on success
	 */
	public boolean update(ColdBot bot)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE))
		{
			int i = 1;
			ps.setInt(i++, bot.getClassId());
			ps.setInt(i++, bot.getLevel());
			ps.setLong(i++, bot.getExpIntoLevel());
			ps.setLong(i++, bot.getSp());
			ps.setLong(i++, bot.getAdena());
			ps.setString(i++, bot.getRegion());
			ps.setInt(i++, bot.getX());
			ps.setInt(i++, bot.getY());
			ps.setInt(i++, bot.getZ());
			ps.setString(i++, bot.getActivity());
			ps.setString(i++, bot.getPhase());
			ps.setLong(i++, bot.getUpdatedAt());
			ps.setLong(i++, bot.getLastResolvedAt());
			ps.setLong(i++, bot.getNextResolveAt());
			ps.setBoolean(i++, bot.isHotLock());
			ps.setLong(i++, bot.getCharId());
			ps.setLong(i++, bot.getSoulshots());
			ps.setLong(i++, bot.getPotions());
			ps.setInt(i++, bot.getGearTier());
			ps.setString(i++, bot.getGoal() == null ? "hunting" : bot.getGoal());
			ps.setBoolean(i++, bot.isRewardClaimed());
			ps.setString(i++, bot.getStatsJson());
			ps.setString(i++, bot.getZone());
			ps.setString(i++, bot.getTown());
			ps.setLong(i++, bot.getEscapes());
			ps.setString(i++, (bot.getLeg() == null) ? null : bot.getLeg().encode());
			ps.setLong(i++, bot.getLoot());
			ps.setInt(i++, bot.getDeaths());
			ps.setString(i++, (bot.getRisk() == ColdRisk.State.EMPTY) ? null : bot.getRisk().encode());
			ps.setString(i++, (bot.getQuest() == null) ? null : bot.getQuest().encode());
			ps.setString(i++, bot.getSkills());
			ps.setString(i++, bot.getGear());
			ps.setLong(i++, bot.getId());
			ps.executeUpdate();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: failed to update cold bot id " + bot.getId() + ": " + e.getMessage(), e);
			return false;
		}
	}
}
