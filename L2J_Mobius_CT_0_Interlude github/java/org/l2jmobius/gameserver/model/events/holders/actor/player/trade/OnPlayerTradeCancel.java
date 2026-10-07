package org.l2jmobius.gameserver.model.events.holders.actor.player.trade;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.IBaseEvent;

/**
 * @author Heelix
 */
public class OnPlayerTradeCancel implements IBaseEvent {

    private final Player _sender;
    private final Player _receiver;
    private final Player _cancelledBy;

    public OnPlayerTradeCancel(Player sender, Player receiver, Player cancelledBy)
    {
        this._sender = sender;
        this._receiver = receiver;
        this._cancelledBy = cancelledBy;
    }

    public Player getSender() {
        return _sender;
    }

    public Player getReceiver() {
        return _receiver;
    }

    public Player getCancelledBy() {
        return _cancelledBy;
    }

    @Override
    public EventType getType() {
        return EventType.ON_PLAYER_TRADE_CANCEL;
    }
}
