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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.Shutdown;

/**
 * Lets the launcher stop the server cleanly.
 * <p>
 * The launcher starts the game server without a console, so it cannot type a shutdown command, and Windows has no
 * signal that runs the Java shutdown hook. Killing the process skips every save, so players lost everything since the
 * last periodic store. Instead the launcher creates {@code shutdown.request} in the game server's working folder (the
 * pack's {@code game\} folder). This manager checks for that file every second, deletes it as an acknowledgement and
 * runs the normal shutdown, which saves and disconnects every player and stores the server data before exit.
 */
public class ShutdownRequestManager
{
	private static final Logger LOGGER = Logger.getLogger(ShutdownRequestManager.class.getName());
	
	private static final Path FILE = Path.of("shutdown.request");
	private static final long INTERVAL_MS = 1000;
	
	private boolean _requested;
	private boolean _warned;
	
	protected ShutdownRequestManager()
	{
		// A request left behind by an earlier run must not stop this one as soon as it starts.
		try
		{
			Files.deleteIfExists(FILE);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "ShutdownRequestManager: could not remove an old " + FILE.toAbsolutePath() + ": " + e.getMessage());
		}
		
		ThreadPool.scheduleAtFixedRate(this::check, INTERVAL_MS, INTERVAL_MS);
	}
	
	private void check()
	{
		if (_requested || !Files.exists(FILE))
		{
			return;
		}
		
		try
		{
			Files.deleteIfExists(FILE);
		}
		catch (IOException e)
		{
			// The launcher treats a file that stays as "not understood" and stops the process the hard way, so keep trying.
			if (!_warned)
			{
				LOGGER.log(Level.WARNING, "ShutdownRequestManager: could not remove " + FILE.toAbsolutePath() + ": " + e.getMessage());
				_warned = true;
			}
			return;
		}
		
		_requested = true;
		LOGGER.info("ShutdownRequestManager: shutdown requested by the launcher. Saving and shutting down.");
		Shutdown.getInstance().startShutdown(null, 0, false);
	}
	
	public static ShutdownRequestManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final ShutdownRequestManager INSTANCE = new ShutdownRequestManager();
	}
}
