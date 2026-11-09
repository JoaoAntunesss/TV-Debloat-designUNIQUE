// TV Debloat: turns an Android / Google TV into the same setup as TV-Debloat-designUNIQUE, with a simple window.
// The window is a small web page (ui.html) shown in an Edge app window; this program is the local server behind it
// and talks to the TV with Google's adb over Wi-Fi. Builds with the C# compiler that ships with Windows (see build-app.ps1).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

[assembly: AssemblyTitle("TV Debloat")]
[assembly: AssemblyProduct("TV Debloat")]
[assembly: AssemblyDescription("Deixa a TV Android sem anúncios, com um ecrã inicial novo e YouTube sem anúncios.")]
[assembly: AssemblyCompany("João Antunes")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace TvDebloat
{
    class Bloat
    {
        public string Pkg, Label;
        public bool NeedsNewHome;
        public Bloat(string pkg, string label, bool needsNewHome) { Pkg = pkg; Label = label; NeedsNewHome = needsNewHome; }

        public static readonly Bloat[] All = {
            new Bloat("com.tcl.ttvs", "TCL: estatísticas e ponte para a Alexa", false),
            new Bloat("com.tcl.usercenter", "TCL: conta e ligação ao telemóvel", false),
            new Bloat("com.tcl.messagebox", "TCL: mensagens promocionais", false),
            new Bloat("com.tcl.esticker", "TCL: autocolantes publicitários no ecrã", false),
            new Bloat("com.tcl.partnercustomizer", "TCL: filas de apps patrocinadas", false),
            new Bloat("com.tcl.logkit", "TCL: recolha de registos", false),
            new Bloat("com.tcl.exhibit", "TCL: modo de demonstração de loja", false),
            new Bloat("com.tcl.channelplus", "TCL Channel", false),
            new Bloat("com.tcl.waterfall.overseas", "TCL: separador de conteúdos", false),
            new Bloat("com.tcl.tv.tclhome_passive", "TCL: ecrã de fundo", false),
            new Bloat("com.tcl.guard", "TCL: limpeza (TV Guard)", false),
            new Bloat("com.tcl.smartalexa", "Alexa da TCL", false),
            new Bloat("com.google.android.apps.tv.dreamx", "Google: protetor de ecrã com recomendações", false),
            new Bloat("com.google.android.videos", "Google TV / Play Filmes", false),
            new Bloat("com.google.android.play.games", "Google Play Jogos", false),
            new Bloat("com.google.android.feedback", "Google: envio de feedback", false),
            new Bloat("com.google.android.apps.tv.launcherx", "Ecrã inicial do Google TV, com anúncios", true),
            new Bloat("com.google.android.tungsten.setupwraith", "Assistente que se apodera do botão Home", true),
        };
    }

    static class Adb
    {
        public static string Exe, Serial;
        public static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TVDebloat");

        public static void Ensure(Action<string> say)
        {
            Directory.CreateDirectory(Home);
            string own = Path.Combine(Home, "platform-tools", "adb.exe");
            string sdk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe");
            if (File.Exists(own)) { Exe = own; return; }
            if (File.Exists(sdk)) { Exe = sdk; return; }
            say("A descarregar as ferramentas oficiais da Google (cerca de 7 MB)…");
            string zip = Path.Combine(Home, "platform-tools.zip");
            using (var wc = new WebClient()) wc.DownloadFile("https://dl.google.com/android/repository/platform-tools-latest-windows.zip", zip);
            string dir = Path.Combine(Home, "platform-tools");
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            ZipFile.ExtractToDirectory(zip, Home);
            File.Delete(zip);
            Exe = own;
        }

        public static string Run(string args, int timeoutMs)
        {
            var psi = new ProcessStartInfo(Exe, args)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = Encoding.UTF8
            };
            using (var p = Process.Start(psi))
            {
                var o = p.StandardOutput.ReadToEndAsync();
                var e = p.StandardError.ReadToEndAsync();
                if (!p.WaitForExit(timeoutMs)) { try { p.Kill(); } catch { } return "timeout"; }
                return (o.Result + e.Result).Replace("\r", "").Trim();
            }
        }
        public static string Run(string args) { return Run(args, 60000); }
        public static string Sh(string cmd) { return Run("-s " + Serial + " shell " + cmd, 120000); }

        public static string State()
        {
            foreach (var line in Run("devices").Split('\n'))
            {
                var parts = line.Trim().Split('\t');
                if (parts.Length == 2 && parts[0] == Serial) return parts[1].Trim();
            }
            return "offline";
        }

        public static string Install(string apk, string name)
        {
            string remote = "/data/local/tmp/" + name;
            string r = Run("-s " + Serial + " push \"" + apk + "\" " + remote, 600000);
            r += "\n" + Run("-s " + Serial + " shell pm install -r " + remote, 600000);
            Sh("rm -f " + remote);
            return r;
        }
    }

    /** What the window shows: read by the page every half second. */
    class Job
    {
        public string Phase = "idle";        // idle, working, connected, done, error
        public string Msg = "";
        public int Progress;
        public List<string> Log = new List<string>();
        public string Model = "", Abi = "", Ip = "";
        public List<Bloat> Present = new List<Bloat>();
    }

    class App
    {
        static readonly object L = new object();
        static Job job = new Job();
        static DateTime lastPing = DateTime.Now;

        static void Set(string phase, string msg) { lock (L) { if (phase != null) job.Phase = phase; if (msg != null) job.Msg = msg; } }
        static void Say(string line) { lock (L) job.Log.Add(line); }
        static void Prog(int p) { lock (L) job.Progress = Math.Max(0, Math.Min(100, p)); }

        // -------------------------------------------------------------- server
        [STAThread]
        static void Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            int port = ((IPEndPoint)listener.LocalEndpoint).Port;
            string url = "http://127.0.0.1:" + port + "/";
            OpenWindow(url);
            new Thread(() =>
            {
                while (true)
                {
                    Thread.Sleep(5000);
                    bool busy; lock (L) busy = job.Phase == "working";
                    if (!busy && (DateTime.Now - lastPing).TotalSeconds > 25) Environment.Exit(0);
                }
            }) { IsBackground = true }.Start();
            while (true)
            {
                var c = listener.AcceptTcpClient();
                ThreadPool.QueueUserWorkItem(_ => { try { Serve(c); } catch { } finally { c.Close(); } });
            }
        }

        static void OpenWindow(string url)
        {
            string[] edges = {
                Environment.ExpandEnvironmentVariables(@"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
                Environment.ExpandEnvironmentVariables(@"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
            };
            string profile = Path.Combine(Adb.Home, "janela");
            Directory.CreateDirectory(profile);
            foreach (var e in edges)
                if (File.Exists(e))
                {
                    Process.Start(e, "--app=" + url + " --window-size=1180,780 --user-data-dir=\"" + profile + "\" --no-first-run --no-default-browser-check");
                    return;
                }
            Process.Start(url);   // default browser
        }

        static byte[] Res(string name)
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (s == null) return null;
                var m = new MemoryStream(); s.CopyTo(m); return m.ToArray();
            }
        }

        static void Serve(TcpClient c)
        {
            c.ReceiveTimeout = 15000;
            var stream = c.GetStream();
            var head = new StringBuilder();
            var buf = new List<byte>();
            int b, crlf = 0;
            while ((b = stream.ReadByte()) >= 0)
            {
                buf.Add((byte)b);
                crlf = (b == '\r' || b == '\n') ? crlf + 1 : 0;
                if (crlf == 4) break;
            }
            string headers = Encoding.UTF8.GetString(buf.ToArray());
            var lines = headers.Split(new[] { "\r\n" }, StringSplitOptions.None);
            var first = lines[0].Split(' ');
            if (first.Length < 2) return;
            string method = first[0], path = first[1];
            int len = 0;
            foreach (var h in lines) if (h.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)) int.TryParse(h.Substring(15).Trim(), out len);
            var body = new byte[len]; int got = 0;
            while (got < len) { int n = stream.Read(body, got, len - got); if (n <= 0) break; got += n; }
            var form = ParseForm(Encoding.UTF8.GetString(body, 0, got));
            string q = path.Contains("?") ? path.Substring(path.IndexOf('?') + 1) : "";
            path = path.Split('?')[0];
            foreach (var kv in ParseForm(q)) form[kv.Key] = kv.Value;
            lastPing = DateTime.Now;

            byte[] outBody; string type = "application/json; charset=utf-8";
            switch (path)
            {
                case "/": outBody = Res("ui.html"); type = "text/html; charset=utf-8"; break;
                case "/wall.jpg": outBody = Res("wall.jpg"); type = "image/jpeg"; break;
                case "/icon.png": outBody = Res("icon.png"); type = "image/png"; break;
                case "/api/ping": outBody = Json("{\"ok\":true}"); break;
                case "/api/status": outBody = Json(Status()); break;
                case "/api/scan": outBody = Json(Scan()); break;
                case "/api/last": outBody = Json("{\"ip\":" + Str(Last()) + "}"); break;
                case "/api/connect": Start(() => Connect(Get(form, "ip"))); outBody = Json("{\"ok\":true}"); break;
                case "/api/apply": Start(() => Apply(form)); outBody = Json("{\"ok\":true}"); break;
                case "/api/restore": Start(Restore); outBody = Json("{\"ok\":true}"); break;
                case "/api/quit": outBody = Json("{\"ok\":true}"); new Timer(_ => Environment.Exit(0), null, 500, Timeout.Infinite); break;
                default: outBody = null; break;
            }
            string status = outBody == null ? "404 Not Found" : "200 OK";
            if (outBody == null) outBody = new byte[0];
            var resp = Encoding.ASCII.GetBytes("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + outBody.Length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
            stream.Write(resp, 0, resp.Length);
            stream.Write(outBody, 0, outBody.Length);
        }

        static byte[] Json(string s) { return Encoding.UTF8.GetBytes(s); }
        static string Get(Dictionary<string, string> f, string k) { string v; return f.TryGetValue(k, out v) ? v : ""; }

        static Dictionary<string, string> ParseForm(string s)
        {
            var d = new Dictionary<string, string>();
            foreach (var part in s.Split('&'))
            {
                if (part.Length == 0) continue;
                int i = part.IndexOf('=');
                string k = Uri.UnescapeDataString((i < 0 ? part : part.Substring(0, i)).Replace('+', ' '));
                string v = i < 0 ? "" : Uri.UnescapeDataString(part.Substring(i + 1).Replace('+', ' '));
                d[k] = v;
            }
            return d;
        }

        static string Str(string s)
        {
            var sb = new StringBuilder("\"");
            foreach (char ch in s ?? "")
            {
                if (ch == '"' || ch == '\\') sb.Append('\\').Append(ch);
                else if (ch < 32) sb.Append("\\u").Append(((int)ch).ToString("x4"));
                else sb.Append(ch);
            }
            return sb.Append('"').ToString();
        }

        static string Status()
        {
            lock (L)
            {
                var sb = new StringBuilder("{");
                sb.Append("\"phase\":").Append(Str(job.Phase)).Append(",\"msg\":").Append(Str(job.Msg));
                sb.Append(",\"progress\":").Append(job.Progress).Append(",\"model\":").Append(Str(job.Model)).Append(",\"ip\":").Append(Str(job.Ip));
                sb.Append(",\"log\":[").Append(string.Join(",", job.Log.Select(Str))).Append("]");
                sb.Append(",\"bloat\":[").Append(string.Join(",", job.Present.Select(x => "{\"pkg\":" + Str(x.Pkg) + ",\"label\":" + Str(x.Label) + ",\"home\":" + (x.NeedsNewHome ? "true" : "false") + "}"))).Append("]");
                return sb.Append("}").ToString();
            }
        }

        static void Start(Action a)
        {
            lock (L) { if (job.Phase == "working") return; job.Phase = "working"; job.Progress = 0; }
            new Thread(() =>
            {
                try { a(); }
                catch (Exception ex) { Say("Erro: " + ex.Message); Set("error", "Algo correu mal: " + ex.Message); }
            }) { IsBackground = true }.Start();
        }

        // -------------------------------------------------------------- finding the TV
        static string Last() { try { return File.ReadAllText(Path.Combine(Adb.Home, "ultima-tv.txt")).Trim(); } catch { return ""; } }

        static string Scan()
        {
            var probes = new List<Task<string>>();
            foreach (var prefix in Subnets())
                for (int i = 1; i < 255; i++) probes.Add(Probe(prefix + i));
            Task.WaitAll(probes.ToArray(), 6000);
            var hits = probes.Where(t => t.IsCompleted && t.Result != null).Select(t => t.Result).ToList();
            return "[" + string.Join(",", hits.OrderBy(h => h.Contains("false") ? 1 : 0)) + "]";
        }

        static async Task<string> Probe(string ip)
        {
            if (await OpenAsync(ip, 5555, 1500)) return "{\"ip\":\"" + ip + "\",\"ready\":true}";
            if (await OpenAsync(ip, 6466, 1200)) return "{\"ip\":\"" + ip + "\",\"ready\":false}";
            return null;
        }

        static async Task<bool> OpenAsync(string ip, int port, int ms)
        {
            var c = new TcpClient();
            try
            {
                var t = c.ConnectAsync(ip, port);
                var first = await Task.WhenAny(t, Task.Delay(ms));
                return first == t && !t.IsFaulted && c.Connected;
            }
            catch { return false; }
            finally { c.Close(); }
        }

        static IEnumerable<string> Subnets()
        {
            var list = new List<string>();
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                string d = (ni.Description + " " + ni.Name).ToLowerInvariant();
                if (d.Contains("virtual") || d.Contains("vpn") || d.Contains("hyper-v") || d.Contains("vmware") || d.Contains("wsl") || d.Contains("vethernet")) continue;
                if (ni.GetIPProperties().GatewayAddresses.Count == 0) continue;
                foreach (var a in ni.GetIPProperties().UnicastAddresses)
                {
                    if (a.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    var b = a.Address.GetAddressBytes();
                    if (b[0] == 169 || b[0] == 127) continue;
                    string p = b[0] + "." + b[1] + "." + b[2] + ".";
                    if (!list.Contains(p)) list.Add(p);
                }
            }
            return list;
        }

        static void Connect(string ip)
        {
            ip = (ip ?? "").Trim();
            if (!Regex.IsMatch(ip, @"^\d{1,3}(\.\d{1,3}){3}(:\d+)?$")) { Set("error", "Escreve um endereço como 192.168.1.50."); return; }
            string serial = ip.Contains(":") ? ip : ip + ":5555";
            Adb.Ensure(m => Set(null, m));
            Set(null, "A ligar à TV…");
            Adb.Serial = serial;
            lock (L) job.Ip = ip;
            Adb.Run("disconnect " + serial, 5000);
            string r = Adb.Run("connect " + serial, 15000);
            if (r.Contains("refused"))
            { Set("error", "A TV recusou a ligação. Confirma que ativaste a «Depuração USB» nas Opções de programador."); return; }
            if (r.Contains("failed") || r.Contains("cannot") || r == "timeout")
            { Set("error", "Não consegui chegar à TV. Confirma que está ligada (não em espera), na mesma rede, e que o IP está certo."); return; }
            string state = "";
            for (int i = 0; i < 90; i++)
            {
                state = Adb.State();
                if (state == "device") break;
                if (state == "unauthorized") Set(null, "authorize");
                if (state == "offline" && i % 5 == 4) Adb.Run("connect " + serial, 10000);
                Thread.Sleep(1000);
            }
            if (state != "device") { Set("error", "A TV não autorizou a tempo. Tenta outra vez e aceita o pedido no ecrã da TV."); return; }
            File.WriteAllText(Path.Combine(Adb.Home, "ultima-tv.txt"), ip);
            string brand = Adb.Sh("getprop ro.product.manufacturer"), model = Adb.Sh("getprop ro.product.model");
            string pkgs = "\n" + Adb.Sh("pm list packages") + "\n";
            lock (L)
            {
                job.Model = (brand + " " + model).Trim();
                job.Abi = Adb.Sh("getprop ro.product.cpu.abilist");
                job.Present = Bloat.All.Where(x => pkgs.Contains("\npackage:" + x.Pkg + "\n")).ToList();
            }
            Set("connected", "");
        }

        // -------------------------------------------------------------- doing it
        static string Saved { get { return Path.Combine(Adb.Home, "desativadas-" + Adb.Serial.Replace(":", "_") + ".txt"); } }

        static void Apply(Dictionary<string, string> o)
        {
            bool home = Get(o, "home") == "1", ads = Get(o, "ads") == "1", tube = Get(o, "tube") == "1", player = Get(o, "player") == "1";
            string bg = Get(o, "bg");
            var pick = new HashSet<string>(Get(o, "pkgs").Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries));
            lock (L) job.Log.Clear();
            Set(null, "A preparar…");
            File.WriteAllText(Path.Combine(Adb.Home, "apps-antes-" + Adb.Serial.Replace(":", "_") + "-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt"), Adb.Sh("pm list packages -f"));
            var off = new List<string>();
            if (File.Exists(Saved)) off.AddRange(File.ReadAllLines(Saved));
            Prog(4);

            bool homeOk = false;
            if (home)
            {
                Set(null, "A instalar o ecrã inicial…");
                string apk = Path.Combine(Path.GetTempPath(), "tvhome.apk");
                File.WriteAllBytes(apk, Res("tvhome.apk"));
                string r = Adb.Install(apk, "tvhome.apk");
                homeOk = r.Contains("Success");
                if (!homeOk && (r.Contains("UPDATE_INCOMPATIBLE") || r.Contains("signatures do not match")))
                {
                    // the launcher on the TV was signed on another PC: swap it once for this one
                    Set(null, "A trocar o ecrã inicial antigo pelo novo…");
                    Adb.Sh("pm uninstall com.mihir.tvhome");
                    r = Adb.Install(apk, "tvhome.apk");
                    homeOk = r.Contains("Success");
                }
                Say(homeOk ? "✓ Ecrã inicial instalado" : "✗ Ecrã inicial: " + LastLine(r));
                if (homeOk)
                {
                    Adb.Sh("pm grant com.mihir.tvhome android.permission.BLUETOOTH_CONNECT");
                    Adb.Sh("cmd notification allow_listener com.mihir.tvhome/com.mihir.tvhome.MediaListener");
                    Adb.Sh("appops set com.mihir.tvhome PROJECT_MEDIA allow");
                    Adb.Sh("cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity");
                    Say("✓ Painel de controlo, «A tocar», ecrã dividido e comando do telemóvel ativos");
                }
            }
            Prog(25);

            if (player)
            {
                Set(null, "A instalar o AntunesPlayer…");
                Download("https://github.com/JoaoAntunesss/AntunesPlayer/releases/download/latest/AntunesPlayer.apk", "antunesplayer.apk", "AntunesPlayer");
            }
            Prog(40);

            if (tube)
            {
                Set(null, "A instalar o YouTube sem anúncios…");
                try { SmartTube(); } catch (Exception ex) { Say("✗ SmartTube: " + ex.Message); }
            }
            Prog(60);

            if (ads || homeOk)
            {
                Set(null, "A desligar anúncios e apps de fábrica…");
                List<Bloat> present; lock (L) present = job.Present.ToList();
                var list = present.Where(x => pick.Contains(x.Pkg) && ((ads && !x.NeedsNewHome) || (x.NeedsNewHome && homeOk))).ToList();
                for (int i = 0; i < list.Count; i++)
                {
                    string r = Adb.Sh("pm disable-user --user 0 " + list[i].Pkg);
                    bool ok = r.Contains("disabled");
                    Say((ok ? "✓ Desligado: " : "· Já estava desligado: ") + list[i].Label);
                    if (ok && !off.Contains(list[i].Pkg)) off.Add(list[i].Pkg);
                    File.WriteAllLines(Saved, off);
                    Prog(60 + 30 * (i + 1) / list.Count);
                }
            }
            Prog(92);

            Adb.Sh("am force-stop com.android.tv.settings");
            Adb.Sh("input keyevent KEYCODE_HOME");
            if (homeOk && (bg == "canal" || bg == "lareira" || bg == "fotos" || bg == "cidades" || bg == "animais"))
            {
                Set(null, "A escolher o fundo…");
                Thread.Sleep(4000);
                string tvIp; lock (L) tvIp = job.Ip.Split(':')[0];
                for (int i = 0; i < 5; i++)
                {
                    try
                    {
                        using (var wc = new WebClient()) wc.DownloadString("http://" + tvIp + ":8765/bg?mode=" + bg);
                        Say("✓ Fundo: " + (bg == "lareira" ? "lareira com som" : bg == "fotos" ? "fotos da natureza" : bg == "cidades" ? "cidades 4K" : bg == "animais" ? "animais 4K" : "canal ao vivo"));
                        break;
                    }
                    catch { Thread.Sleep(2000); }
                }
            }
            Prog(100);
            Set("done", "");
        }

        static void Download(string url, string file, string name)
        {
            try
            {
                string apk = Path.Combine(Path.GetTempPath(), file);
                using (var wc = new WebClient()) { wc.Headers["User-Agent"] = "TVDebloat"; wc.DownloadFile(url, apk); }
                string r = Adb.Install(apk, file);
                Say(r.Contains("Success") ? "✓ " + name + " instalado" : "✗ " + name + ": " + LastLine(r));
            }
            catch (Exception ex) { Say("✗ " + name + ": " + ex.Message); }
        }

        static void SmartTube()
        {
            string json;
            using (var wc = new WebClient()) { wc.Headers["User-Agent"] = "TVDebloat"; json = wc.DownloadString("https://api.github.com/repos/yuliskov/SmartTube/releases/latest"); }
            var urls = Regex.Matches(json, "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").Cast<Match>().Select(m => m.Groups[1].Value).ToList();
            string abi; lock (L) abi = job.Abi;
            string want = abi.StartsWith("arm64") ? "arm64" : abi.StartsWith("x86") ? "x86" : "armeabi";
            string url = urls.FirstOrDefault(u => u.Contains("stable") && u.Contains(want)) ?? urls.FirstOrDefault(u => u.Contains(want)) ?? urls.FirstOrDefault(u => u.Contains("armeabi")) ?? urls.FirstOrDefault();
            if (url == null) throw new Exception("não encontrei o ficheiro da app");
            Download(url, "smarttube.apk", "YouTube sem anúncios (SmartTube)");
        }

        static void Restore()
        {
            lock (L) job.Log.Clear();
            Set(null, "A repor a TV…");
            var pk = Bloat.All.Select(x => x.Pkg).ToList();
            if (File.Exists(Saved)) foreach (var p in File.ReadAllLines(Saved)) if (!pk.Contains(p)) pk.Add(p);
            for (int i = 0; i < pk.Count; i++)
            {
                string r = Adb.Sh("pm enable " + pk[i]);
                if (r.Contains("enabled")) Say("✓ Reativado: " + pk[i]);
                Prog(80 * (i + 1) / pk.Count);
            }
            Adb.Sh("cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity");
            if (Adb.Run("-s " + Adb.Serial + " uninstall com.mihir.tvhome").Contains("Success")) Say("✓ Ecrã inicial novo removido");
            Say("✓ Ecrã inicial original reposto");
            Adb.Sh("input keyevent KEYCODE_HOME");
            try { File.Delete(Saved); } catch { }
            Prog(100);
            Set("done", "restored");
        }

        static string LastLine(string s)
        {
            var l = s.Split('\n').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            return l.Length == 0 ? "sem resposta" : l[l.Length - 1];
        }
    }
}
