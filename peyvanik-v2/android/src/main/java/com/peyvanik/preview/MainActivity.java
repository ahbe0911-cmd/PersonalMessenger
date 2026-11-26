package com.peyvanik.preview;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final String URL = "https://peyvanik-preview.ahbe0911.workers.dev/";
    private int dp(float v) {return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,94,84));
        getWindow().setNavigationBarColor(Color.rgb(7,94,84));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);content.setGravity(Gravity.CENTER);
        content.setPadding(dp(24),dp(28),dp(24),dp(24));content.setBackgroundColor(Color.rgb(241,247,245));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.peyvanik_icon);
        content.addView(logo,new LinearLayout.LayoutParams(dp(96),dp(96)));
        TextView title=text("پیونیک",32,Color.rgb(7,94,84));title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        content.addView(title);content.addView(text("نسخه آزمایشی ۲.۰",17,Color.rgb(23,47,53)));
        TextView info=text("رابط گفتگوها، ابر من و حساب کاربری در مرورگر امن سیستم باز می‌شود. ثبت‌نام عمومی هنوز فعال نیست.",16,Color.rgb(97,119,123));info.setPadding(0,dp(14),0,dp(18));content.addView(info);
        Button open=new Button(this);open.setText("باز کردن پیونیک");open.setAllCaps(false);open.setTextSize(17);open.setTextColor(Color.WHITE);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(8,125,112));bg.setCornerRadius(dp(14));open.setBackground(bg);
        content.addView(open,new LinearLayout.LayoutParams(-1,dp(56)));open.setOnClickListener(v->launch());
        TextView privacy=text("ورود در مرورگر اصلی دستگاه انجام می‌شود. این برنامه رمز یا کد شما را دریافت نمی‌کند.",13,Color.rgb(97,119,123));privacy.setPadding(0,dp(16),0,0);content.addView(privacy);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);setContentView(scroll);
    }
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private void launch(){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE));}catch(ActivityNotFoundException ex){Toast.makeText(this,"مرورگر نصب نشده است.",Toast.LENGTH_LONG).show();}}
}