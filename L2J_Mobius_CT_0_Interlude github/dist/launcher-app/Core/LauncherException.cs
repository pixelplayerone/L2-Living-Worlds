using System;

namespace LivingWorld.Core;

// A boot/stop step that failed for a reason worth showing the player verbatim
// (missing jar, DB would not start, bad credentials). Thrown by the Core layer
// and caught by the UI, which prints Message to the log and stops the sequence.
public sealed class LauncherException : Exception
{
    public LauncherException(string message) : base(message) { }
}
