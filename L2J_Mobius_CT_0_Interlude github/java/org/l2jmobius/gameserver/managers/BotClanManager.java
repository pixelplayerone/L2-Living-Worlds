/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.sql.CrestTable;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.model.clan.Crest;
import org.l2jmobius.gameserver.model.clan.enums.CrestType;

import org.w3c.dom.Document;

/**
 * Bot-clan brain.
 * <p>
 * Builds a small set of synthetic {@link Clan} objects from {@code data/BotClans.xml} at boot and lets phantoms
 * belong to them, so a phantom's clan name and crest show over its head and in the clan window exactly like a real
 * player clan. Two entry points attach a phantom to a bot clan:
 * <ul>
 * <li>{@link PhantomManager} attaches a field/town phantom to the clan named on its population (per-population
 * {@code botClan} / {@code botClanChance} in {@code PhantomPopulations.xml}).</li>
 * <li>{@link PhantomPartyManager} rolls each recruited (LFM) phantom against {@link #getRecruitClanChance()} to wear
 * a random bot clan, so pickup groups occasionally sport clan crests.</li>
 * </ul>
 * <p>
 * Bot clans are <b>not</b> written to {@code clan_data}; they are rebuilt every boot from the data file. Only their
 * crests persist (in the {@code crests} table), and those are reused across reboots by matching bytes
 * ({@link CrestTable#findByData}), so nothing accumulates. Membership is purely in-memory: a phantom is registered
 * on the clan with a runtime-only {@link ClanMember} on spawn and dropped again on despawn, with no database writes.
 */
