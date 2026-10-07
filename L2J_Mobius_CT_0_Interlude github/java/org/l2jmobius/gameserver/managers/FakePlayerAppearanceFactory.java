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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.CrystalType;

/**
 * Procedurally generates {@link FakePlayerAppearance}s: unique pronounceable names plus a random but
 * coherent race / gender / class / look. Gear is resolved data-driven from the item table by armor family
 * and grade (no hard-coded id lists): a mystic gets a robe set + magic weapon, a fighter a light or heavy
 * set + a sword/blunt, and every bot wears a <b>complete</b> set for its grade (chest + legs - or a single
 * full-body piece - plus gloves and boots; only the helmet varies), so the crowd no longer shows random
 * missing pieces or casters in plate.
 */
public class FakePlayerAppearanceFactory
{
	// Occupation classes per race, split by level tier: [0] base (<20), [1] first occupation (20-39),
	// [2] second occupation (40+). A generated bot draws a class from the tier its rolled level fits, so a town
	// crowd shows recognizable knights / archers / daggers / healers / mages at the right levels instead of only
	// base fighters and mystics. (Third-occupation classes, 76+, keep their 2nd-class look - fine visually.)
	private static final int[][] HUMAN_TIERS =
	{
		{
			0,
			10
		}, // Fighter, Mage
		{
			1,
			4,
			7,
			11,
			15
		}, // Warrior, Knight, Rogue, Wizard, Cleric
		{
			2,
			3,
			5,
			6,
			8,
			9,
			12,
			13,
			14,
			16,
			17
		} // Gladiator, Warlord, Paladin, Dark Avenger, Treasure Hunter, Hawkeye, Sorcerer, Necromancer, Warlock, Bishop, Prophet
	};
	private static final int[][] ELF_TIERS =
	{
		{
			18,
			25
		},
		{
			19,
			22,
			26,
			29
		}, // Elven Knight, Elven Scout, Elven Wizard, Elven Oracle
		{
			20,
			21,
			23,
			24,
			27,
			28,
			30
		} // Temple Knight, Swordsinger, Plains Walker, Silver Ranger, Spellsinger, Elemental Summoner, Elven Elder
	};
	private static final int[][] DARK_ELF_TIERS =
	{
		{
			31,
			38
		},
		{
			32,
			35,
			39,
			42
		}, // Palus Knight, Assassin, Dark Wizard, Shillien Oracle
		{
			33,
			34,
			36,
			37,
			40,
			41,
			43
		} // Shillien Knight, Bladedancer, Abyss Walker, Phantom Ranger, Spellhowler, Phantom Summoner, Shillien Elder
	};
	private static final int[][] ORC_TIERS =
	{
		{
			44,
			49
		},
		{
			45,
			47,
			50
		}, // Orc Raider, Orc Monk, Orc Shaman
		{
			46,
			48,
			51,
			52
		} // Destroyer, Tyrant, Overlord, Warcryer
	};
	private static final int[][] DWARF_TIERS =
	{
		{
			53
		},
		{
			54,
			56
		}, // Scavenger, Artisan
		{
			55,
			57
		} // Bounty Hunter, Warsmith
	};

