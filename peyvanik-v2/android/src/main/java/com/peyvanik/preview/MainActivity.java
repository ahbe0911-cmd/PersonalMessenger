package com.peyvanik.preview;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.webkit.*;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import android.graphics.Color;
import android.net.http.SslError;
import java.net.URL;
import java.net.HttpURLConnection;
import android.webkit.CookieManager;
import java.io.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
 private static final String HOST="peyvanik-web.ahbe0911.workers.dev",ORIGIN="https://"+HOST;
 private static final int PICK=201,SAVE=202,MAX=20*1024*1024;
 private WebView web; private ValueCallback<Uri[]> chooser;
 private final ExecutorService io=Executors.newSingleThreadExecutor();
 private byte[] pending; private boolean busy=false; private boolean dead=false;
 private boolean trusted(Uri u){return u!=null&&"https".equals(u.getScheme())&&HOST.equals(u.getHost())&&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null;}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(7,94,84));getWindow().setNavigationBarColor(Color.rgb(7,94,84));
  FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(7,94,84));
  web=new WebView(this);web.setBackgroundColor(Color.rgb(241,247,245));root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
  if(android.os.Build.VERSION.SDK_INT>=30){
   getWindow().setDecorFitsSystemWindows(false);
   android.view.WindowInsetsController controller=getWindow().getInsetsController();
   if(controller!=null)controller.setSystemBarsAppearance(0,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
   root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return WindowInsets.CONSUMED;});
   root.requestApplyInsets();
  }
  WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSaveFormData(false);settings.setUserAgentString(settings.getUserAgentString()+" PeyvanikAndroid/2.6");WebView.setWebContentsDebuggingEnabled(false);
  CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){Uri u=r.getUrl();if(!r.isForMainFrame())return !trusted(u);if(!trusted(u))return true;if("/native-download".equals(u.getPath())){download(u);return true;}return false;}
   @Override public void onReceivedSslError(WebView w,SslErrorHandler handler,SslError e){handler.cancel();toast("گواهی اتصال معتبر نیست.");}
   @Override public void onReceivedError(WebView w,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())toast("اتصال برقرار نشد؛ اینترنت را بررسی و برنامه را دوباره باز کنید.");}
  });
  web.setWebChromeClient(new WebChromeClient(){
   @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> cb,FileChooserParams params){
    if(chooser!=null)chooser.onReceiveValue(null);chooser=cb;
    try{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);java.util.ArrayList<String> types=new java.util.ArrayList<>();for(String t:params.getAcceptTypes())if(t!=null&&!t.isEmpty()&&t.contains("/"))types.add(t);pick.setType(types.size()==1?types.get(0):"*/*");if(types.size()>1)pick.putExtra(Intent.EXTRA_MIME_TYPES,types.toArray(new String[0]));startActivityForResult(pick,PICK);return true;}catch(Exception ex){chooser=null;cb.onReceiveValue(null);return true;}
   }
   @Override public void onPermissionRequest(PermissionRequest r){r.deny();}
  });
  web.loadUrl(ORIGIN+"/");
 }
 private void download(Uri request){
  if(busy){toast("یک دانلود در حال انجام است.");return;}
  String id=request.getQueryParameter("id"),name=request.getQueryParameter("name");
  if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))return;
  final String filename=(name==null?"file":name.replaceAll("[\\\\/\\p{Cntrl}]","_"));
  final String cookie=CookieManager.getInstance().getCookie(ORIGIN);
  if(cookie==null||cookie.isEmpty()){toast("ابتدا وارد حساب شوید.");return;}
  busy=true;toast("در حال دریافت فایل…");
  io.execute(()->{HttpURLConnection c=null;try{
   c=(HttpURLConnection)new URL(ORIGIN+"/api/v2/cloud/"+id+"/download").openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("X-Peyvanik-Request","1");c.setRequestProperty("Cookie",cookie);
   if(c.getResponseCode()!=200||c.getContentLengthLong()>MAX)throw new IOException();
   byte[] data;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>MAX)throw new IOException();out.write(b,0,n);}data=out.toByteArray();}
   runOnUiThread(()->{if(dead)return;pending=data;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,filename.isEmpty()?"file":filename),SAVE);}catch(Exception e){pending=null;busy=false;toast("انتخاب محل ذخیره ممکن نیست.");}});
  }catch(Exception e){runOnUiThread(()->{if(!dead){busy=false;toast("دانلود ناموفق بود.");}});}finally{if(c!=null)c.disconnect();}});
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==PICK){ValueCallback<Uri[]> cb=chooser;chooser=null;if(cb!=null){Uri u=result==RESULT_OK&&data!=null?data.getData():null;cb.onReceiveValue(u!=null&&"content".equals(u.getScheme())?new Uri[]{u}:null);}return;}
  if(request==SAVE){byte[] bytes=pending;pending=null;Uri u=result==RESULT_OK&&data!=null?data.getData():null;if(bytes==null||u==null){busy=false;return;}io.execute(()->{boolean ok=false;try(OutputStream out=getContentResolver().openOutputStream(u,"w")){if(out!=null){out.write(bytes);ok=true;}}catch(Exception e){}final boolean done=ok;runOnUiThread(()->{if(!dead){busy=false;toast(done?"فایل ذخیره شد.":"ذخیره ناموفق بود.");}});});}
 }
 @Override protected void onPause(){super.onPause();CookieManager.getInstance().flush();}
 @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){dead=true;pending=null;if(chooser!=null){chooser.onReceiveValue(null);chooser=null;}io.shutdownNow();if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}