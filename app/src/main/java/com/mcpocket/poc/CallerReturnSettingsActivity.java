package com.mcpocket.poc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

/** Shares Pico theme surfaces; package identifiers stay out of the user interface. */
public final class CallerReturnSettingsActivity extends Activity {
 private PickPicoTheme.State theme;
 private final List<ResolveInfo> apps=new ArrayList<>(), filtered=new ArrayList<>();
 private BaseAdapter adapter;
 private TextView selected;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state); theme=PickPicoTheme.load(this);
  getWindow().setStatusBarColor(theme.colorA); getWindow().setNavigationBarColor(theme.colorA);
  getWindow().getDecorView().setSystemUiVisibility(PickPicoTheme.isLightBackground(theme)?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
  FrameLayout stage=new FrameLayout(this);
  stage.addView(new PickPicoTheme.BackgroundView(this,theme),new FrameLayout.LayoutParams(-1,-1));
  LinearLayout safe=new LinearLayout(this); safe.setOrientation(1); SystemBarInsets.apply(safe,true,true);
  stage.addView(safe,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(20),dp(8),dp(20),dp(12));
  safe.addView(root,new LinearLayout.LayoutParams(-1,-1));
  TextView back=text("‹  Back",14); back.setMinHeight(dp(48)); back.setGravity(Gravity.CENTER_VERTICAL); back.setOnClickListener(v->finish()); root.addView(back);
  TextView heading=text("Pico settings",22); heading.setTypeface(null,1); root.addView(heading);
  TextView size=text("Text size  ·  "+Math.round(PicoTextSettings.scale(this)*100)+"%  ›",13);
  size.setMinHeight(dp(48)); size.setGravity(Gravity.CENTER_VERTICAL);
  size.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Text size for Pico and this page")
   .setSingleChoiceItems(new String[]{"Default · 100%","Larger · 120%","Largest · 140%"},Math.round((PicoTextSettings.scale(this)-1)*5),(d,w)->{PicoTextSettings.save(this,new int[]{100,120,140}[w]); d.dismiss(); recreate();})
   .setNegativeButton("Cancel",null).show()); root.addView(size);
  selected=text("",14); selected.setPadding(dp(14),dp(14),dp(14),dp(14)); selected.setBackground(PickPicoTheme.card(theme,dp(16),true)); root.addView(selected);
  TextView note=text("Use the task’s return destination when provided, otherwise open your selected app.",12); note.setTextColor(PickPicoTheme.muted(theme)); note.setPadding(0,dp(12),0,dp(12)); root.addView(note);
  EditText search=new EditText(this); search.setSingleLine(true); search.setHint("Search apps"); search.setTextSize(14*PicoTextSettings.scale(this)); search.setTextColor(PickPicoTheme.text(theme)); search.setHintTextColor(PickPicoTheme.muted(theme)); search.setPadding(dp(14),dp(10),dp(14),dp(10)); search.setMinHeight(dp(48)); search.setBackground(PickPicoTheme.card(theme,dp(12),false)); root.addView(search);
  FrameLayout listArea=new FrameLayout(this); root.addView(listArea,new LinearLayout.LayoutParams(-1,0,1));
  ListView list=new ListView(this); list.setDivider(null); listArea.addView(list,new FrameLayout.LayoutParams(-1,-1));
  TextView empty=text("No matching apps",14); empty.setGravity(Gravity.CENTER); listArea.addView(empty,new FrameLayout.LayoutParams(-1,-1)); list.setEmptyView(empty);
  TextView clear=text("Clear default return app",13); clear.setGravity(Gravity.CENTER); clear.setMinHeight(dp(48)); clear.setOnClickListener(v->{CallerReturnSettings.save(this,"","");updateSelection();adapter.notifyDataSetChanged();}); root.addView(clear); setContentView(stage);
  Set<String> seen=new HashSet<>();
  for(ResolveInfo app:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) if(!getPackageName().equals(app.activityInfo.packageName)&&seen.add(app.activityInfo.packageName))apps.add(app);
  java.text.Collator collator=java.text.Collator.getInstance(); apps.sort((a,b)->collator.compare(label(a),label(b)));
  adapter=new BaseAdapter(){
   public int getCount(){return filtered.size();} public Object getItem(int p){return filtered.get(p);} public long getItemId(int p){return p;}
   public View getView(int p,View recycled,ViewGroup parent){
    ResolveInfo app=filtered.get(p); LinearLayout row=new LinearLayout(CallerReturnSettingsActivity.this); row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setMinimumHeight(dp(64));
    ImageView icon=new ImageView(CallerReturnSettingsActivity.this);icon.setImageDrawable(app.loadIcon(getPackageManager()));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));
    TextView name=text(label(app),14);name.setPadding(dp(14),0,dp(8),0);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    boolean chosen=app.activityInfo.packageName.equals(CallerReturnSettings.load(CallerReturnSettingsActivity.this).packageName);row.addView(text(chosen?"✓":"",18));row.setContentDescription(label(app)+(chosen?", selected":""));return row;
   }
  };list.setAdapter(adapter);
  list.setOnItemClickListener((parent,v,p,id)->{ResolveInfo app=filtered.get(p);CallerReturnSettings.save(this,label(app),app.activityInfo.packageName);updateSelection();adapter.notifyDataSetChanged();Toast.makeText(this,"Return app: "+label(app),Toast.LENGTH_SHORT).show();});
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){filter(s.toString());}});
  updateSelection();filter("");
 }
 private String label(ResolveInfo a){return a.loadLabel(getPackageManager()).toString();}
 private void updateSelection(){CallerReturnTarget t=CallerReturnSettings.load(this);selected.setText("Default return app\n"+(t.available()?t.name:"Not selected"));}
 private void filter(String q){filtered.clear();for(ResolveInfo a:apps)if(label(a).toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT)))filtered.add(a);adapter.notifyDataSetChanged();}
 private TextView text(String s,float size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size*PicoTextSettings.scale(this));t.setTextColor(PickPicoTheme.text(theme));return t;}
 private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