	// ===== Data-driven display-gear pools, resolved once from the item table (no hard-coded id lists). =====
	// Body slots that belong to an armor family (light/heavy/robe). FULL_ARMOR is a one-piece body armor (fills the
	// chest slot; legs stay empty by design - not a "missing pants" bug).
	private static final BodyPart[] BODY_SLOTS =
	{
		BodyPart.FULL_ARMOR,
		BodyPart.CHEST,
		BodyPart.LEGS
	};
	// Gloves / boots / helmet are family-agnostic (ArmorType.NONE - any class wears any of them), so they come from
	// one shared pool per slot, not from the light/heavy/robe family pools.
	private static final BodyPart[] EXTREMITY_SLOTS =
	{
		BodyPart.GLOVES,
		BodyPart.FEET,
		BodyPart.HEAD
	};
	// The weapon look each archetype carries. SWORD_1H is a one-handed sword/blunt (so a shield fits) for
	// tanks and swordsingers; SWORD is any sword/blunt for generic fighters.
	private enum WeaponKind
	{
		MAGIC,
		SWORD,
		SWORD_1H,
		DAGGER,
		BOW,
		DUAL,
		FIST
	}
	// family (LIGHT/HEAVY/MAGIC) -> grade -> body slot -> candidate display ids.
	private static final Map<ArmorType, EnumMap<CrystalType, EnumMap<BodyPart, List<Integer>>>> ARMOR_POOLS = new EnumMap<>(ArmorType.class);
	// grade -> extremity slot (gloves/feet/head) -> candidate display ids (shared across families).
	private static final EnumMap<CrystalType, EnumMap<BodyPart, List<Integer>>> EXTREMITY_POOLS = new EnumMap<>(CrystalType.class);
	// weapon kind -> grade -> candidate display ids.
	private static final Map<WeaponKind, EnumMap<CrystalType, List<Integer>>> WEAPON_POOLS = new EnumMap<>(WeaponKind.class);
	// grade -> candidate shield display ids (for tanks / swordsingers).
	private static final EnumMap<CrystalType, List<Integer>> SHIELDS = new EnumMap<>(CrystalType.class);
	private static volatile boolean _poolsBuilt = false;
	private static volatile int _poolsVersion = -1;

	// Races eligible for generated bots, weighted by repetition (humans most common, dwarves least).
	private static final Race[] RACE_POOL =
	{
		Race.HUMAN, Race.HUMAN, Race.HUMAN,
		Race.ELF, Race.ELF,
		Race.DARK_ELF, Race.DARK_ELF,
		Race.ORC,
		Race.DWARF
	};

	// Believable private-store titles shown above seated vendors. The store-type icon already marks a shop as
	// buying or selling, so these carry no "WTS"/"WTB" tag - the buy signs just phrase the demand ("buying ...").
	private static final String[] SELL_TITLES =
	{
		"items pm", "D grade cheap", "C grade", "soulshots", "spiritshots",
		"mats cheap", "adena items", "cheap stuff inside", "armor parts", "recipes",
		"keymats", "clearance sale", "top D", "weapon", "jewels"
	};
	private static final String[] BUY_TITLES =
	{
		"buying adena", "buying D grade", "buying mats", "buying soulshots", "buying recipes",
		"buying keymats", "buying C grade", "buying enchant scrolls", "buying proofs", "buying gemstones"
	};
	private static final String[] CRAFT_TITLES =
	{
		"crafting D grade", "crafting C grade", "armor craft", "weapon craft", "free craft pm mats",
		"craft service", "making shots", "craft cheap", "mass production"
	};

	private FakePlayerAppearanceFactory()
	{
	}

	/**
	 * Generates a fresh appearance with a random level in the given (inclusive) range.
	 * @param minLevel lowest level to roll
	 * @param maxLevel highest level to roll
	 * @return a populated {@link FakePlayerAppearance}
	 */
	public static FakePlayerAppearance generate(int minLevel, int maxLevel)
	{
		return generate(minLevel, maxLevel, null);
	}

	/**
	 * Generates a fresh appearance, optionally biased toward a dominant race (e.g. a Dwarven village).
	 * @param minLevel lowest level to roll
	 * @param maxLevel highest level to roll
	 * @param dominantRace if not {@code null}, most bots will be this race
	 * @return a populated {@link FakePlayerAppearance}
	 */
	public static FakePlayerAppearance generate(int minLevel, int maxLevel, Race dominantRace)
	{
		return generate(minLevel, maxLevel, dominantRace, false);
	}

