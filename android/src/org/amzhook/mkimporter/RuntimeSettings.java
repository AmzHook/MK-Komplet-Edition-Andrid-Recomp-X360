package org.amzhook.mkimporter;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices saved by the launcher and snapshotted for each runtime launch. */
final class RuntimeSettings {
    private final SharedPreferences preferences;
    RuntimeSettings(Context context) { preferences = context.getSharedPreferences("runtime-options", Context.MODE_PRIVATE); }
    boolean fillScreen() { return preferences.getBoolean("fill_screen", false); }
    boolean vsync() { return preferences.getBoolean("vsync", true); }
    boolean traceAssets() { return preferences.getBoolean("trace_assets", true); }
    int resolution() { return preferences.getInt("resolution", 1) == 2 ? 2 : 1; }
    String driverId() { return preferences.getString("driver_id", ""); }
    void setBoolean(String key, boolean value) {
        if (!key.equals("fill_screen") && !key.equals("vsync") && !key.equals("trace_assets")) throw new IllegalArgumentException("Opção inválida");
        preferences.edit().putBoolean(key, value).apply();
    }
    void setResolution(int value) { preferences.edit().putInt("resolution", value == 2 ? 2 : 1).apply(); }
    void setDriver(String id) { preferences.edit().putString("driver_id", id == null ? "" : id).apply(); }
    void reset() { preferences.edit().clear().apply(); }
}
