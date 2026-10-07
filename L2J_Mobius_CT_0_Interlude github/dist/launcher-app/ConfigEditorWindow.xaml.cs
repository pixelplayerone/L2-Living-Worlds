using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace LivingWorld;

// Hosts tools\l2admin\index.html as a desktop window (no browser). It injects
// tools\l2admin\native-bridge.js before the page loads and answers the page's
// file requests with normal .NET file access, so the editor reads and writes the
// server's .ini / .xml / crest files directly. The page opens straight to the
// server game folder, which is handed to it via __l2native.gameDir.
//
// This replaces the earlier PowerShell host (L2Admin-App.ps1): hosting WebView2
// from PowerShell failed to initialise on some machines with no surfaced error.
// A real WPF app initialises it reliably and any failure surfaces as an exception.
public partial class ConfigEditorWindow : Window
{
    private readonly string _indexPath;
    private readonly string _bridgePath;
    private readonly string? _gameDir;
    private readonly string _userDataFolder;

    public ConfigEditorWindow(string indexPath, string bridgePath, string? gameDir, string userDataFolder)
    {
        InitializeComponent();
        _indexPath = indexPath;
        _bridgePath = bridgePath;
        _gameDir = gameDir;
        _userDataFolder = userDataFolder;
        Loaded += OnLoaded;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(_userDataFolder);
            // Point the user-data folder somewhere writable (the exe may sit in a
            // read-only location); otherwise use the runtime's defaults.
            var env = await CoreWebView2Environment.CreateAsync(null, _userDataFolder, null);
            await Web.EnsureCoreWebView2Async(env);

            var core = Web.CoreWebView2;
            core.Settings.AreDefaultContextMenusEnabled = true;
            core.Settings.IsStatusBarEnabled = false;
            core.WebMessageReceived += OnWebMessage;

            // Inject the bridge, then the game-folder handoff, BEFORE navigating so
            // both run ahead of any page script (awaited, so ordering is guaranteed).
            var bridge = File.ReadAllText(_bridgePath);
            await core.AddScriptToExecuteOnDocumentCreatedAsync(bridge);
            var gameDirJson = JsonSerializer.Serialize(_gameDir); // quoted string, or null
            await core.AddScriptToExecuteOnDocumentCreatedAsync(
                $"window.__l2native && (window.__l2native.gameDir = {gameDirJson});");

            core.Navigate(new Uri(_indexPath).AbsoluteUri);
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "The config editor could not start its window.\n\n" + ex.Message +
                "\n\nIf this mentions the WebView2 Runtime, install the free 'Evergreen' " +
                "runtime from https://developer.microsoft.com/microsoft-edge/webview2/ and reopen it.",
                "L2 Config Editor", MessageBoxButton.OK, MessageBoxImage.Error);
            Close();
        }
    }

    // ---- file bridge: answer native-bridge.js requests ---------------------
    // Runs on the UI thread; the files are small .ini / .xml / crest images, so
    // synchronous IO here is fine.
    private void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        var core = Web.CoreWebView2;
        int rid = -1;
        try
        {
            var msg = JsonDocument.Parse(e.WebMessageAsJson).RootElement;
            rid = msg.GetProperty("rid").GetInt32();
            var op = msg.GetProperty("op").GetString();
            switch (op)
            {
                case "pickDir": PickDir(core, rid); break;
                case "pickFile": PickFile(core, rid); break;
                case "list": List(core, rid, Str(msg, "path")); break;
                case "stat": Stat(core, rid, Str(msg, "path")); break;
                case "readBytes": ReadBytes(core, rid, Str(msg, "path")); break;
                case "writeBytes": WriteBytes(core, rid, Str(msg, "path"), Str(msg, "base64")); break;
                case "mkdir": Mkdir(core, rid, Str(msg, "path")); break;
                default: Reply(core, rid, false, null, "unknown op: " + op, "NotSupportedError"); break;
            }
        }
        catch (Exception ex)
        {
            if (rid >= 0) Reply(core, rid, false, null, ex.Message, "InvalidStateError");
        }
    }

    private static string Str(JsonElement o, string k)
        => o.TryGetProperty(k, out var v) ? (v.GetString() ?? "") : "";

    private static void Reply(CoreWebView2 core, int rid, bool ok, object? result, string? error, string? name)
    {
        var payload = new Dictionary<string, object?> { ["rid"] = rid, ["ok"] = ok };
        if (ok) payload["result"] = result;
        else { payload["error"] = error; payload["name"] = name; }
        core.PostWebMessageAsJson(JsonSerializer.Serialize(payload));
    }

    private void PickDir(CoreWebView2 core, int rid)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Pick your server game folder (the one holding config and data)" };
        if (!string.IsNullOrEmpty(_gameDir) && Directory.Exists(_gameDir)) dlg.InitialDirectory = _gameDir;
        if (dlg.ShowDialog() == true)
        {
            var p = dlg.FolderName;
            Reply(core, rid, true, new Dictionary<string, object?> { ["path"] = p, ["name"] = LeafName(p) }, null, null);
        }
        else Reply(core, rid, true, new Dictionary<string, object?> { ["cancelled"] = true }, null, null);
    }

    private void PickFile(CoreWebView2 core, int rid)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "Config files (*.ini)|*.ini|All files (*.*)|*.*" };
        if (dlg.ShowDialog() == true)
        {
            var p = dlg.FileName;
            Reply(core, rid, true, new Dictionary<string, object?> { ["path"] = p, ["name"] = Path.GetFileName(p) }, null, null);
        }
        else Reply(core, rid, true, new Dictionary<string, object?> { ["cancelled"] = true }, null, null);
    }

    private static void List(CoreWebView2 core, int rid, string path)
    {
        var entries = new List<Dictionary<string, object?>>();
        if (Directory.Exists(path))
        {
            foreach (var d in Directory.EnumerateDirectories(path))
                entries.Add(new Dictionary<string, object?> { ["name"] = Path.GetFileName(d), ["kind"] = "directory" });
            foreach (var f in Directory.EnumerateFiles(path))
                entries.Add(new Dictionary<string, object?> { ["name"] = Path.GetFileName(f), ["kind"] = "file" });
        }
        Reply(core, rid, true, new Dictionary<string, object?> { ["entries"] = entries }, null, null);
    }

    private static void Stat(CoreWebView2 core, int rid, string path)
    {
        if (File.Exists(path))
            Reply(core, rid, true, new Dictionary<string, object?> { ["exists"] = true, ["kind"] = "file" }, null, null);
        else if (Directory.Exists(path))
            Reply(core, rid, true, new Dictionary<string, object?> { ["exists"] = true, ["kind"] = "directory" }, null, null);
        else
            Reply(core, rid, true, new Dictionary<string, object?> { ["exists"] = false }, null, null);
    }

    private static void ReadBytes(CoreWebView2 core, int rid, string path)
    {
        var bytes = File.ReadAllBytes(path);
        Reply(core, rid, true, new Dictionary<string, object?> { ["base64"] = Convert.ToBase64String(bytes) }, null, null);
    }

    private static void WriteBytes(CoreWebView2 core, int rid, string path, string base64)
    {
        var bytes = Convert.FromBase64String(base64);
        var dir = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        File.WriteAllBytes(path, bytes);
        Reply(core, rid, true, new Dictionary<string, object?> { ["ok"] = true }, null, null);
    }

    private static void Mkdir(CoreWebView2 core, int rid, string path)
    {
        Directory.CreateDirectory(path);
        Reply(core, rid, true, new Dictionary<string, object?> { ["ok"] = true }, null, null);
    }

    private static string LeafName(string path)
    {
        var p = path.TrimEnd('\\', '/');
        var i = p.LastIndexOfAny(new[] { '\\', '/' });
        return i >= 0 ? p.Substring(i + 1) : p;
    }
}