	/**
	 * Generates a fresh appearance, optionally biased toward (or locked to) a given race.
	 * @param minLevel lowest level to roll
	 * @param maxLevel highest level to roll
	 * @param dominantRace if not {@code null}, bots will be this race (biased, or guaranteed if forced)
	 * @param forceRace {@code true} to make every bot {@code dominantRace} (e.g. dwarf-only crafters)
	 * @return a populated {@link FakePlayerAppearance}
	 */
	public static FakePlayerAppearance generate(int minLevel, int maxLevel, Race dominantRace, boolean forceRace)
	{
		final Race race = (dominantRace != null) && (forceRace || (Rnd.get(100) < 82)) ? dominantRace : RACE_POOL[Rnd.get(RACE_POOL.length)];
		final boolean female = (race != Race.ORC) && (race != Race.DWARF) ? Rnd.nextBoolean() : Rnd.get(100) < 30; // orcs/dwarves skew male
		final int level = Rnd.get(minLevel, maxLevel);
		final int classId = pickClassId(race, level); // a class whose occupation tier fits the rolled level
		final PlayerClass playerClass = PlayerClass.getPlayerClass(classId);

		final FakePlayerAppearance look = new FakePlayerAppearance();
		look.setName(generateName());
		look.setRace(race);
		look.setFemale(female);
		if (playerClass != null)
		{
			look.setPlayerClass(playerClass);
		}
		look.setLevel(level);
		look.setHairStyle(Rnd.get(0, 3));
		look.setHairColor(Rnd.get(0, 3));
		look.setFace(Rnd.get(0, 2));
		equip(look, playerClass);
		return look;
	}

	/**
	 * Dresses a generated bot in a coherent, complete gear set for its level, matching its class archetype (the
	 * same class-&gt;role mapping the recruited-phantom gearing uses): an archer shows a bow + light set, a knight a
	 * sword + shield + heavy set, a mystic a robe set + magic weapon, a fist-fighter a fist weapon, and so on.
	 * Chest, gloves and boots are always worn and legs too (unless the chest is a one-piece full-body armor); only
	 * the helmet varies, so the crowd shows variety without random bare torsos/legs or wrong-fit gear.
	 * @param look the appearance to equip
	 * @param playerClass the bot's class (drives the weapon kind + armor family); {@code null} falls back to a plain fighter
	 */
	private static void equip(FakePlayerAppearance look, PlayerClass playerClass)
	{
		buildPools();

		// Archetype from the class - shared source of truth with the recruited-phantom gearing.
		final PhantomManager.PartyRole role = (playerClass != null) ? PhantomManager.roleForClass(playerClass) : PhantomManager.PartyRole.WARRIOR;

		// Grade the bot's level allows, spread by "wealth" so a same-level crowd is not all in identical top gear:
		// most are poor (no-grade) or average (a grade under), a minority fully kitted for their level.
		final CrystalType levelGrade = gradeForLevel(look.getLevel());
		final int wealth = Rnd.get(100);
		final CrystalType grade;
		if (wealth < 40)
		{
			grade = CrystalType.NONE; // poor: plain no-grade
		}
		else if (wealth < 85)
		{
			grade = stepDown(levelGrade, Rnd.get(0, 1)); // average: their grade, often one lower
		}
		else
		{
			grade = levelGrade; // well-off: full grade for their level
		}

		// Armor family from the archetype: robe (MAGIC) for casters, LIGHT for archers/daggers/monks, HEAVY for
		// tanks/warriors/singer/dancer.
		final ArmorType family = role.armor;

		// A coherent matching armor set for the family/grade (Karmian, Full Plate, ...), so the bot wears a real
		// set instead of a random mix. Any slot the set does not define is filled from the generic pools below.
		final FakePlayerArmorSets.Outfit outfit = FakePlayerArmorSets.random(family, grade);

		// Weapon: most carry one, a few go unarmed; a minority show a small enchant. Kind matches the archetype
		// (bow/dagger/dual/fist/1H-sword/magic); tanks and swordsingers also get a shield (the set's, if any).
		if (Rnd.get(100) < 88)
		{
			int rHand = pickWeapon(weaponKindForRole(role), grade);
			if (rHand <= 0) // a kind/grade gap - fall back so a bot is not left barehanded
			{
				rHand = pickWeapon(role.mage ? WeaponKind.MAGIC : WeaponKind.SWORD, grade);
			}
			if (rHand > 0)
			{
				int lHand = 0;
				if ((role == PhantomManager.PartyRole.TANK) || (role == PhantomManager.PartyRole.SINGER))
				{
					lHand = ((outfit != null) && (outfit.shield > 0)) ? outfit.shield : pickShield(grade);
				}
				look.setWeapon(rHand, lHand);
				if ((grade != CrystalType.NONE) && (Rnd.get(100) < 12))
				{
					look.setWeaponEnchantLevel(Rnd.get(1, 4));
				}
			}
		}

		// Body armor from the set (chest + legs, unless the chest is a one-piece full body armor); if there is no
		// set for this family/grade, fall back to a family chest (+ legs) so the bot is never bare-chested.
		int chest;
		int legs = 0;
		if (outfit != null)
		{
			chest = outfit.chest;
			legs = outfit.onepiece ? 0 : ((outfit.legs > 0) ? outfit.legs : pickArmor(family, grade, BodyPart.LEGS));
		}
		else
		{
			final int full = pickArmor(family, grade, BodyPart.FULL_ARMOR);
			final int upper = pickArmor(family, grade, BodyPart.CHEST);
			if ((full > 0) && ((upper <= 0) || Rnd.nextBoolean()))
			{
				chest = full; // one-piece body armor - no separate leg item
			}
			else if (upper > 0)
			{
				chest = upper;
				legs = pickArmor(family, grade, BodyPart.LEGS);
			}
			else
			{
				chest = full;
			}
		}

		// Gloves and boots always; helmet a bit over half the time. Prefer the set's matching piece, else a
		// generic one from the family-agnostic extremity pool.
		final int gloves = ((outfit != null) && (outfit.gloves > 0)) ? outfit.gloves : pickExtremity(grade, BodyPart.GLOVES);
		final int feet = ((outfit != null) && (outfit.feet > 0)) ? outfit.feet : pickExtremity(grade, BodyPart.FEET);
		final int head = (Rnd.get(100) < 55) ? (((outfit != null) && (outfit.head > 0)) ? outfit.head : pickExtremity(grade, BodyPart.HEAD)) : 0;
		look.setArmor(head, chest, legs, gloves, feet, 0);
	}

