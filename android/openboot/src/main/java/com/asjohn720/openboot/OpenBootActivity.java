package com.asjohn720.openboot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Opens the boot (trunk) by tapping its button on the dashboard.
 *
 * Where the button is comes from S1Mods ("Set boot button" in the Cursor tab), stored as
 * Settings.Global s1_boot_x / s1_boot_y in 1/1000 of the screen width / height.
 */
public class OpenBootActivity extends Activity {

    private static final String HMI_PACKAGE = "com.olaelectric.hmiclient";
    private static final long SETTLE_MS = 900; // time for the dashboard to come to the front

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TapService svc = TapService.instance;
        if (svc == null) {
            say("Open Boot is not switched on. In S1Mods open Apps and tap Install / Enable on Open Boot.");
            finish();
            return;
        }

        final int xm = Settings.Global.getInt(getContentResolver(), "s1_boot_x", -1);
        final int ym = Settings.Global.getInt(getContentResolver(), "s1_boot_y", -1);
        if (xm < 0 || xm > 1000 || ym < 0 || ym > 1000) {
            say("Boot button position not set. In S1Mods open the Cursor tab and tap Set boot button.");
            finish();
            return;
        }
        final int hold = Settings.Global.getInt(getContentResolver(), "s1_boot_hold_ms", 80);

        // Bring the dashboard to the front first, so the tap lands on it and not on this window.
        boolean shown = false;
        try {
            Intent hmi = new Intent();
            hmi.setClassName(HMI_PACKAGE, HMI_PACKAGE + ".MainActivity");
            hmi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(hmi);
            shown = true;
        } catch (Exception ignored) {
            // not exported / not found: use the HOME action below
        }
        if (!shown) svc.goHome();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                DisplayMetrics m = new DisplayMetrics();
                ((WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
                float x = m.widthPixels * xm / 1000f;
                float y = m.heightPixels * ym / 1000f;
                boolean sent = svc.tap(x, y, hold);
                say(sent ? "Boot button pressed" : "Could not press the boot button");
                finish();
            }
        }, SETTLE_MS);
    }

    private void say(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }
}
