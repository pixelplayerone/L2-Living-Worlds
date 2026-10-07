using System;
using System.Collections.Generic;
using System.IO;

namespace LivingWorld.Core;

// One Play button: pre-flight, DB engine, first-run schema, login, game, brain,
// then the client. Runs on a background thread; reports human-readable log lines
// and a 0..1 progress value with a stage label. Throws LauncherException on a
// step that cannot proceed; the caller shows the message and stops.
public sealed class Boot
{
    private readonly LauncherPaths _paths;
    private readonly Config _cfg;
    private readonly Action<string> _log;
    private readonly Action<string, double> _stage;

    public Boot(LauncherPaths paths, Config cfg, Action<string> log, Action<string, double> stage)
    {
        _paths = paths;
        _cfg = cfg;
        _log = log;
        _stage = stage;
    }

    public void Run()
    {
        _stage("Pre-flight", 0.03);
        PreFlight();

        _stage("Java runtime", 0.10);
        var java = Java.Find(_paths, _cfg.JavaHome)
            ?? throw new LauncherException("Could not find Java. Use the bundled pack, set the Java path in settings, or install JDK 25.");
        _log($"Java: {java}");

        var registry = new ProcessRegistry(_paths.RegistryPath);
        var db = new Database(_paths, _cfg, _log);
        var servers = new Servers(_paths, _cfg, _log);

        _stage("Database engine", 0.25);
        db.EnsureRunning();

        _stage("Database schema", 0.45);
        db.InstallSchemaIfNeeded();

        _stage("Login server", 0.62);
        _stage("Game server", 0.78);
        servers.StartLoginAndGame(registry, java);

        _stage("FPC brain", 0.85);
        servers.StartBrainIfEnabled(registry);

        if (_cfg.LaunchClient)
        {
            _stage("Game client", 0.90);
            servers.LaunchClientIfEnabled();
        }

        _stage("Ready", 1.0);
        _log("Server is up. Enjoy the Living World.");
    }

    // One screen of failures up front instead of dying three steps in.
    private void PreFlight()
    {
        var conflict = ProcessRegistry.RunningConflict(_paths.RegistryPath);
        if (conflict != null) throw new LauncherException(conflict);

        var problems = new List<string>();

        if (Java.Find(_paths, _cfg.JavaHome) == null)
            problems.Add("Java (JDK 25) not found. Use the bundled pack (it ships Java at dist\\jre\\), set the Java path in settings, or install JDK 25.");

        var mysqldExe = Path.Combine(_paths.ResolveRel(_cfg.MysqlBin), "mysqld.exe");
        if (!Ports.IsOpen(_cfg.DbPortNumber) && !File.Exists(mysqldExe))
            problems.Add($"Database engine not found: expected mysqld.exe at {mysqldExe} (fix the DB bin path in settings, or bundle MariaDB).");

        if (_cfg.StartLogin && !File.Exists(_paths.LoginJar))
            problems.Add("LoginServer.jar missing from libs\\ - build with 'ant' and include it in the pack.");
        if (_cfg.StartGame && !File.Exists(_paths.GameJar))
            problems.Add("GameServer.jar missing from libs\\ - build with 'ant' and include it in the pack.");

        if (problems.Count > 0)
        {
            _log("Cannot start yet - the following are missing:");
            foreach (var p in problems) _log("  - " + p);
            throw new LauncherException("Resolve the items above and try again. (A fully bundled pack ships Java + MariaDB + jars.)");
        }
        _log("Pre-flight OK.");
    }
}