	/** Highest gear grade a bot of this level would carry (aligned with Interlude expertise level gates). */
	private static CrystalType gradeForLevel(int level)
	{
		if (level < 20)
		{
			return CrystalType.NONE;
		}
		if (level < 40)
		{
			return CrystalType.D;
		}
		if (level < 52)
		{
			return CrystalType.C;
		}
		if (level < 61)
		{
			return CrystalType.B;
		}
		if (level < 76)
		{
			return CrystalType.A;
		}
		return CrystalType.S;
	}

	/** {@code grade} lowered by {@code steps} tiers, clamped at NO-GRADE. */
	private static CrystalType stepDown(CrystalType grade, int steps)
	{
		return CrystalType.values()[Math.max(0, grade.ordinal() - steps)];
	}

	/** A random body-armor display id for the family/slot at {@code grade}, stepping down a grade if empty; 0 if none. */
	private static int pickArmor(ArmorType family, CrystalType grade, BodyPart slot)
	{
		final EnumMap<CrystalType, EnumMap<BodyPart, List<Integer>>> byGrade = ARMOR_POOLS.get(family);
		if (byGrade == null)
		{
			return 0;
		}
		for (int ord = grade.ordinal(); ord >= 0; ord--)
		{
			final List<Integer> list = byGrade.get(CrystalType.values()[ord]).get(slot);
			if ((list != null) && !list.isEmpty())
			{
				return list.get(Rnd.get(list.size()));
			}
		}
		return 0;
	}

	/** A random gloves/boots/helmet display id at {@code grade} (family-agnostic), stepping down a grade if empty; 0 if none. */
	private static int pickExtremity(CrystalType grade, BodyPart slot)
	{
		for (int ord = grade.ordinal(); ord >= 0; ord--)
		{
			final List<Integer> list = EXTREMITY_POOLS.get(CrystalType.values()[ord]).get(slot);
			if ((list != null) && !list.isEmpty())
			{
				return list.get(Rnd.get(list.size()));
			}
		}
		return 0;
	}

	/** The weapon look each archetype (role) carries. */
	private static WeaponKind weaponKindForRole(PhantomManager.PartyRole role)
	{
		switch (role)
		{
			case NUKER:
			case HEALER:
			case BUFFER:
			{
				return WeaponKind.MAGIC;
			}
			case ARCHER:
			{
				return WeaponKind.BOW;
			}
			case DAGGER:
			{
				return WeaponKind.DAGGER;
			}
			case DANCER:
			{
				return WeaponKind.DUAL;
			}
			case MONK:
			{
				return WeaponKind.FIST;
			}
			case TANK:
			case SINGER:
			{
				return WeaponKind.SWORD_1H; // one-handed, so a shield fits
			}
			default: // WARRIOR and anything else - a generic sword/blunt
			{
				return WeaponKind.SWORD;
			}
		}
	}