public class BotClanManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(BotClanManager.class.getName());

	// The clan crest wire cap is 256 bytes (see RequestSetPledgeCrest); a blob over that would be rejected by a
	// client, so skip it and log rather than register a crest that cannot render. The ally crest wire cap is 192
	// bytes (see RequestSetAllyCrest).
	private static final int MAX_PLEDGE_CREST_BYTES = 256;
	private static final int MAX_ALLY_CREST_BYTES = 192;
	// Crests are DDS (DXT1) textures, not PNG - the Interlude client cannot decode a PNG as a crest. The .png files
	// under data/crests/ are the design source; the .dds files next to them are what ships and renders.
	private static final String DEFAULT_PLEDGE_CREST_FILE = "pledge_16x12.dds";
	private static final String DEFAULT_ALLY_CREST_FILE = "ally_8x12.dds";

	// Generated clan-member titles: clan membership is what lets a character bear a title, so a clanned bot shows one
	// picked from this pool (rank/flavor words) instead of an empty title. Kept short so it reads well under a name.
	private static final String[] MEMBER_TITLES =
	{
		"Initiate", "Member", "Veteran", "Elite", "Officer", "Guardian", "Champion", "Vanguard", "Sentinel", "Warden", //
		"Adept", "Zealot", "Enforcer", "Knight", "Squire", "Herald", "Loyalist", "Reaver", "Blademaster", "Sworn"
	};

	private final Map<String, Clan> _clansByKey = new ConcurrentHashMap<>();
	private final List<Clan> _clanList = new ArrayList<>();
	private int _recruitClanChance; // % chance a recruited phantom wears a random bot clan (0 = never)
	private int _fakePlayerClanChance; // % chance a generated town fake player wears a random bot clan (0 = never)
	private String _pledgeCrestFile = DEFAULT_PLEDGE_CREST_FILE;
	private String _allyCrestFile = DEFAULT_ALLY_CREST_FILE;
	private int _allianceCount; // alliances built this load, for the boot log

	protected BotClanManager()
	{
		load();
	}

	public void load()
	{
		_clansByKey.clear();
		_clanList.clear();
		_recruitClanChance = 0;
		_fakePlayerClanChance = 0;
		_pledgeCrestFile = DEFAULT_PLEDGE_CREST_FILE;
		_allyCrestFile = DEFAULT_ALLY_CREST_FILE;
		_allianceCount = 0;
		parseDatapackFile("data/BotClans.xml");
		int withCrest = 0;
		for (Clan clan : _clanList)
		{
			if (clan.getCrestId() != 0)
			{
				withCrest++;
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _clanList.size() + " bot clan(s) (" + withCrest + " with a crest), " + _allianceCount + " alliance(s); recruit clan chance " + _recruitClanChance + "%, fake-player clan chance " + _fakePlayerClanChance + "%.");
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		// Settings first, so the crest file name is known before any clan is built.
		forEach(document, "list", listNode -> forEach(listNode, "settings", settingsNode ->
		{
			final StatSet set = new StatSet(parseAttributes(settingsNode));
			_recruitClanChance = Math.max(0, Math.min(100, set.getInt("recruitClanChance", 0)));
			_fakePlayerClanChance = Math.max(0, Math.min(100, set.getInt("fakePlayerClanChance", 0)));
			_pledgeCrestFile = set.getString("pledgeCrestFile", DEFAULT_PLEDGE_CREST_FILE);
			_allyCrestFile = set.getString("allyCrestFile", DEFAULT_ALLY_CREST_FILE);
		}));

		forEach(document, "list", listNode -> forEach(listNode, "clan", clanNode ->
		{
			final StatSet set = new StatSet(parseAttributes(clanNode));
			final String key = set.getString("key", "").trim();
			final String name = set.getString("name", "").trim();
			if (key.isEmpty() || name.isEmpty())
			{
				LOGGER.warning(getClass().getSimpleName() + ": Skipping a <clan> missing key or name.");
				return;
			}
			if (_clansByKey.containsKey(key))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Duplicate bot clan key '" + key + "' - skipping.");
				return;
			}
			final int level = set.getInt("level", 5);
			final String crestSet = set.getString("crestSet", key);
			try
			{
				final Clan clan = buildClan(key, name, level, crestSet);
				if (clan != null)
				{
					_clansByKey.put(key, clan);
					_clanList.add(clan);
				}
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Failed to build bot clan '" + key + "': " + e.getMessage());
			}
		}));

		// Alliances are parsed after every clan is built and registered, so member keys resolve regardless of the
		// order clans and alliances appear in the file.
		forEach(document, "list", listNode -> forEach(listNode, "alliance", allyNode ->
		{
			final StatSet set = new StatSet(parseAttributes(allyNode));
			final String name = set.getString("name", "").trim();
			final String leaderKey = set.getString("leader", "").trim();
			if (name.isEmpty() || leaderKey.isEmpty())
			{
				LOGGER.warning(getClass().getSimpleName() + ": Skipping an <alliance> missing name or leader.");
				return;
			}
			final String crestSet = set.getString("crestSet", leaderKey);
			final List<String> memberKeys = new ArrayList<>();
			forEach(allyNode, "member", memberNode ->
			{
				final String memberKey = new StatSet(parseAttributes(memberNode)).getString("clan", "").trim();
				if (!memberKey.isEmpty())
				{
					memberKeys.add(memberKey);
				}
			});
			try
			{
				if (buildAlliance(name, leaderKey, crestSet, memberKeys))
				{
					_allianceCount++;
				}
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Failed to build alliance '" + name + "': " + e.getMessage());
			}
		}));
	}

	/**
	 * Forms one in-memory alliance across already-built bot clans. Alliances are never persisted (like the clans
	 * themselves): the ally id is the leader clan's id (retail convention, see {@link Clan#createAlly}), and the ally
	 * name plus a shared ally crest are stamped in memory onto the leader and every member clan, so a clanned bot's
	 * ally name and ally crest broadcast exactly like a real alliance. A clan already in another alliance is left
	 * alone (a clan belongs to at most one alliance).
	 * @return {@code true} if the alliance was formed (leader resolved), {@code false} otherwise.
	 */
	private boolean buildAlliance(String name, String leaderKey, String crestSet, List<String> memberKeys)
	{
		final Clan leader = _clansByKey.get(leaderKey);
		if (leader == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Alliance '" + name + "' references unknown leader clan '" + leaderKey + "' - skipping.");
			return false;
		}
		if (leader.getAllyId() != 0)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Alliance leader '" + leaderKey + "' already belongs to an alliance - skipping alliance '" + name + "'.");
			return false;
		}

		final int allyId = leader.getId();
		final int allyCrestId = loadAllyCrest(crestSet); // 0 if the file is missing or too big; ally still forms

		final List<Clan> members = new ArrayList<>();
		members.add(leader);
		for (String memberKey : memberKeys)
		{
			if (memberKey.equals(leaderKey))
			{
				continue; // the leader is a member implicitly
			}
			final Clan member = _clansByKey.get(memberKey);
			if (member == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Alliance '" + name + "' references unknown clan '" + memberKey + "' - skipping that member.");
				continue;
			}
			if (member.getAllyId() != 0)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Clan '" + memberKey + "' is already in an alliance - not adding it to '" + name + "'.");
				continue;
			}
			members.add(member);
		}

		for (Clan clan : members)
		{
			clan.setAllyId(allyId);
			clan.setAllyName(name);
			if (allyCrestId != 0)
			{
				clan.setAllyCrestId(allyCrestId);
			}
		}
		return true;
	}

	/**
	 * Builds one in-memory bot clan: a real {@link Clan} with a data-only leader (so the clan window has a leader to
	 * show) and a pledge crest, registered in {@link ClanTable} so lookups resolve it. Always creates a fresh clan -
	 * bot clans are never persisted, so they are rebuilt each boot; the crest is reused across boots by byte match, so
	 * only the crest (not the clan row) is stable. Deliberately does NOT reuse a same-named clan from {@link ClanTable}
	 * so a real player clan that happens to share a name is never hijacked or given a crest.
	 */
	private Clan buildClan(String key, String name, int level, String crestSet)
	{
		final Clan clan = new Clan(IdManager.getInstance().getNextId(), name);
		// A stable, data-only leader (never spawned) so getLeaderName()/the clan window never hit a null leader.
		final ClanMember leader = new ClanMember(clan, name + "Lord", level + 40, 0, IdManager.getInstance().getNextId(), false, Race.HUMAN.ordinal());
		clan.setBotLevel(level);
		clan.addBotMember(leader);
		clan.setLeader(leader);
		ClanTable.getInstance().registerClan(clan);
		final int crestId = loadPledgeCrest(crestSet);
		if (crestId != 0)
		{
			clan.setCrestId(crestId);
		}
		return clan;
	}

	/**
	 * Loads a set's pledge crest from {@code data/crests/<crestSet>/<pledgeCrestFile>} (type {@link CrestType#PLEDGE},
	 * cap {@value #MAX_PLEDGE_CREST_BYTES} bytes). @return the crest id, or 0 if the file is missing or too big.
	 */
	private int loadPledgeCrest(String crestSet)
	{
		return loadCrest(crestSet, _pledgeCrestFile, CrestType.PLEDGE, MAX_PLEDGE_CREST_BYTES);
	}

	/**
	 * Loads a set's ally crest from {@code data/crests/<crestSet>/<allyCrestFile>} (type {@link CrestType#ALLY}, cap
	 * {@value #MAX_ALLY_CREST_BYTES} bytes). @return the crest id, or 0 if the file is missing or too big.
	 */
	private int loadAllyCrest(String crestSet)
	{
		return loadCrest(crestSet, _allyCrestFile, CrestType.ALLY, MAX_ALLY_CREST_BYTES);
	}

	/**
	 * Loads crest bytes from {@code data/crests/<crestSet>/<fileName>}, reusing an existing stored crest with matching
	 * bytes or creating one, and returns its id (0 if the file is missing or over {@code maxBytes}).
	 */
	private int loadCrest(String crestSet, String fileName, CrestType crestType, int maxBytes)
	{
		final String relativePath = "data/crests/" + crestSet + "/" + fileName;
		// Resolve against the working directory (new File(".", ...)) - the SAME base parseDatapackFile uses for the XML
		// that loaded fine - rather than DATAPACK_ROOT, which can differ from the working dir on some server configs.
		File crestFile = new File(".", relativePath);
		if (!crestFile.isFile())
		{
			// Fall back to the configured datapack root in case the working dir is not the datapack dir.
			final File alt = new File(ServerConfig.DATAPACK_ROOT, relativePath);
			if (alt.isFile())
			{
				crestFile = alt;
			}
			else
			{
				LOGGER.warning(getClass().getSimpleName() + ": Crest file '" + fileName + "' not found for set '" + crestSet + "'. Looked in: " + crestFile.getAbsolutePath() + " and " + alt.getAbsolutePath() + ".");
				return 0;
			}
		}
		try
		{
			final byte[] data = Files.readAllBytes(crestFile.toPath());
			if (data.length > maxBytes)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Crest '" + fileName + "' for set '" + crestSet + "' is " + data.length + " bytes (max " + maxBytes + ") - skipping so it can't be rejected by a client.");
				return 0;
			}
			Crest crest = CrestTable.getInstance().findByData(data, crestType);
			if (crest == null)
			{
				crest = CrestTable.getInstance().createCrest(data, crestType);
			}
			return (crest != null) ? crest.getId() : 0;
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to load crest '" + fileName + "' for set '" + crestSet + "': " + e.getMessage());
			return 0;
		}
	}

	/**
	 * @param key a bot clan key from {@code BotClans.xml}
	 * @return the bot clan for that key, or {@code null} if none is defined
	 */
	public Clan getClanByKey(String key)
	{
		return (key == null) ? null : _clansByKey.get(key);
	}

	/** @return a random bot clan, or {@code null} if none are defined. */
	public Clan getRandomClan()
	{
		return _clanList.isEmpty() ? null : _clanList.get(Rnd.get(_clanList.size()));
	}

	/** @return the % chance a recruited (LFM) phantom should wear a random bot clan (0 = never). */
	public int getRecruitClanChance()
	{
		return _recruitClanChance;
	}

	/** @return the % chance a generated town fake player should wear a random bot clan (0 = never). */
	public int getFakePlayerClanChance()
	{
		return _fakePlayerClanChance;
	}

	/** @return {@code true} if {@code clan} is one of the synthetic bot clans this manager owns. */
	public boolean isBotClan(Clan clan)
	{
		return (clan != null) && _clanList.contains(clan);
	}

	/** @return a generated clan-member title (rank/flavor word) for a clanned bot. */
	public String randomTitle()
	{
		return MEMBER_TITLES[Rnd.get(MEMBER_TITLES.length)];
	}

	/**
	 * Attaches {@code phantom} to {@code clan} as a runtime-only member so its crest and clan name show. Safe to call
	 * before the phantom enters the world (pure state, no packets required); registering the member first satisfies
	 * the {@code isMember} check that {@link Player#setClan(Clan)} enforces.
	 * @param phantom the clientless phantom
	 * @param clan the bot clan to join (no-op if null)
	 */
	public void attach(Player phantom, Clan clan)
	{
		if ((phantom == null) || (clan == null) || (phantom.getClan() == clan))
		{
			return;
		}
		clan.addBotMember(new ClanMember(clan, phantom));
		phantom.setClan(clan);
		phantom.setTitle(randomTitle()); // clan membership is what lets it bear a title; give it a generated one
	}

	/**
	 * Detaches {@code phantom} from its bot clan on despawn, dropping the runtime-only membership so the roster keeps
	 * no stale object id. A no-op for a phantom not in a bot clan, so it is safe to call on every despawn path.
	 * @param phantom the phantom being torn down
	 */
	public void detach(Player phantom)
	{
		if (phantom == null)
		{
			return;
		}
		final Clan clan = phantom.getClan();
		if (isBotClan(clan))
		{
			clan.removeBotMember(phantom.getObjectId());
			phantom.setClan(null);
			phantom.setTitle(""); // clear the generated clan title so a persistent regular's saved row keeps none
		}
	}

	public static BotClanManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final BotClanManager INSTANCE = new BotClanManager();
	}
}
