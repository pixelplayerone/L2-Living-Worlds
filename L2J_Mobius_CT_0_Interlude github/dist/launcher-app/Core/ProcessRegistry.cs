using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text.Json;

namespace LivingWorld.Core;

public sealed class ProcessRecord
{
    public string Role { get; set; } = "";
    public int Id { get; set; }
    public string StartTicks { get; set; } = "";
    public string ProcessName { get; set; } = "";
    public string Marker { get; set; } = "";
}

// A record of the processes THIS launcher started, so Stop only ever kills its
// own processes. A PID alone is not enough (Windows reuses them), so each record
// also carries the process start time and image name; both must still match
// before a process is terminated. Port of the .processes.json logic shared by
// launcher.ps1 and stop.ps1, and interoperable with them (same file, same shape).
public sealed class ProcessRegistry
{
    private readonly string _path;
    private readonly List<ProcessRecord> _records = new();

    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

    public ProcessRegistry(string path) => _path = path;

    public void Register(string role, Process process, string marker)
    {
        System.Threading.Thread.Sleep(100);
        process.Refresh();
        if (process.HasExited)
            throw new LauncherException($"{role} exited immediately after launch.");

        _records.Add(new ProcessRecord
        {
            Role = role,
            Id = process.Id,
            StartTicks = SafeStartTicks(process),
            ProcessName = process.ProcessName,
            Marker = marker
        });
        Save();
    }

    private void Save()
    {
        var tmp = _path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(_records, JsonOpts));
        File.Copy(tmp, _path, true);
        File.Delete(tmp);
    }

    public static List<ProcessRecord> Load(string path)
    {
        if (!File.Exists(path)) return new();
        return JsonSerializer.Deserialize<List<ProcessRecord>>(File.ReadAllText(path)) ?? new();
    }

    // If a previously recorded server is still alive (same PID, start time and
    // image name), report it so the caller can refuse to start a second copy.
    // Clears a stale registry as a side effect. Mirrors Assert-NoManagedProcesses.
    public static string? RunningConflict(string path)
    {
        if (!File.Exists(path)) return null;

        List<ProcessRecord> records;
        try { records = Load(path); }
        catch { return $"Process registry is unreadable: {path}. Remove it before starting."; }

        foreach (var r in records)
        {
            if (IsAlive(r))
                return $"{r.Role} is already running (PID {r.Id}). Stop the server first.";
        }

        try { File.Delete(path); } catch { /* best effort */ }
        return null;
    }

    public static bool IsAlive(ProcessRecord r)
    {
        try
        {
            var p = Process.GetProcessById(r.Id);
            return SafeStartTicks(p) == r.StartTicks && p.ProcessName == r.ProcessName;
        }
        catch { return false; }
    }

    private static string SafeStartTicks(Process p)
    {
        try { return p.StartTime.ToUniversalTime().Ticks.ToString(); }
        catch { return ""; }
    }
}