	/** A random weapon display id of {@code kind} at {@code grade}, stepping down a grade if that one is empty; 0 if none. */
	private static int pickWeapon(WeaponKind kind, CrystalType grade)
	{
		final EnumMap<CrystalType, List<Integer>> pool = WEAPON_POOLS.get(kind);
		if (pool == null)
		{
			return 0;
		}
		for (int ord = grade.ordinal(); ord >= 0; ord--)
		{
			final List<Integer> list = pool.get(CrystalType.values()[ord]);
			if ((list != null) && !list.isEmpty())
			{
				return list.get(Rnd.get(list.size()));
			}
		}
		return 0;
	}

	/** A random shield display id at {@code grade}, stepping down a grade if that one is empty; 0 if none. */
	private static int pickShield(CrystalType grade)
	{
		for (int ord = grade.ordinal(); ord >= 0; ord--)
		{
			final List<Integer> list = SHIELDS.get(CrystalType.values()[ord]);
			if ((list != null) && !list.isEmpty())
			{
				return list.get(Rnd.get(list.size()));
			}
		}
		return 0;
	}

	/** Resolves the display-gear pools from the item table once (armor by family/grade/slot, weapons by grade). */
	private static synchronized void buildPools()
	{
		final int version = FakePlayerGearFilter.getVersion();
		if (_poolsBuilt && (_poolsVersion == version))
		{
			return;
		}
		ARMOR_POOLS.clear();
		EXTREMITY_POOLS.clear();
		WEAPON_POOLS.clear();
		SHIELDS.clear();
		for (ArmorType family : new ArmorType[]
		{
			ArmorType.LIGHT,
			ArmorType.HEAVY,
			ArmorType.MAGIC
		})
		{
			final EnumMap<CrystalType, EnumMap<BodyPart, List<Integer>>> byGrade = new EnumMap<>(CrystalType.class);
			for (CrystalType g : CrystalType.values())
			{
				final EnumMap<BodyPart, List<Integer>> bySlot = new EnumMap<>(BodyPart.class);
				for (BodyPart bp : BODY_SLOTS)
				{
					bySlot.put(bp, new ArrayList<>());
				}
				byGrade.put(g, bySlot);
			}
			ARMOR_POOLS.put(family, byGrade);
		}
		for (CrystalType g : CrystalType.values())
		{
			final EnumMap<BodyPart, List<Integer>> bySlot = new EnumMap<>(BodyPart.class);
			for (BodyPart bp : EXTREMITY_SLOTS)
			{
				bySlot.put(bp, new ArrayList<>());
			}
			EXTREMITY_POOLS.put(g, bySlot);
		}
		for (WeaponKind kind : WeaponKind.values())
		{
			final EnumMap<CrystalType, List<Integer>> byGrade = new EnumMap<>(CrystalType.class);
			for (CrystalType g : CrystalType.values())
			{
				byGrade.put(g, new ArrayList<>());
			}
			WEAPON_POOLS.put(kind, byGrade);
		}
		for (CrystalType g : CrystalType.values())
		{
			SHIELDS.put(g, new ArrayList<>());
		}
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || !item.isEquipable() || !item.isTradeable() || (item.getReferencePrice() <= 0) || item.isForNpc() || !FakePlayerGearFilter.isPlayerGear(item))
			{
				continue; // skip pet/summon/monster gear (for_npc) that renders as a sack / invisible slot
			}
			final CrystalType grade = item.getCrystalType();
			final int id = item.getId();
			if (item instanceof Armor)
			{
				final ArmorType at = ((Armor) item).getItemType();
				if (at == ArmorType.SHIELD)
				{
					SHIELDS.get(grade).add(id);
					continue;
				}
				final BodyPart bp = item.getBodyPart();
				if ((bp == BodyPart.GLOVES) || (bp == BodyPart.FEET) || (bp == BodyPart.HEAD)) // family-agnostic (ArmorType.NONE)
				{
					EXTREMITY_POOLS.get(grade).get(bp).add(id);
					continue;
				}
				final EnumMap<CrystalType, EnumMap<BodyPart, List<Integer>>> byGrade = ARMOR_POOLS.get(at);
				if (byGrade == null) // SIGIL / NONE without a body slot we dress
				{
					continue;
				}
				final List<Integer> list = byGrade.get(grade).get(bp); // CHEST / LEGS / FULL_ARMOR
				if (list != null)
				{
					list.add(id);
				}
			}
			else if (item instanceof Weapon)
			{
				if (item.isMagicWeapon())
				{
					WEAPON_POOLS.get(WeaponKind.MAGIC).get(grade).add(id);
					continue;
				}
				switch (((Weapon) item).getItemType())
				{
					case SWORD:
					case BLUNT:
					{
						WEAPON_POOLS.get(WeaponKind.SWORD).get(grade).add(id);
						if (item.getBodyPart() == BodyPart.R_HAND) // one-handed - usable with a shield
						{
							WEAPON_POOLS.get(WeaponKind.SWORD_1H).get(grade).add(id);
						}
						break;
					}
					case DAGGER:
					{
						WEAPON_POOLS.get(WeaponKind.DAGGER).get(grade).add(id);
						break;
					}
					case BOW:
					{
						WEAPON_POOLS.get(WeaponKind.BOW).get(grade).add(id);
						break;
					}
					case DUAL:
					{
						WEAPON_POOLS.get(WeaponKind.DUAL).get(grade).add(id);
						break;
					}
					case FIST:
					case DUALFIST:
					{
						WEAPON_POOLS.get(WeaponKind.FIST).get(grade).add(id);
						break;
					}
					default:
					{
						break;
					}
				}
			}
		}
		_poolsVersion = version;
		_poolsBuilt = true;
	}

	/**
	 * @param kind store kind: BUY, CRAFT/MANUFACTURE, or anything else (sell)
	 * @return a random believable store title for that kind
	 */
	public static String storeTitle(String kind)
	{
		if ("BUY".equalsIgnoreCase(kind))
		{
			return BUY_TITLES[Rnd.get(BUY_TITLES.length)];
		}
		if ("CRAFT".equalsIgnoreCase(kind) || "MANUFACTURE".equalsIgnoreCase(kind))
		{
			return CRAFT_TITLES[Rnd.get(CRAFT_TITLES.length)];
		}
		return SELL_TITLES[Rnd.get(SELL_TITLES.length)];
	}

	/** A class id for the race whose occupation tier fits {@code level} (base &lt;20, first 20-39, second 40+). */
	private static int pickClassId(Race race, int level)
	{
		final int[][] tiers = tiersForRace(race);
		final int tier = (level < 20) ? 0 : (level < 40) ? 1 : 2;
		final int[] pool = tiers[Math.min(tier, tiers.length - 1)];
		return pool[Rnd.get(pool.length)];
	}

	private static int[][] tiersForRace(Race race)
	{
		switch (race)
		{
			case ELF:
			{
				return ELF_TIERS;
			}
			case DARK_ELF:
			{
				return DARK_ELF_TIERS;
			}
			case ORC:
			{
				return ORC_TIERS;
			}
			case DWARF:
			{
				return DWARF_TIERS;
			}
			default:
			{
				return HUMAN_TIERS;
			}
		}
	}

	/**
	 * @return a unique pronounceable name (max 16 chars, as the client expects)
	 */
	public static String generateName()
	{
		return FakePlayerNameFactory.generateName();
	}

	/**
	 * Deterministic name variant built from a caller-supplied seeded RNG, so the same seed always yields the
	 * same name. Used for stable "regular" identities that must be identical across restarts (unlike the
	 * random {@link #generateName()}).
	 * @param rng a seeded random source (same seed -&gt; same name)
	 * @return a syllable-built name (from the same pools as the random generator), capped at 16 chars
	 */
	public static String generateName(Random rng)
	{
		return FakePlayerNameFactory.generateName(rng);
	}
}
