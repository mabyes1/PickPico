package com.mcpocket.poc;
import android.content.Context;
final class PicoTextSettings {
 static float scale(Context c) { int p=c.getSharedPreferences(PickPicoTheme.PREFS,0).getInt("pico_text_percent",100); return (p==120||p==140?p:100)/100f; }
 static void save(Context c,int p) { c.getSharedPreferences(PickPicoTheme.PREFS,0).edit().putInt("pico_text_percent",p).apply(); }
}
