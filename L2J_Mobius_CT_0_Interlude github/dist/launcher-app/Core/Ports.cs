using System;
using System.Net.Sockets;
using System.Threading;

namespace LivingWorld.Core;

// Loopback TCP probes used by the live status lights and by boot/stop waits.
// Same 600 ms connect timeout the PowerShell launcher used.
public static class Ports
{
    public const int Database = 3306;
    public const int Login = 2106;
    public const int Game = 7777;
    public const int Brain = 5000;

    public static bool IsOpen(int port, int timeoutMs = 600)
    {
        try
        {
            using var client = new TcpClient();
            var ar = client.BeginConnect("127.0.0.1", port, null, null);
            if (ar.AsyncWaitHandle.WaitOne(timeoutMs) && client.Connected)
            {
                client.EndConnect(ar);
                return true;
            }
            return false;
        }
        catch { return false; }
    }

    public static bool WaitOpen(int port, int timeoutSec, Func<bool>? cancelled = null)
    {
        for (int i = 0; i < timeoutSec; i++)
        {
            if (cancelled?.Invoke() == true) return false;
            if (IsOpen(port)) return true;
            Thread.Sleep(1000);
        }
        return false;
    }

    public static bool WaitClosed(int port, int timeoutSec)
    {
        for (int i = 0; i < timeoutSec; i++)
        {
            if (!IsOpen(port)) return true;
            Thread.Sleep(1000);
        }
        return false;
    }
}
