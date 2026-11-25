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
    private Icon? appIcon;

    public MessengerWindow()
    {
        Text = "پیام‌رسان من";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(520, 480);
        Size = new Size(1200, 800);
        WindowState = FormWindowState.Maximized;
        appIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        if (appIcon != null) Icon = appIcon;
        Controls.Add(web);
        Controls.Add(loading);
        loading.BringToFront();
        var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes };
        menu.Items.Add("باز کردن پیام‌رسان", null, (_,_) => RestoreWindow());
        menu.Items.Add("بارگذاری مجدد", null, (_,_) => { RestoreWindow(); web.CoreWebView2?.Reload(); });
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

    private async Task InitializeBrowser()
    {
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
                if (e.IsSuccess) loading.Visible = false;
                else { loading.Text = "ارتباط برقرار نشد. برای تلاش دوباره روی این صفحه کلیک کنید."; loading.Visible = true; loading.BringToFront(); }
            };
            loading.Click += (_,_) => { loading.Text = "در حال تلاش مجدد…"; core.Navigate(Origin + "/"); };
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
            loading.Text = "WebView2 Runtime نصب نشده است.";
            var answer = MessageBox.Show("برای اجرای پیام‌رسان، Microsoft Edge WebView2 Runtime را نصب کنید.\nصفحه رسمی دانلود باز شود؟", Text, MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (answer == DialogResult.Yes)
                Process.Start(new ProcessStartInfo("https://developer.microsoft.com/microsoft-edge/webview2/") { UseShellExecute = true });
        }
        catch (Exception)
        {
            loading.Text = "برنامه نتوانست محیط وب را آماده کند. پس از بررسی اینترنت و WebView2 برنامه را دوباره اجرا کنید.";
        }
    }
}
