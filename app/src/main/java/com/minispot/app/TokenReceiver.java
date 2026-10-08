package com.minispot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the Client ID and a Spotify session made on the PC by login-pc.py
 * (old phone browsers cannot load Spotify's login page).
 * Protected by the DUMP permission in the manifest: only adb (the shell user) can send it, not other apps.
 */
public class TokenReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		String clientId = intent.getStringExtra("client_id");
		if (!Api.isValidClientId(clientId)) {
			return;
		}
		Api api = new Api(context);
		api.setClientId(clientId);
		String refresh = intent.getStringExtra("refresh");
		if (refresh == null || refresh.isEmpty()) {
			return; // Client ID only
		}
		String access = intent.getStringExtra("access");
		int expiresIn = intent.getIntExtra("expires", 0);
		api.saveLogin(access, refresh, access != null ? expiresIn : 0);
		context.startActivity(new Intent(context, RemoteActivity.class)
			.putExtra(RemoteActivity.EXTRA_LOGGED_IN, true)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
	}
}
