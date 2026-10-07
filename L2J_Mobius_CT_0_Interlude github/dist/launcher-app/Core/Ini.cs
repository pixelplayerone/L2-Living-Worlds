using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace LivingWorld.Core;

// Minimal INI reader/writer for launcher.ini. Section and key lookups are
// case-insensitive. Writes edit the file in place so launcher.ini keeps all of
// its comments: an existing key is updated where it sits, a new key is appended
// to the end of its section, and a missing section is created at the end.
public sealed class Ini
{
    private readonly string _path;
    private readonly List<string> _lines;

    public Ini(string path)
    {
        _path = path;
        _lines = File.Exists(path)
            ? new List<string>(File.ReadAllLines(path))
            : new List<string>();
    }

    public string Get(string section, string key, string def = "")
    {
        var current = "";
        foreach (var raw in _lines)
        {
            var line = raw.Trim();
            if (line.Length == 0 || line[0] == '#' || line[0] == ';') continue;
            if (line.StartsWith('[') && line.EndsWith(']'))
            {
                current = line.Substring(1, line.Length - 2).Trim();
                continue;
            }
            int idx = line.IndexOf('=');
            if (idx < 0 || current.Length == 0) continue;
            if (string.Equals(current, section, StringComparison.OrdinalIgnoreCase) &&
                string.Equals(line[..idx].Trim(), key, StringComparison.OrdinalIgnoreCase))
            {
                var val = line[(idx + 1)..].Trim();
                return val.Length == 0 ? def : val;
            }
        }
        return def;
    }

    public bool GetBool(string section, string key, bool def)
    {
        var v = Get(section, key, def ? "true" : "false").ToLowerInvariant();
        return v is "true" or "1" or "yes" or "on";
    }

    public void Set(string section, string key, string value)
    {
        int sectionStart = -1, sectionEnd = _lines.Count;
        for (int i = 0; i < _lines.Count; i++)
        {
            var line = _lines[i].Trim();
            if (line.StartsWith('[') && line.EndsWith(']'))
            {
                var name = line.Substring(1, line.Length - 2).Trim();
                if (string.Equals(name, section, StringComparison.OrdinalIgnoreCase))
                {
                    sectionStart = i;
                }
                else if (sectionStart >= 0)
                {
                    sectionEnd = i;
                    break;
                }
                continue;
            }
            if (sectionStart < 0) continue;
            if (line.Length == 0 || line[0] == '#' || line[0] == ';') continue;
            int idx = line.IndexOf('=');
            if (idx <= 0) continue;
            if (string.Equals(line[..idx].Trim(), key, StringComparison.OrdinalIgnoreCase))
            {
                var original = _lines[i];
                var lead = original[..(original.Length - original.TrimStart().Length)];
                _lines[i] = $"{lead}{key}={value}";
                return;
            }
        }

        if (sectionStart < 0)
        {
            if (_lines.Count > 0 && _lines[^1].Trim().Length != 0) _lines.Add("");
            _lines.Add($"[{section}]");
            _lines.Add($"{key}={value}");
            return;
        }

        int insertAt = sectionEnd;
        while (insertAt - 1 > sectionStart && _lines[insertAt - 1].Trim().Length == 0) insertAt--;
        _lines.Insert(insertAt, $"{key}={value}");
    }

    public void Save()
    {
        var tmp = _path + ".tmp";
        File.WriteAllLines(tmp, _lines, new UTF8Encoding(false));
        File.Copy(tmp, _path, true);
        File.Delete(tmp);
    }
}
