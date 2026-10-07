package org.l2jmobius.gameserver.model.events.holders.actor.player.trade;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.IBaseEvent;

/**
 * @author Heelix
 */
public class OnPlayerTradeStart implements IBaseEvent {

    private final Player _sender;
    private final Player _receiver;

    public OnPlayerTradeStart(Player sender, Player receiver)
    {
        this._sender = sender;
        this._receiver = receiver;
    }

    public Player getSender() {
        return _sender;
    }

    public Player getReceiver() {
        return _receiver;
    }

    @Override
    public EventType getType() {
        return EventType.ON_PLAYER_TRADE_START;
    }
}
