using System.Diagnostics;
using System.Drawing;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
namespace Peyvanik;
static class Program {
 [STAThread] static void Main(){ApplicationConfiguration.Initialize();Application.Run(new MessengerWindow());}
}
sealed class MessengerWindow:Form{
 const string Origin="https://peyvanik-web.ahbe0911.workers.dev";
 readonly WebView2 web=new(){Dock=DockStyle.Fill,DefaultBackgroundColor=Color.FromArgb(241,247,245)};
 readonly Label status=new(){Dock=DockStyle.Top,Height=34,TextAlign=ContentAlignment.MiddleCenter,Text="در حال اتصال به پیونیک…"};
 readonly Button retry=new(){Dock=DockStyle.Top,Height=42,Text="تلاش مجدد",Visible=false};
 readonly NotifyIcon tray=new();bool exiting=false,starting=false;
 public MessengerWindow(){
  Text="پیونیک | Peyvanik";MinimumSize=new Size(340,400);StartPosition=FormStartPosition.Manual;var area=Screen.PrimaryScreen?.WorkingArea??new Rectangle(0,0,1920,1080);int width=Math.Min(area.Width,Math.Max(340,(int)(area.Width*.35)));Bounds=new Rectangle(area.Right-width,area.Top,width,area.Height);
  Icon=Icon.ExtractAssociatedIcon(Application.ExecutablePath)??SystemIcons.Application;Controls.Add(web);Controls.Add(retry);Controls.Add(status);
  var menu=new ContextMenuStrip{RightToLeft=RightToLeft.Yes};menu.Items.Add("باز کردن پیونیک",null,(_,_)=>Restore());menu.Items.Add("بارگذاری مجدد",null,async(_,_)=>await Connect());menu.Items.Add("خروج کامل",null,(_,_)=>{exiting=true;Close();});tray.Icon=Icon;tray.Text="پیونیک";tray.ContextMenuStrip=menu;tray.Visible=true;tray.DoubleClick+=(_,_)=>Restore();
  retry.Click+=async(_,_)=>await Connect();Shown+=async(_,_)=>await Connect();
  FormClosing+=(_,e)=>{if(!exiting&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();}};
  FormClosed+=(_,_)=>{tray.Visible=false;tray.Dispose();web.Dispose();};
 }
 void Restore(){Show();if(WindowState==FormWindowState.Minimized)WindowState=FormWindowState.Normal;Activate();}
 static bool Trusted(string? s)=>Uri.TryCreate(s,UriKind.Absolute,out var u)&&u.Scheme=="https"&&u.IdnHost=="peyvanik-web.ahbe0911.workers.dev"&&u.Port==443&&string.IsNullOrEmpty(u.UserInfo);
 async Task Connect(){
  if(starting)return;starting=true;status.Visible=true;status.Text="در حال اتصال به پیونیک…";retry.Visible=false;
  try{
   if(web.CoreWebView2==null){
    var path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Peyvanik","WebView2");Directory.CreateDirectory(path);await web.EnsureCoreWebView2Async(await CoreWebView2Environment.CreateAsync(null,path));
    var c=web.CoreWebView2;c.Settings.AreDevToolsEnabled=false;c.Settings.IsWebMessageEnabled=false;c.Settings.IsStatusBarEnabled=false;
    c.NavigationStarting+=(_,e)=>{if(!Trusted(e.Uri))e.Cancel=true;};c.NewWindowRequested+=(_,e)=>e.Handled=true;c.PermissionRequested+=(_,e)=>e.State=CoreWebView2PermissionState.Deny;
    c.NavigationCompleted+=(_,e)=>{status.Visible=!e.IsSuccess;retry.Visible=!e.IsSuccess;if(!e.IsSuccess)status.Text="اتصال برقرار نشد: "+e.WebErrorStatus;};
    c.ProcessFailed+=(_,e)=>{status.Visible=true;retry.Visible=true;status.Text="موتور نمایش متوقف شد؛ برنامه را دوباره اجرا کنید.";};
   }
   web.CoreWebView2.Navigate(Origin+"/");
  }catch(Exception e){status.Text="اتصال برقرار نشد: "+e.GetType().Name;retry.Visible=true;status.Visible=true;}finally{starting=false;}
 }
}