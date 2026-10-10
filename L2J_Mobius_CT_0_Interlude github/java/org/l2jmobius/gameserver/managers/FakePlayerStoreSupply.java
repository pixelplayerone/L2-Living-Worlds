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
package org.l2jmobius.gameserver.managers;

import java.util.List;

import org.l2jmobius.gameserver.model.Location;

/**
 * Where the SELL stores of the fake-player vendors can get real goods instead of rolled ones. A module sets one with
 * {@link FakePlayerStoreFactory#setSupply(FakePlayerStoreSupply)}; without one, or when it offers nothing for a spot,
 * the stores roll their stock as before. A store line made from an offer remembers its source, and a purchase of that
 * line goes through {@link #withdraw}, {@link #paid} and {@link #restore} so whoever owns the goods is paid.
 */
public interface FakePlayerStoreSupply
{
	/**
	 * One item the supply can put in a store.
	 * @param itemId the item
	 * @param count how many it holds
	 * @param source what to hand back to the other methods (opaque to the store)
	 */
	record Offer(int itemId, int count, Object source)
	{
	}

	/**
	 * @param where where the store stands
	 * @return the goods available there, empty when there are none or the spot is not covered
	 */
	List<Offer> offers(Location where);

	/**
	 * Takes goods out of the supply for a sale in progress.
	 * @param source the line's source
	 * @param itemId the item
	 * @param count how many
	 * @return a receipt for {@link #paid} or {@link #restore}, or null when that many are no longer there
	 */
	Object withdraw(Object source, int itemId, int count);

	/**
	 * The sale went through.
	 * @param receipt the receipt from {@link #withdraw}
	 * @param total what the buyer paid for these goods, in adena
	 */
	void paid(Object receipt, long total);

	/**
	 * The sale fell through: puts the goods back.
	 * @param receipt the receipt from {@link #withdraw}
	 */
	void restore(Object receipt);
}
