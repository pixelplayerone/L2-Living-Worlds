using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;

namespace LivingWorld.Core;

public sealed record NewsItem(string Title, string Tag, DateTimeOffset? Date, string Body);

public sealed record UpdateInfo(string InstalledTag, string LatestTag);

// News panel + patch/update, both driven by the public GitHub releases feed.
// Releases come in pairs: vX.Y.Z (full pack) and vX.Y.Z-patch (overlay). The
// news panel shows the full releases; the updater downloads and applies the
// matching -patch overlay. Faithful port of update.ps1's logic.
public sealed class Updater
{
    // Keep in sync with update.ps1.
    public const string UpdateRepo = "Teravibes/L2-Living-Worlds";

    private readonly LauncherPaths _paths;

    private static readonly HttpClient Http = CreateClient();

    public Updater(LauncherPaths paths) => _paths = paths;

    private static HttpClient CreateClient()
    {
        var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("LivingWorldLauncher");
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return http;
    }

    public string InstalledTag()
    {
        try
        {
            if (File.Exists(_paths.VersionPath))
                return File.ReadAllText(_paths.VersionPath).Trim();
        }
        catch { /* fall through */ }
        return "";
    }

    private async Task<JsonElement> FetchReleasesAsync()
    {
        var json = await Http.GetStringAsync($"https://api.github.com/repos/{UpdateRepo}/releases");
        return JsonDocument.Parse(json).RootElement;
    }

    // Full (non-patch, non-draft) releases, newest first, for the news panel.
    public async Task<List<NewsItem>> FetchNewsAsync()
    {
        var releases = await FetchReleasesAsync();
        var items = new List<NewsItem>();
        foreach (var rel in releases.EnumerateArray())
        {
            if (Bool(rel, "draft")) continue;
            var tag = Str(rel, "tag_name");
            if (tag.EndsWith("-patch", StringComparison.OrdinalIgnoreCase)) continue;

            var name = Str(rel, "name");
            if (string.IsNullOrWhiteSpace(name)) name = tag;
            DateTimeOffset? date = DateTimeOffset.TryParse(Str(rel, "published_at"), out var d) ? d : null;
            items.Add(new NewsItem(name, tag, date, Str(rel, "body").Trim()));
        }
        return items;
    }

    public async Task<UpdateInfo?> CheckAsync()
    {
        var releases = await FetchReleasesAsync();
        string latestBase = "", latestTag = "";
        foreach (var rel in releases.EnumerateArray())
        {
            if (Bool(rel, "draft")) continue;
            var tag = Str(rel, "tag_name");
            var b = BaseVersion(tag);
            if (b.Length == 0) continue;
            if (latestBase.Length == 0 || CompareBase(b, latestBase) > 0)
            {
                latestBase = b;
                // Prefer the plain vX.Y.Z tag over its -patch companion as the label.
                latestTag = "v" + b;
            }
        }
        if (latestBase.Length == 0) return null;

        var installed = InstalledTag();
        var installedBase = BaseVersion(installed);
        // No installed version stamp => treat as older than anything.
        if (installedBase.Length != 0 && CompareBase(installedBase, latestBase) >= 0)
            return null;

        return new UpdateInfo(installed.Length == 0 ? "(unknown)" : installed, latestTag);
    }

