using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace LivingWorld.Core;

// Starts the database engine and, on a first run only, installs the schema.
// Faithful port of steps 2 and 3 of launcher.ps1, including the safety guard
// that never imports over a database that already has tables.
public sealed class Database
{
    private readonly LauncherPaths _paths;
    private readonly Config _cfg;
    private readonly Action<string> _log;

    private readonly string _mysqlBin;
    private readonly string _mysqlExe;
    private readonly string _mysqldExe;
    private readonly string _dataDir;

    public Database(LauncherPaths paths, Config cfg, Action<string> log)
    {
        _paths = paths;
        _cfg = cfg;
        _log = log;
        _mysqlBin = paths.ResolveRel(cfg.MysqlBin);
        _mysqlExe = Path.Combine(_mysqlBin, "mysql.exe");
        _mysqldExe = Path.Combine(_mysqlBin, "mysqld.exe");
        _dataDir = cfg.BundledDb ? paths.ResolveRel(cfg.DataDir) : "";
    }

    public void EnsureRunning()
    {
        int port = _cfg.DbPortNumber;
        if (Ports.IsOpen(port))
        {
            _log($"Database already running on port {port}.");
            return;
        }

        if (!_cfg.AutoStartMysql)
            throw new LauncherException($"Nothing is listening on port {port} and AutoStartMysql=false. Start your DB and retry.");

        if (!File.Exists(_mysqldExe))
            throw new LauncherException($"DB not running and mysqld.exe not found at {_mysqldExe}. Fix MysqlBin in launcher.ini.");

        if (_cfg.BundledDb)
        {
            InitBundledIfNeeded();
            _log($"Starting bundled MariaDB (port {port}) ...");
            var args = $"--datadir=\"{_dataDir}\" --port={port} --skip-name-resolve --console";
            Proc.StartHidden(_mysqldExe, args, _mysqlBin);
        }
        else
        {
            _log($"Starting mysqld from {_mysqlBin} ...");
            Proc.StartHidden(_mysqldExe, "", _mysqlBin);
        }

        if (Ports.WaitOpen(port, 60))
            _log($"Database is up on port {port}.");
        else
            throw new LauncherException(
                $"The database did not open port {port} within 60s. Check its hidden console for errors, and avoid folder paths with spaces.");
    }

    private void InitBundledIfNeeded()
    {
        bool needInit = !Directory.Exists(Path.Combine(_dataDir, "mysql"))
                     && !File.Exists(Path.Combine(_dataDir, "ibdata1"));
        if (!needInit) return;

        _log($"First run - initializing bundled MariaDB data dir at {_dataDir} ...");
        // A partial dir left by a failed attempt breaks init - start clean.
        if (Directory.Exists(_dataDir))
        {
            try { Directory.Delete(_dataDir, true); } catch { /* best effort */ }
        }
        Directory.CreateDirectory(_dataDir);

        string? installExe = new[] { "mariadb-install-db.exe", "mysql_install_db.exe" }
            .Select(n => Path.Combine(_mysqlBin, n))
            .FirstOrDefault(File.Exists);
        if (installExe == null)
            throw new LauncherException($"Bundled MariaDB is missing mariadb-install-db.exe / mysql_install_db.exe in {_mysqlBin}.");

        // The Windows mariadb-install-db.exe only accepts --datadir; a plain init
        // creates root@localhost with an empty password, which is what the server
        // config expects. Progress is logged to stderr, so it is not fatal here.
        var r = Proc.Run(installExe, new[] { $"--datadir={_dataDir}" }, _mysqlBin);
        foreach (var line in SplitLines(r.StdOut).Concat(SplitLines(r.StdErr)))
            _log("    " + line);
        if (r.ExitCode != 0)
            throw new LauncherException($"MariaDB data dir initialization failed (code {r.ExitCode}). See messages above.");
        _log("MariaDB data dir initialized.");
    }

    public void InstallSchemaIfNeeded()
    {
        if (File.Exists(_paths.MarkerPath))
        {
            _log("Database schema already installed.");
            return;
        }
        if (!File.Exists(_mysqlExe))
            throw new LauncherException($"First-run install needs mysql.exe at {_mysqlExe}. Fix MysqlBin in launcher.ini.");

        var baseArgs = BaseMysqlArgs();
        var dbName = _cfg.Database;

        // SAFETY: never import over a database that already has tables - several
        // SQL files DROP TABLE before recreating, which would wipe a live server.
        var count = Proc.Run(_mysqlExe, baseArgs.Concat(new[]
        {
            "-N", "-B", "-e",
            $"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{dbName}';"
        }).ToArray());
        if (count.ExitCode != 0)
            throw new LauncherException("Could not connect to the database. Check DB credentials in launcher.ini.");

        if (int.TryParse(count.StdOut.Trim(), out var tables) && tables > 0)
        {
            _log($"Existing database '{dbName}' detected ({tables} tables) - skipping install to protect your data.");
            File.WriteAllText(_paths.MarkerPath, $"pre-existing db, install skipped {DateTime.Now:s}");
            return;
        }

        _log($"First run detected, empty database - installing '{dbName}' schema ...");
        var create = Proc.Run(_mysqlExe, baseArgs.Concat(new[]
        {
            "-e", $"CREATE DATABASE IF NOT EXISTS `{dbName}` CHARACTER SET utf8 COLLATE utf8_unicode_ci;"
        }).ToArray());
        if (create.ExitCode != 0)
            throw new LauncherException("Could not connect / create database. Check DB credentials in launcher.ini.");

        var dbArgs = baseArgs.Concat(new[] { dbName }).ToArray();
        foreach (var group in new[] { "login", "game" })
        {
            var sqlDir = Path.Combine(_paths.DbInstallerDir, "sql", group);
            if (!Directory.Exists(sqlDir))
            {
                _log($"No {group} SQL folder, skipping.");
                continue;
            }
            var files = Directory.GetFiles(sqlDir, "*.sql").OrderBy(Path.GetFileName, StringComparer.Ordinal).ToList();
            _log($"{group} : {files.Count} tables");
            foreach (var f in files)
            {
                var sql = File.ReadAllText(f);
                var import = Proc.Run(_mysqlExe, dbArgs, stdinText: sql);
                if (import.ExitCode != 0)
                    throw new LauncherException($"Error importing {Path.GetFileName(f)}");
            }
        }

        File.WriteAllText(_paths.MarkerPath, $"installed {DateTime.Now:s}");
        _log("Schema installed.");
    }

    private string[] BaseMysqlArgs()
    {
        var args = new List<string> { "-h", _cfg.DbHost, "-P", _cfg.DbPort, "-u", _cfg.DbUser };
        if (!string.IsNullOrEmpty(_cfg.DbPassword)) args.Add($"--password={_cfg.DbPassword}");
        return args.ToArray();
    }

    private static IEnumerable<string> SplitLines(string s) =>
        string.IsNullOrEmpty(s)
            ? Array.Empty<string>()
            : s.Split('\n').Select(l => l.TrimEnd('\r')).Where(l => l.Length > 0);
}
