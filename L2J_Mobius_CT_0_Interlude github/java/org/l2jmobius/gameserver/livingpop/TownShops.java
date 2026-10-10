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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.l2jmobius.gameserver.managers.FakePlayerStoreSupply;
import org.l2jmobius.gameserver.model.Location;

/**
 * Puts a town's stock into the shops of the fake-player vendors nearby, and pays the bots whose items sell. A shop line
 * is an offer of one item in one town; a purchase takes the items out of the stock and pays each owner their share.
 */
public final class TownShops implements FakePlayerStoreSupply
{
	/** The farthest a vendor can stand from a town's arrival point and still sell its stock. */
	private static final long MAX_DISTANCE = 6000L;

	private final TownStock _stock;
	private final Supplier<List<ZoneCatalog.Town>> _towns;
	private final PayOwner _pay;

	/** Pays a bot: the owner's id and the adena. */
	@FunctionalInterface
	public interface PayOwner
	{
		void pay(long owner, long adena);
	}

	/**
	 * @param stock the town stock
	 * @param towns the towns, for finding the nearest one to a shop
	 * @param pay pays an owner
	 */
	public TownShops(TownStock stock, Supplier<List<ZoneCatalog.Town>> towns, PayOwner pay)
	{
		_stock = stock;
		_towns = towns;
		_pay = pay;
	}

	@Override
	public List<Offer> offers(Location where)
	{
		final String town = nearest(where.getX(), where.getY());
		final Map<Integer, Long> items = (town == null) ? null : _stock.totals().get(town);
		final List<Offer> offers = new ArrayList<>();
		if (items != null)
		{
			items.forEach((itemId, count) -> offers.add(new Offer(itemId, (int) Math.min(Integer.MAX_VALUE, count), town)));
		}
		return offers;
	}

	@Override
	public int available(Object source, int itemId)
	{
		return (int) Math.min(Integer.MAX_VALUE, _stock.count((String) source, itemId));
	}

	@Override
	public Object withdraw(Object source, int itemId, int count)
	{
		return _stock.take((String) source, itemId, count);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void paid(Object receipt, long total)
	{
		final List<TownStock.Listing> taken = (List<TownStock.Listing>) receipt;
		final List<Long> shares = TownStock.shares(taken, total);
		for (int i = 0; i < taken.size(); i++)
		{
			_pay.pay(taken.get(i).owner(), shares.get(i));
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void restore(Object receipt)
	{
		_stock.restore((List<TownStock.Listing>) receipt);
	}

	/** @return the name of the town nearest to a point, or null when none is within range */
	private String nearest(int x, int y)
	{
		String best = null;
		long bestDistance = MAX_DISTANCE * MAX_DISTANCE;
		for (ZoneCatalog.Town town : _towns.get())
		{
			final long dx = x - town.arrival().x();
			final long dy = y - town.arrival().y();
			final long distance = (dx * dx) + (dy * dy);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = town.name();
			}
		}
		return best;
	}
}
