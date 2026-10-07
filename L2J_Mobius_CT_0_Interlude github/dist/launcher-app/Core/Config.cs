namespace LivingWorld.Core;

// A snapshot of launcher.ini, with the same keys and defaults the PowerShell
// launcher used. Loaded fresh whenever the launcher needs current settings.
public sealed class Config
{
    public string JavaHome = "";

    public string DbHost = "localhost";
    public string DbPort = "3306";
    public string DbUser = "root";
    public string DbPassword = "";
    public string Database = "l2jmobiusinterlude";
    public string MysqlBin = @"C:\xampp\mysql\bin";
    public string DataDir = "";
    public bool AutoStartMysql = true;

    public bool StartLogin = true;
    public bool StartGame = true;
    public bool StartBrain = false;

    public string ClientExe = "";
    public bool LaunchClient = false;

    public static Config Load(Ini ini) => new()
    {
        JavaHome = ini.Get("java", "JavaHome", ""),

        DbHost = ini.Get("database", "Host", "localhost"),
        DbPort = ini.Get("database", "Port", "3306"),
        DbUser = ini.Get("database", "User", "root"),
        DbPassword = ini.Get("database", "Password", ""),
        Database = ini.Get("database", "Database", "l2jmobiusinterlude"),
        MysqlBin = ini.Get("database", "MysqlBin", @"C:\xampp\mysql\bin"),
        DataDir = ini.Get("database", "DataDir", ""),
        AutoStartMysql = ini.GetBool("database", "AutoStartMysql", true),

        StartLogin = ini.GetBool("servers", "StartLogin", true),
        StartGame = ini.GetBool("servers", "StartGame", true),
        StartBrain = ini.GetBool("servers", "StartBrain", false),

        ClientExe = ini.Get("client", "ClientExe", ""),
        LaunchClient = ini.GetBool("client", "LaunchClient", false),
    };

    public bool BundledDb => !string.IsNullOrEmpty(DataDir);
    public int DbPortNumber => int.TryParse(DbPort, out var p) ? p : 3306;
}
