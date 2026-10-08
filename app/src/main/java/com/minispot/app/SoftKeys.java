package com.minispot.app;

import android.util.Log;
import android.view.Window;

import java.lang.reflect.Method;

/**
 * Labels of the grey soft key bar at the bottom of Kyocera flip phones (left, centre, right).
 * Uses the phone's com.nextfp.android.util library through reflection; does nothing on other phones.
 * The labels only reach the bar once the window exists, so the activity calls {@link #set} again from onResume.
 */
class SoftKeys {
	private static final int CENTER = 0, LEFT = 1, RIGHT = 2;

	private Object guide;
	private Method setText;
	private Method show;
	private Method invalidate;

	SoftKeys(Window window) {
		try {
			Class<?> c = Class.forName("com.nextfp.android.util.NfpSoftkeyGuide");
			guide = c.getMethod("getSoftkeyGuide", Window.class).invoke(null, window);
			setText = c.getMethod("setText", int.class, CharSequence.class);
			show = c.getMethod("show");
			invalidate = c.getMethod("invalidate");
		} catch (Throwable t) {
			Log.i("MiniSpot", "no soft key guide: " + t);
			guide = null;
		}
	}

	void set(String left, String center, String right) {
		if (guide == null) {
			return;
		}
		try {
			setText.invoke(guide, LEFT, left);
			setText.invoke(guide, CENTER, center);
			setText.invoke(guide, RIGHT, right);
			show.invoke(guide);
			invalidate.invoke(guide);
		} catch (Throwable t) {
			Log.w("MiniSpot", "soft keys", t);
		}
	}
}
