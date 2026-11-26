using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Drawing;
using System.Windows.Forms;
namespace PeyvanikKeymaker;
static class Program{
 [STAThread] static void Main(){ApplicationConfiguration.Initialize();Application.Run(new KeyForm());}
}
sealed class KeyForm:Form{
 static readonly HttpClient Http=new(new HttpClientHandler{UseCookies=false,AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(25)};
 readonly TextBox name=new(){MaxLength=80,Dock=DockStyle.Top},user=new(){MaxLength=32,RightToLeft=RightToLeft.No,Dock=DockStyle.Top},key=new(){ReadOnly=true,Multiline=true,RightToLeft=RightToLeft.No,Dock=DockStyle.Top,Height=66};
 readonly CheckBox accept=new(){Text="کلید را امن نگه می‌دارم؛ بازیابی خودکار وجود ندارد.",AutoSize=true};
 readonly Button create=new(){Text="ایجاد حساب و کلید",Height=48,Dock=DockStyle.Top},copy=new(){Text="کپی کلید",AutoSize=true,Enabled=false},save=new(){Text="ذخیره فایل متنی",AutoSize=true,Enabled=false},clear=new(){Text="پاک کردن نمایش",AutoSize=true,Enabled=false};
 readonly Label status=new(){AutoSize=true,MaximumSize=new Size(480,0)};string currentUser="";
 public KeyForm(){
 Text="کلیدساز پیونیک | Peyvanik Keymaker";RightToLeft=RightToLeft.Yes;RightToLeftLayout=true;StartPosition=FormStartPosition.CenterScreen;Size=new Size(560,520);MinimumSize=new Size(400,460);BackColor=Color.FromArgb(241,247,245);Font=new Font("Tahoma",11);
 var panel=new TableLayoutPanel{Dock=DockStyle.Fill,AutoScroll=true,Padding=new Padding(24),ColumnCount=1};
 panel.Controls.Add(new Label{Text="کلیدساز پیونیک",Font=new Font("Tahoma",23,FontStyle.Bold),ForeColor=Color.FromArgb(7,94,84),AutoSize=true});
 panel.Controls.Add(new Label{Text="نام نمایشی",AutoSize=true});panel.Controls.Add(name);panel.Controls.Add(new Label{Text="نام کاربری (لاتین)",AutoSize=true});panel.Controls.Add(user);panel.Controls.Add(accept);panel.Controls.Add(create);panel.Controls.Add(status);panel.Controls.Add(key);
 var actions=new FlowLayoutPanel{AutoSize=true,Dock=DockStyle.Top};actions.Controls.AddRange(new Control[]{copy,save,clear});panel.Controls.Add(actions);Controls.Add(panel);
 create.BackColor=Color.FromArgb(8,125,112);create.ForeColor=Color.White;create.FlatStyle=FlatStyle.Flat;
 create.Click+=async(_,_)=>await Generate();copy.Click+=(_,_)=>{try{if(key.TextLength>0)Clipboard.SetText(key.Text);}catch{status.Text="کپی ممکن نیست؛ متن کلید را انتخاب کنید.";}};
 save.Click+=(_,_)=>{if(key.TextLength==0)return;using var d=new SaveFileDialog{Filter="Text file|*.txt",FileName="peyvanik-"+currentUser+"-key.txt"};if(d.ShowDialog()==DialogResult.OK){try{File.WriteAllText(d.FileName,"Peyvanik\nUsername: "+currentUser+"\nAccess key: "+key.Text+"\nKeep private.\n");status.Text="کلید ذخیره شد.";}catch{status.Text="ذخیره ناموفق بود.";}}};
 clear.Click+=(_,_)=>ClearKey();FormClosing+=(_,e)=>{if(key.TextLength>0&&MessageBox.Show("کلید را ذخیره کرده‌اید؟","پیونیک",MessageBoxButtons.YesNo)!=DialogResult.Yes)e.Cancel=true;};
 }
 void ClearKey(){key.Clear();currentUser="";copy.Enabled=save.Enabled=clear.Enabled=false;}
 async Task Generate(){
 if(!accept.Checked||!Regex.IsMatch(user.Text.Trim(),"^[a-zA-Z][a-zA-Z0-9_]{2,31}$")||string.IsNullOrWhiteSpace(name.Text)){status.Text="نام، نام کاربری معتبر و تأیید نگهداری کلید لازم است.";return;}
 if(key.TextLength>0&&MessageBox.Show("کلید قبلی ذخیره شده است؟","پیونیک",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
 ClearKey();create.Enabled=false;status.Text="در حال ارتباط با سرور…";
 try{using var req=new HttpRequestMessage(HttpMethod.Post,"https://peyvanik-web.ahbe0911.workers.dev/keymaker/issue");req.Headers.Add("X-Peyvanik-Keymaker","1");req.Content=JsonContent.Create(new{display_name=name.Text.Trim(),username=user.Text.Trim(),accept_key_responsibility=true});using var res=await Http.SendAsync(req);using var doc=JsonDocument.Parse(await res.Content.ReadAsStringAsync());var root=doc.RootElement;
 if(!res.IsSuccessStatusCode){string code=root.TryGetProperty("error",out var er)?er.GetString()??"":"";status.Text=code switch{"USERNAME_TAKEN"=>"نام کاربری تکراری است.","REGISTRATION_LIMIT"=>"ظرفیت حساب‌ها پر شده.","TOO_MANY_REQUESTS"=>"محدودیت درخواست؛ بعداً تلاش کنید.",_=>"ساخت حساب ناموفق بود."};return;}
 string issued=root.GetProperty("access_key").GetString()??"";if(!Regex.IsMatch(issued,"^pk_[a-f0-9]{64}$"))throw new Exception();currentUser=root.GetProperty("account").GetProperty("username").GetString()??"";key.Text=issued;copy.Enabled=save.Enabled=clear.Enabled=true;status.Text="حساب ساخته شد؛ کلید فقط یک‌بار نمایش داده می‌شود.";name.Clear();user.Clear();accept.Checked=false;
 }catch{status.Text="پاسخ سرور تأیید نشد؛ در قطع شبکه احتمال ایجاد حساب وجود دارد.";}finally{create.Enabled=true;}
 }
}