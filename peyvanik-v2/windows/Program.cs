using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
namespace PeyvanikPreview;
static class Program {
 [STAThread] static void Main() {
  ApplicationConfiguration.Initialize(); Application.Run(new PreviewForm());
 }
}
sealed class PreviewForm : Form {
 const string Url="https://peyvanik-preview.ahbe0911.workers.dev/";
 public PreviewForm(){
  Text="Peyvanik — پیونیک 2.0 Preview"; RightToLeft=RightToLeft.Yes; RightToLeftLayout=true;
  BackColor=Color.FromArgb(241,247,245); ForeColor=Color.FromArgb(7,94,84); Font=new Font("Tahoma",11);
  StartPosition=FormStartPosition.Manual; var area=Screen.PrimaryScreen!.WorkingArea;
  int width=Math.Max(320,(int)(area.Width*0.35)); width=Math.Min(width,area.Width);
  Bounds=new Rectangle(area.Right-width,area.Top,width,area.Height);
  MinimumSize=new Size(300,320);
  var panel=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(24),ColumnCount=1,RowCount=5};
  panel.RowStyles.Add(new RowStyle(SizeType.Percent,25));
  panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
  panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
  panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
  panel.RowStyles.Add(new RowStyle(SizeType.Percent,75));
  var heading=new Label{Text="پیونیک",Font=new Font("Tahoma",28,FontStyle.Bold),AutoSize=true,Anchor=AnchorStyles.None};
  var note=new Label{Text="نسخه آزمایشی ۲.۰\nرابط پیونیک در مرورگر امن سیستم باز می‌شود.\nثبت‌نام عمومی هنوز فعال نیست.",AutoSize=true,MaximumSize=new Size(Math.Max(240,width-64),0),Anchor=AnchorStyles.None,TextAlign=ContentAlignment.MiddleCenter,Padding=new Padding(0,16,0,16)};
  var open=new Button{Text="باز کردن پیونیک",Height=52,Dock=DockStyle.Top,FlatStyle=FlatStyle.Flat,BackColor=Color.FromArgb(8,125,112),ForeColor=Color.White};
  open.Click+=(_,_)=>{try{Process.Start(new ProcessStartInfo(Url){UseShellExecute=true});}catch{MessageBox.Show("مرورگر سیستم در دسترس نیست.");}};
  panel.Controls.Add(heading,0,1);panel.Controls.Add(note,0,2);panel.Controls.Add(open,0,3);Controls.Add(panel);
 }
}