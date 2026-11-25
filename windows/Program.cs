using System.Diagnostics;
using System.Drawing;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace PersonalMessenger;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MessengerWindow());
    }
}

internal sealed class MessengerWindow : Form
{
    private const string Origin = "https://personal-messenger-web.ahbe0911.workers.dev";
    private readonly WebView2 web = new() { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.FromArgb(239,234,226) };
    private readonly NotifyIcon tray = new();
    private readonly Label loading = new() { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, Text = "در حال اتصال به پیام‌رسان…", Font = new Font("Segoe UI", 13), ForeColor = Color.FromArgb(7,94,84), BackColor = Color.FromArgb(239,234,226) };
    private bool exiting;
    private bool started;
    private bool initializing;
    private readonly Panel failure = new() { Dock = DockStyle.Fill, BackColor = Color.FromArgb(239,234,226) };
    private readonly Label detail = new() { Dock = DockStyle.Top, Height = 112, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 11), ForeColor = Color.FromArgb(7,94,84), RightToLeft = RightToLeft.Yes };
    private readonly Button retry = new() { Text = "تلاش مجدد", AutoSize = true, Height = 42 };
    private readonly Button openBrowser = new() { Text = "باز کردن نسخه وب", AutoSize = true, Height = 42 };
    private readonly Button health = new() { Text = "بررسی اتصال سرور", AutoSize = true, Height = 42 };
    private Icon? appIcon;

    public MessengerWindow()
    {
        Text = "پیام‌رسان من";
        StartPosition = FormStartPosition.Manual;
        MinimumSize = new Size(340, 400);
        WindowState = FormWindowState.Normal;
        var work = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1920, 1080);
        var width = Math.Min(work.Width, Math.Max(340, (int)Math.Round(work.Width * 0.35)));
        Bounds = new Rectangle(work.Right - width, work.Top, width, work.Height);

        appIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        if (appIcon != null) Icon = appIcon;
        Controls.Add(web);
        Controls.Add(loading);
        var actions = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 60, FlowDirection = FlowDirection.RightToLeft, WrapContents = false, Padding = new Padding(12) };
        actions.Controls.Add(retry); actions.Controls.Add(openBrowser); actions.Controls.Add(health);
        var errorContent = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3, Padding = new Padding(25) };
        errorContent.RowStyles.Add(new RowStyle(SizeType.Percent, 40));
        errorContent.RowStyles.Add(new RowStyle(SizeType.Absolute, 180));
        errorContent.RowStyles.Add(new RowStyle(SizeType.Percent, 60));
        var middle = new Panel { Dock = DockStyle.Fill };
        middle.Controls.Add(actions); middle.Controls.Add(detail);
        errorContent.Controls.Add(middle, 0, 1);
        failure.Controls.Add(errorContent);
        Controls.Add(failure);
        failure.Visible = false;
        loading.BringToFront();
        retry.Click += async (_,_) => { if (initializing) return; ShowLoading(); if (web.CoreWebView2 is { } core) core.Navigate(Origin + "/"); else await InitializeBrowser(); };
        openBrowser.Click += (_,_) => OpenExternal(Origin + "/");
        health.Click += (_,_) => OpenExternal(Origin + "/health");
        var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes };
        menu.Items.Add("باز کردن پیام‌رسان", null, (_,_) => RestoreWindow());
        menu.Items.Add("بارگذاری مجدد", null, async (_,_) => { RestoreWindow(); if(initializing)return; ShowLoading(); if(web.CoreWebView2 is { } core)core.Navigate(Origin + "/");else await InitializeBrowser(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("خروج کامل", null, (_,_) => { exiting = true; Close(); });
        tray.Icon = appIcon ?? SystemIcons.Application;
        tray.Text = "پیام‌رسان من";
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        tray.DoubleClick += (_,_) => RestoreWindow();
        Shown += async (_,_) => { if (!started) { started = true; await InitializeBrowser(); } };
        FormClosing += (_,e) => {
            if (!exiting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                tray.ShowBalloonTip(2500, "پیام‌رسان من", "برنامه کنار ساعت ویندوز فعال است. برای خروج کامل از منوی آیکون استفاده کنید.", ToolTipIcon.Info);
            }
        };
        FormClosed += (_,_) => { tray.Visible = false; tray.Dispose(); web.Dispose(); appIcon?.Dispose(); };
    }

    private void RestoreWindow()
    {
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }

    private static bool Trusted(string? address)
    {
        return Uri.TryCreate(address, UriKind.Absolute, out var u) &&
            u.Scheme == Uri.UriSchemeHttps &&
            u.IdnHost.Equals("personal-messenger-web.ahbe0911.workers.dev", StringComparison.OrdinalIgnoreCase) &&
            u.Port == 443 && string.IsNullOrEmpty(u.UserInfo);
    }

    private static void OpenExternal(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch { MessageBox.Show("مرورگر پیش‌فرض باز نشد.", "پیام‌رسان من"); }
    }

    private void ShowLoading()
    {
        failure.Visible = false;
        loading.Text = "در حال اتصال به پیام‌رسان…";
        loading.Visible = true; loading.BringToFront();
    }

    private void ShowFailure(string code, string explanation)
    {
        loading.Visible = false;
        detail.Text = "ارتباط برقرار نشد.\n" + explanation + "\nکد خطا: " + code;
        failure.Visible = true; failure.BringToFront();
    }

    private static string Explain(string code) => code switch
    {
        "HostNameNotResolved" => "نام سرور یافت نشد. DNS و دسترسی اینترنت را بررسی کنید.",
        "InternetDisconnected" => "اتصال اینترنت برقرار نیست.",
        "CannotConnect" or "ServerUnreachable" or "Timeout" or "ConnectionAborted" or "ConnectionReset" => "دسترسی به سرور برقرار نشد. اتصال، پراکسی و دسترسی به دامنه workers.dev را بررسی کنید.",
        "CertificateCommonNameIsIncorrect" or "CertificateExpired" or "CertificateIsInvalid" or "ClientCertificateContainsErrors" or "CertificateRevoked" => "گواهی HTTPS معتبر نیست. تاریخ و ساعت ویندوز و تنظیمات شبکه را بررسی کنید.",
        _ => "نسخه وب را در مرورگر باز کنید و کد خطا را برای بررسی ارسال کنید."
    };

    private async Task InitializeBrowser()
    {
        if (initializing) return;
        initializing = true;
        ShowLoading();
        try
        {
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PersonalMessenger", "WebView2");
            Directory.CreateDirectory(folder);
            var environment = await CoreWebView2Environment.CreateAsync(null, folder);
            await web.EnsureCoreWebView2Async(environment);
            var core = web.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = true;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsWebMessageEnabled = true;
            core.Settings.AreBrowserAcceleratorKeysEnabled = true;
            core.NavigationStarting += (_,e) => { if (!Trusted(e.Uri)) e.Cancel = true; };
            core.NewWindowRequested += (_,e) => { e.Handled = true; };
            core.PermissionRequested += (_,e) => {
                if (!Trusted(e.Uri)) { e.State = CoreWebView2PermissionState.Deny; return; }
                if (e.PermissionKind != CoreWebView2PermissionKind.Microphone)
                    e.State = CoreWebView2PermissionState.Deny;
                // Microphone stays at default: the runtime asks the user.
            };
            core.NavigationCompleted += (_,e) => {
                if (e.IsSuccess) { failure.Visible = false; loading.Visible = false; }
                else {
                    var code = e.WebErrorStatus.ToString();
                    if(code == "OperationCanceled") return;
                    ShowFailure(code, Explain(code));
                }
            };
            core.ProcessFailed += (_,e) => {
                if(e.ProcessFailedKind.ToString() == "BrowserProcessExited")
                    ShowFailure(e.ProcessFailedKind.ToString(), "موتور مرورگر متوقف شد؛ برنامه را از کنار ساعت ویندوز کاملاً ببندید و دوباره اجرا کنید.");
            };
            core.WebMessageReceived += (_,e) => {
                if (!Trusted(e.Source)) return;
                if (e.WebMessageAsJson == "\"pm-ui-new-message\"" && (!Visible || WindowState == FormWindowState.Minimized || !ContainsFocus))
                    tray.ShowBalloonTip(3000, "پیام‌رسان من", "پیام جدید دریافت شد.", ToolTipIcon.Info);
            };
            // Presentation-only observer; does not call APIs, inspect tokens, or transmit message content.
            await core.AddScriptToExecuteOnDocumentCreatedAsync("""
            (()=>{if(location.origin!=='https://personal-messenger-web.ahbe0911.workers.dev')return;
              const start=()=>{const history=document.getElementById('history');if(!history)return;
                let previous=null;let lastNotice=0;
                const snapshot=()=>{const nodes=[...history.querySelectorAll('article.bubble:not(.mine)')];
                  const next=nodes.length;
                  if(previous!==null&&next>previous&&Date.now()-lastNotice>5000){
                    lastNotice=Date.now();window.chrome.webview.postMessage('pm-ui-new-message');
                  }
                  previous=next;
                };
                snapshot();new MutationObserver(snapshot).observe(history,{childList:true});
              };
              if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start,{once:true});else start();
            })();
            """);
            core.Navigate(Origin + "/");
        }
        catch (WebView2RuntimeNotFoundException)
        {
            ShowFailure("WebView2RuntimeNotFound", "Microsoft Edge WebView2 Runtime نصب نشده است.");
            var answer = MessageBox.Show("برای اجرای پیام‌رسان، Microsoft Edge WebView2 Runtime را نصب کنید.\nصفحه رسمی دانلود باز شود؟", Text, MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (answer == DialogResult.Yes)
                Process.Start(new ProcessStartInfo("https://developer.microsoft.com/microsoft-edge/webview2/") { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            ShowFailure(ex.GetType().Name + " / 0x" + ex.HResult.ToString("X8"), "موتور WebView2 آماده نشد. نسخه وب را بررسی کنید و کد خطا را ارسال کنید.");
        }
        finally { initializing = false; }
    }
}