    // Download the -patch overlay for latestTag and apply it over the install.
    // stopServers is invoked first (the files are in use while the server runs).
    public async Task ApplyPatchAsync(string latestTag, IProgress<double> progress, Action<string> log, Action stopServers)
    {
        var releases = await FetchReleasesAsync();

        JsonElement patch = default;
        bool found = false;
        foreach (var rel in releases.EnumerateArray())
        {
            if (Str(rel, "tag_name").Equals($"{latestTag}-patch", StringComparison.OrdinalIgnoreCase))
            {
                patch = rel;
                found = true;
                break;
            }
        }
        if (!found)
            throw new LauncherException($"Found {latestTag} but no companion '{latestTag}-patch' release. Download the full {latestTag} pack manually to upgrade.");

        string? url = null, assetName = null;
        long size = 0;
        foreach (var asset in patch.GetProperty("assets").EnumerateArray())
        {
            var name = Str(asset, "name");
            if (!name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) continue;
            // Prefer an asset whose name mentions 'patch'.
            if (url == null || name.Contains("patch", StringComparison.OrdinalIgnoreCase))
            {
                url = Str(asset, "browser_download_url");
                assetName = name;
                size = asset.TryGetProperty("size", out var s) ? s.GetInt64() : 0;
            }
        }
        if (url == null)
            throw new LauncherException($"The {latestTag}-patch release has no .zip asset attached. Cannot auto-update.");

        log($"Patch download: {assetName} ({size / (1024.0 * 1024.0):0.0} MB)");

        log("Stopping the server before applying files ...");
        stopServers();

        var tmpRoot = Path.Combine(Path.GetTempPath(), "l2update_" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        Directory.CreateDirectory(tmpRoot);
        var zipPath = Path.Combine(tmpRoot, "patch.zip");
        try
        {
            log("Downloading patch ...");
            await DownloadAsync(url, zipPath, progress);
            log($"Downloaded {new FileInfo(zipPath).Length / (1024.0 * 1024.0):0.0} MB.");

            var extractDir = Path.Combine(tmpRoot, "extract");
            Directory.CreateDirectory(extractDir);
            log("Extracting ...");
            ZipFile.ExtractToDirectory(zipPath, extractDir, overwriteFiles: true);

            log($"Applying files to {_paths.DistDir} ...");
            Overlay(extractDir, _paths.DistDir);

            File.WriteAllText(_paths.VersionPath, latestTag);
            log($"Updated to {latestTag}. Press Play to run the updated build.");
        }
        finally
        {
            try { Directory.Delete(tmpRoot, true); } catch { /* best effort */ }
        }
    }

    // ---- the launcher exe itself -------------------------------------------
    // The exe is too large to ride the patch and ships as its own release asset.
    // A running exe cannot be overwritten on Windows, but it CAN be renamed, so
    // the new exe is swapped in via a rename dance and takes effect on restart.

    // Returns the download URL of a newer LivingWorld.exe on the latest full
    // release, or null if this exe is already current (or no asset exists).
    public async Task<string?> LauncherExeUpdateUrlAsync(string latestTag)
    {
        var running = Assembly.GetEntryAssembly()?.GetName().Version;
        var latestBase = BaseVersion(latestTag);
        if (running != null)
        {
            var runningBase = $"{running.Major}.{running.Minor}.{running.Build}";
            if (CompareBase(runningBase, latestBase) >= 0) return null;
        }

        var releases = await FetchReleasesAsync();
        foreach (var rel in releases.EnumerateArray())
        {
            if (!Str(rel, "tag_name").Equals(latestTag, StringComparison.OrdinalIgnoreCase)) continue;
            if (!rel.TryGetProperty("assets", out var assets)) return null;
            foreach (var asset in assets.EnumerateArray())
                if (Str(asset, "name").Equals("LivingWorld.exe", StringComparison.OrdinalIgnoreCase))
                    return Str(asset, "browser_download_url");
        }
        return null;
    }

    public async Task RefreshLauncherExeAsync(string url, IProgress<double> progress, Action<string> log)
    {
        var current = Environment.ProcessPath
            ?? throw new LauncherException("Cannot locate the running launcher exe to update it.");
        var tmp = current + ".new";
        var old = current + ".old";

        log("Downloading the updated launcher ...");
        await DownloadAsync(url, tmp, progress);

        try { if (File.Exists(old)) File.Delete(old); } catch { /* a prior .old still locked - overwritten below fails loudly */ }
        File.Move(current, old);   // free the LivingWorld.exe name (allowed while running)
        File.Move(tmp, current);   // put the new exe in its place
        log("Launcher updated. Close and reopen LivingWorld.exe to run the new version.");
    }

    // Delete a leftover LivingWorld.exe.old from a previous self-update. Safe to
    // call on every startup; does nothing if there is none.
    public static void CleanupOldExe()
    {
        try
        {
            var current = Environment.ProcessPath;
            if (current == null) return;
            var old = current + ".old";
            if (File.Exists(old)) File.Delete(old);
        }
        catch { /* still locked or gone - ignore, retried next launch */ }
    }

    private static async Task DownloadAsync(string url, string dest, IProgress<double> progress)
    {
        using var resp = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? -1L;

        await using var input = await resp.Content.ReadAsStreamAsync();
        await using var output = File.Create(dest);
        var buffer = new byte[81920];
        long readTotal = 0;
        int n;
        while ((n = await input.ReadAsync(buffer)) > 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, n));
            readTotal += n;
            if (total > 0) progress.Report(Math.Clamp((double)readTotal / total, 0, 1));
        }
        progress.Report(1);
    }

    // Merge the extracted tree onto the install, overwriting changed files and
    // leaving everything else (the database, untouched configs) alone. Never
    // copies a 'mariadb' folder, and drops the patch's own readme. Mirrors the
    // robocopy /XD mariadb /XF PATCH-README.txt call in update.ps1.
    private static void Overlay(string src, string dst)
    {
        // The launcher cannot overwrite its own running exe on Windows (the file is
        // locked), so a patch never replaces it. The pack ships LivingWorld.exe out
        // of patches by design; this is belt-and-braces if one ever slips in.
        var runningExe = Environment.ProcessPath;

        Directory.CreateDirectory(dst);
        foreach (var dir in Directory.GetDirectories(src))
        {
            var name = Path.GetFileName(dir);
            if (name.Equals("mariadb", StringComparison.OrdinalIgnoreCase)) continue;
            Overlay(dir, Path.Combine(dst, name));
        }
        foreach (var file in Directory.GetFiles(src))
        {
            var name = Path.GetFileName(file);
            if (name.Equals("PATCH-README.txt", StringComparison.OrdinalIgnoreCase)) continue;
            var target = Path.Combine(dst, name);
            if (runningExe != null &&
                string.Equals(Path.GetFullPath(target), Path.GetFullPath(runningExe), StringComparison.OrdinalIgnoreCase))
                continue;
            File.Copy(file, target, overwrite: true);
        }
    }

    // ---- version helpers (port of Get-BaseVersion / Compare-BaseVersion) ----

    public static string BaseVersion(string tag)
    {
        var t = (tag ?? "").Trim();
        if (t.Length == 0) return "";
        if (t.StartsWith('v') || t.StartsWith('V')) t = t[1..];
        int dash = t.IndexOf('-');
        if (dash >= 0) t = t[..dash];
        return t;
    }

    public static int CompareBase(string a, string b)
    {
        var pa = a.Split('.');
        var pb = b.Split('.');
        int n = Math.Max(pa.Length, pb.Length);
        for (int i = 0; i < n; i++)
        {
            int ia = i < pa.Length && int.TryParse(pa[i], out var x) ? x : 0;
            int ib = i < pb.Length && int.TryParse(pb[i], out var y) ? y : 0;
            if (ia != ib) return ia < ib ? -1 : 1;
        }
        return 0;
    }

    private static string Str(JsonElement e, string prop) =>
        e.TryGetProperty(prop, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static bool Bool(JsonElement e, string prop) =>
        e.TryGetProperty(prop, out var v) && (v.ValueKind == JsonValueKind.True);
}
