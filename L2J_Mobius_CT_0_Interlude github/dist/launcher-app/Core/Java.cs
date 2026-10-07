using System;
using System.IO;

namespace LivingWorld.Core;

// Locates a JDK/JRE the same way launcher.ps1 Find-Java does, in the same order:
// an explicit JavaHome, the bundled dist\jre, the JAVA_HOME environment
// variable, then java.exe on PATH. Returns null if none is found.
public static class Java
{
    public static string? Find(LauncherPaths paths, string javaHome)
    {
        if (!string.IsNullOrWhiteSpace(javaHome))
        {
            var c = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(c)) return c;
        }

        var bundled = Path.Combine(paths.DistDir, "jre", "bin", "java.exe");
        if (File.Exists(bundled)) return bundled;

        var env = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(env))
        {
            var c = Path.Combine(env, "bin", "java.exe");
            if (File.Exists(c)) return c;
        }

        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in path.Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(dir)) continue;
            try
            {
                var c = Path.Combine(dir.Trim(), "java.exe");
                if (File.Exists(c)) return c;
            }
            catch { /* malformed PATH entry - ignore */ }
        }
        return null;
    }

    // Prefer javaw.exe next to java.exe so the servers run without a console
    // window (their own Swing GUI console still opens). Falls back to java.exe.
    public static string ServerExe(string javaExe)
    {
        var dir = Path.GetDirectoryName(javaExe);
        if (dir != null)
        {
            var javaw = Path.Combine(dir, "javaw.exe");
            if (File.Exists(javaw)) return javaw;
        }
        return javaExe;
    }
}
