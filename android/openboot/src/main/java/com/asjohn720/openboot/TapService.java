package com.asjohn720.openboot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/**
 * Does one thing: injects a tap at a screen position. Injected gestures do not depend on the
 * physical digitizer, so they also land in the part of the screen that does not sense touch.
 */
public class TapService extends AccessibilityService {

    /** Set while the service is switched on and bound. */
    static volatile TapService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // not used
    }

    @Override
    public void onInterrupt() {
        // not used
    }

    /** Same as pressing HOME (the dashboard is the home screen). */
    void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** Taps (or holds, if holdMs is larger) at pixel position x,y. Returns false if it could not be sent. */
    boolean tap(float x, float y, long holdMs) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, holdMs)))
                .build();
        return dispatchGesture(g, null, null);
    }
}
