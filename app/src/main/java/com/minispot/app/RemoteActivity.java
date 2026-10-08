package com.minispot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keypad remote for Spotify (or another music app).
 * Without login it drives the player through Android's media session, like a headset does.
 * Logged in (Spotify Premium), it also browses and searches Spotify with the Web API and starts playback with it.
 */
public class RemoteActivity extends Activity {
	private static final String TAG = "MiniSpot";
	/** Automatic mode order. Spotify Lite first: much lighter than the full app on this phone. */
	private static final String[] PLAYERS = {"com.spotify.lite", "com.spotify.music"};
	private static final String PREF_PLAYER = "player";
	private static final String PREF_HOME_ORDER = "home_order";
	private static final String PREF_PINS = "pins";
	private static final String PREF_HOME_HIDDEN = "home_hidden";
	private static final String PREF_LANG = "lang";
	/** Language tag and its own name; "" follows the phone. */
	private static final String[][] LANGUAGES = {
		{"en", "English"}, {"fr", "Français"}, {"de", "Deutsch"}, {"es", "Español"}, {"it", "Italiano"},
		{"pt", "Português"}, {"ru", "Русский"}, {"ja", "日本語"}, {"zh-CN", "中文（简体）"}, {"ko", "한국어"},
	};
	/** Home screen entries, in default order. */
	private static final String[] HOME_KEYS = {"recents", "search", "playlists", "liked", "albums", "artists", "podcasts", "history"};
	/** Set by TokenReceiver after a login made on the PC. */
	static final String EXTRA_LOGGED_IN = "logged_in";
	private static final String[] SCOPES = {
		"user-read-private", "playlist-read-private", "playlist-read-collaborative", "playlist-modify-public",
		"user-library-read", "user-library-modify", "user-follow-read", "user-follow-modify",
		"user-read-recently-played", "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing"
	};

	private static final int GREEN = 0xFF1DB954;
	private static final int GRAY = 0xFF9A9A9A;
	private static final int DIM = 0xFF555555;

	private interface Parser {
		Page parse(JSONObject json) throws Exception;
	}

	private static class Page {
		final List<Row> rows = new ArrayList<>();
		String next;
	}

	private static class Row {
		String label;
		String sub;
		int icon;
		boolean header;
		Runnable action;
		/** spotify:track / spotify:episode URI when the row is a playable item. */
		String uri;
		/** Position inside the screen's context (playlist, album), -1 if none. */
		int index = -1;
		/** Spotify object type ("track", "episode", "playlist", "album", "artist", "show") and its JSON, for the menu. */
		String kind;
		JSONObject data;
		/** Drawn in green: the t(R.string.now_playing) row, the entry being moved. */
		boolean highlight;
		/** Drawn in grey: a home tab that is hidden. */
		boolean dim;

		Row(int icon, String label, String sub, Runnable action) {
			this.icon = icon;
			this.label = label;
			this.sub = sub;
			this.action = action;
		}
	}

	private static class Screen {
		final String title;
		final List<Row> rows = new ArrayList<>();
		int selection;
		boolean nowPlaying;
		boolean search;
		boolean home;
		/** "Ordre de l'accueil" screen: the row being moved, -1 if none. */
		boolean reorder;
		int moving = -1;
		/** Playlist / album the track rows belong to, null when tracks are played as a plain list. */
		String contextUri;

		Screen(String title) {
			this.title = title;
		}
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final ArrayList<Screen> stack = new ArrayList<>();
	private final ExecutorService io = Executors.newSingleThreadExecutor();
	private AudioManager audio;
	private Api api;

	private MediaController controller;
	/** Package picked in the t(R.string.player) screen, "" for automatic. */
	private String chosen = "";
	/** Shuffle / repeat as last read from the Web API (repeat: "off", "context", "track"). */
	private boolean shuffleOn;
	private String repeatMode = "off";
	/** The home screen's t(R.string.now_playing) row, refreshed with the current track. */
	private Row nowRow;
	/** Play state shown right after OK, until the player confirms it (Spotify can take a second). */
	private Boolean optimisticPlaying;
	private long optimisticUntil;

	private TextView nowBar, titleView, hintView, npTitle, npArtist, npAlbum, npTime;
	private ImageView npPlay, npShuffle, npRepeat;
	private EditText searchBox;
	private ListView list;
	private final RowAdapter adapter = new RowAdapter();
	private LinearLayout npView;
	private ProgressBar npProgress;
	private SoftKeys softKeys;

	private final Runnable ticker = new Runnable() {
		@Override
		public void run() {
			updateProgress();
			main.postDelayed(this, 1000);
		}
	};

	private final MediaController.Callback controllerCallback = new MediaController.Callback() {
		@Override
		public void onPlaybackStateChanged(PlaybackState state) {
			optimisticPlaying = null;
			updateNow();
		}

		@Override
		public void onMetadataChanged(MediaMetadata metadata) {
			updateNow();
		}

		@Override
		public void onSessionDestroyed() {
			setController(null);
		}
	};

	/** Applies the language picked in Settings, if any, before any text is loaded. */
	@Override
	protected void attachBaseContext(Context base) {
		String lang = base.getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_LANG, "");
		if (!lang.isEmpty()) {
			Configuration config = new Configuration(base.getResources().getConfiguration());
			config.setLocale(Locale.forLanguageTag(lang));
			base = base.createConfigurationContext(config);
		}
		super.attachBaseContext(base);
	}

	private String t(int id) {
		return getString(id);
	}

	private String t(int id, Object... args) {
		return getString(id, args);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		api = new Api(this);
		chosen = getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_PLAYER, "");
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		buildViews();
		softKeys = new SoftKeys(getWindow());
		showHome();
		handleLoginRedirect(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (intent.getBooleanExtra(EXTRA_LOGGED_IN, false)) {
			toast(t(R.string.logged_in));
			showHome();
			return;
		}
		handleLoginRedirect(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// the soft key bar only takes labels once the window is shown
		main.post(this::updateSoftKeys);
	}

	@Override
	protected void onStart() {
		super.onStart();
		findSession();
		main.post(ticker);
	}

	@Override
	protected void onStop() {
		super.onStop();
		main.removeCallbacks(ticker);
		stopListening();
		setController(null);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		io.shutdownNow();
	}

	// ---- media session (works without login) ----

	private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = sessions -> {
		if (controller == null && sessions != null) {
			pickSession(sessions);
		}
	};
	private boolean listening;

	/** Reads the player's session through the notification listener permission (Spotify refuses its media browser). */
	private void findSession() {
		if (controller != null) {
			return;
		}
		try {
			MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
			ComponentName listener = new ComponentName(this, NotifListener.class);
			if (!listening) {
				msm.addOnActiveSessionsChangedListener(sessionsListener, listener, main);
				listening = true;
			}
			pickSession(msm.getActiveSessions(listener));
		} catch (SecurityException e) {
			Log.w(TAG, "no notification listener access");
		}
	}

	private void pickSession(List<MediaController> sessions) {
		String[] order = chosen.isEmpty() ? PLAYERS : new String[] {chosen};
		for (String pkg : order) {
			for (MediaController c : sessions) {
				if (pkg.equals(c.getPackageName())) {
					setController(c);
					return;
				}
			}
		}
		if (chosen.isEmpty() && !sessions.isEmpty()) {
			// automatic mode without Spotify: follow whichever app is playing
			setController(sessions.get(0));
		}
	}

	/** Package of the app MiniSpot drives. */
	private String player() {
		if (!chosen.isEmpty()) {
			return chosen;
		}
		for (String pkg : PLAYERS) {
			try {
				getPackageManager().getPackageInfo(pkg, 0);
				return pkg;
			} catch (Exception ignored) {
			}
		}
		return PLAYERS[0];
	}

	private void stopListening() {
		if (listening) {
			((MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE)).removeOnActiveSessionsChangedListener(sessionsListener);
			listening = false;
		}
	}

	/** Wakes the player with a media key; it then publishes its session and the listener picks it up. */
	private void wakePlayer() {
		long now = SystemClock.uptimeMillis();
		KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
		KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
		Intent query = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(player());
		List<ResolveInfo> receivers = getPackageManager().queryBroadcastReceivers(query, 0);
		if (!receivers.isEmpty()) {
			// address the chosen app directly, not the last app that played
			ComponentName rc = new ComponentName(receivers.get(0).activityInfo.packageName, receivers.get(0).activityInfo.name);
			sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(rc).putExtra(Intent.EXTRA_KEY_EVENT, down));
			sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(rc).putExtra(Intent.EXTRA_KEY_EVENT, up));
		} else {
			audio.dispatchMediaKeyEvent(down);
			audio.dispatchMediaKeyEvent(up);
		}
	}

	private void setController(MediaController c) {
		if (controller != null) {
			controller.unregisterCallback(controllerCallback);
		}
		controller = c;
		if (c != null) {
			c.registerCallback(controllerCallback, main);
		}
		updateNow();
	}

	private MediaController.TransportControls controls() {
		if (controller == null) {
			findSession();
		}
		if (controller == null) {
			wakePlayer();
			toast(t(R.string.starting, appLabel(player())));
			return null;
		}
		return controller.getTransportControls();
	}

	// ---- views ----

	private TextView text(float sp, int color) {
		TextView t = new TextView(this);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		return t;
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
	}

	private Drawable icon(int res, int color, int sizeDp) {
		Drawable d = getDrawable(res).mutate();
		d.setTint(color);
		d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
		return d;
	}

	private ImageView iconView(int res, int sizeDp) {
		ImageView v = new ImageView(this);
		v.setImageResource(res);
		v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
		return v;
	}

	private void buildViews() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(Color.BLACK);

		nowBar = text(13, GREEN);
		nowBar.setSingleLine(true);
		nowBar.setEllipsize(TextUtils.TruncateAt.END);
		nowBar.setPadding(dp(8), dp(3), dp(8), dp(3));
		nowBar.setCompoundDrawablePadding(dp(6));
		nowBar.setGravity(Gravity.CENTER_VERTICAL);
		nowBar.setBackgroundColor(0xFF111111);
		root.addView(nowBar);

		titleView = text(17, Color.WHITE);
		titleView.setTypeface(Typeface.DEFAULT_BOLD);
		titleView.setSingleLine(true);
		titleView.setEllipsize(TextUtils.TruncateAt.END);
		titleView.setPadding(dp(10), dp(6), dp(10), dp(4));
		root.addView(titleView);

		searchBox = new EditText(this);
		searchBox.setSingleLine(true);
		searchBox.setTextColor(Color.WHITE);
		searchBox.setHintTextColor(GRAY);
		searchBox.setHint(R.string.search_hint);
		searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchBox.setCompoundDrawables(icon(R.drawable.ic_search, GRAY, 20), null, null, null);
		searchBox.setCompoundDrawablePadding(dp(6));
		searchBox.setVisibility(View.GONE);
		searchBox.setOnEditorActionListener((v, actionId, event) -> {
			runSearch();
			return true;
		});
		searchBox.setOnKeyListener((v, keyCode, event) -> {
			if (event.getAction() == KeyEvent.ACTION_UP
				&& (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
				runSearch();
				return true;
			}
			return false;
		});
		root.addView(searchBox);

		list = new ListView(this);
		list.setSelector(new ColorDrawable(0xFF1E5631));
		list.setDivider(new ColorDrawable(0xFF1A1A1A));
		list.setDividerHeight(1);
		list.setCacheColorHint(Color.BLACK);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) -> {
			Row r = adapter.rows.get(position);
			if (r.action != null) {
				r.action.run();
			}
		});
		root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

		npView = new LinearLayout(this);
		npView.setOrientation(LinearLayout.VERTICAL);
		npView.setPadding(dp(12), dp(16), dp(12), dp(8));
		npView.setGravity(Gravity.CENTER_HORIZONTAL);
		npView.setVisibility(View.GONE);
		npTitle = text(22, Color.WHITE);
		npTitle.setTypeface(Typeface.DEFAULT_BOLD);
		npTitle.setGravity(Gravity.CENTER);
		npTitle.setMaxLines(3);
		npArtist = text(17, GREEN);
		npArtist.setGravity(Gravity.CENTER);
		npArtist.setMaxLines(2);
		npAlbum = text(14, GRAY);
		npAlbum.setGravity(Gravity.CENTER);
		npAlbum.setMaxLines(2);
		npProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		npProgress.setMax(1000);
		npTime = text(14, GRAY);
		npTime.setGravity(Gravity.CENTER);
		npView.addView(npTitle);
		npView.addView(npArtist);
		npView.addView(npAlbum);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(18);
		npView.addView(npProgress, lp);
		npView.addView(npTime);

		LinearLayout controlsRow = new LinearLayout(this);
		controlsRow.setOrientation(LinearLayout.HORIZONTAL);
		controlsRow.setGravity(Gravity.CENTER);
		npShuffle = iconView(R.drawable.ic_shuffle, 24);
		ImageView prev = iconView(R.drawable.ic_prev, 34);
		npPlay = iconView(R.drawable.ic_pause, 56);
		ImageView next = iconView(R.drawable.ic_next, 34);
		npRepeat = iconView(R.drawable.ic_repeat, 24);
		ImageView[] all = {npShuffle, prev, npPlay, next, npRepeat};
		for (ImageView v : all) {
			LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
			p.leftMargin = p.rightMargin = dp(10);
			controlsRow.addView(v);
		}
		LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp2.topMargin = dp(18);
		npView.addView(controlsRow, lp2);
		root.addView(npView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

		hintView = text(12, GRAY);
		hintView.setPadding(dp(8), dp(3), dp(8), dp(3));
		hintView.setBackgroundColor(0xFF111111);
		hintView.setMaxLines(2);
		root.addView(hintView);

		setContentView(root);
	}

	private class RowAdapter extends BaseAdapter {
		List<Row> rows = new ArrayList<>();

		@Override
		public int getCount() {
			return rows.size();
		}

		@Override
		public Object getItem(int position) {
			return rows.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public boolean isEnabled(int position) {
			return !rows.get(position).header;
		}

		@Override
		public boolean areAllItemsEnabled() {
			return false;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			TextView t = convertView instanceof TextView ? (TextView) convertView : text(17, Color.WHITE);
			Row r = rows.get(position);
			t.setGravity(Gravity.CENTER_VERTICAL);
			t.setMaxLines(2);
			t.setEllipsize(TextUtils.TruncateAt.END);
			t.setCompoundDrawablePadding(dp(12));
			if (r.header) {
				t.setPadding(dp(10), dp(10), dp(10), dp(3));
				t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
				t.setTextColor(GRAY);
				t.setTypeface(Typeface.DEFAULT_BOLD);
				t.setCompoundDrawables(null, null, null, null);
				t.setText(r.label.toUpperCase(Locale.ROOT));
				return t;
			}
			t.setPadding(dp(10), dp(8), dp(10), dp(8));
			t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
			t.setTextColor(r.highlight ? GREEN : r.dim ? DIM : Color.WHITE);
			t.setTypeface(r.highlight ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
			t.setCompoundDrawables(r.icon != 0 ? icon(r.icon, r.highlight ? GREEN : r.dim ? DIM : Color.WHITE, 22) : null, null, null, null);
			if (r.sub == null || r.sub.isEmpty()) {
				t.setText(r.label);
			} else {
				SpannableString s = new SpannableString(r.label + "\n" + r.sub);
				int start = r.label.length() + 1;
				s.setSpan(new ForegroundColorSpan(GRAY), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				s.setSpan(new RelativeSizeSpan(0.8f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				t.setText(s);
			}
			return t;
		}
	}

	// ---- screens ----

	private Screen current() {
		return stack.isEmpty() ? null : stack.get(stack.size() - 1);
	}

	private void push(Screen s) {
		Screen cur = current();
		if (cur != null) {
			cur.selection = list.getSelectedItemPosition();
		}
		stack.add(s);
		render();
	}

	private void pop() {
		if (stack.size() <= 1) {
			moveTaskToBack(true);
			return;
		}
		stack.remove(stack.size() - 1);
		render();
	}

	private void render() {
		Screen s = current();
		titleView.setText(s.title);
		InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if (s.nowPlaying) {
			list.setVisibility(View.GONE);
			searchBox.setVisibility(View.GONE);
			npView.setVisibility(View.VISIBLE);
			imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
			hintView.setText(api.isLoggedIn() ? R.string.hint_np_web : R.string.hint_np_free);
			updateNow();
			refreshPlayerOptions();
			return;
		}
		npView.setVisibility(View.GONE);
		list.setVisibility(View.VISIBLE);
		adapter.rows = s.rows;
		adapter.notifyDataSetChanged();
		if (s.search) {
			searchBox.setVisibility(View.VISIBLE);
			if (s.rows.isEmpty() || !api.isLoggedIn()) {
				searchBox.requestFocus();
				imm.showSoftInput(searchBox, 0);
			} else {
				list.requestFocus();
			}
		} else {
			searchBox.setVisibility(View.GONE);
			imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
			list.requestFocus();
		}
		list.setSelection(Math.min(s.selection, Math.max(0, s.rows.size() - 1)));
		hintView.setText(s.reorder ? R.string.hint_tabs : R.string.hint_list);
		updateSoftKeys();
	}

	/** Grey bar labels: Menu on the left, queue on the right, centre key action in the middle. */
	private void updateSoftKeys() {
		Screen s = current();
		if (softKeys == null || s == null) {
			return;
		}
		String center;
		if (s.nowPlaying) {
			center = isPlaying() ? t(R.string.sk_pause) : t(R.string.sk_play);
		} else if (s.reorder) {
			center = s.moving >= 0 ? t(R.string.sk_drop) : t(R.string.sk_choose);
		} else {
			center = "OK";
		}
		boolean web = api.isLoggedIn();
		softKeys.set(web ? t(R.string.sk_menu) : t(R.string.sk_settings), center, web ? t(R.string.sk_queue) : "");
	}

	private Row header(String label) {
		Row r = new Row(0, label, null, null);
		r.header = true;
		return r;
	}

	private void showHome() {
		stack.clear();
		Screen s = new Screen("MiniSpot");
		s.home = true;
		nowRow = new Row(R.drawable.ic_play, t(R.string.now_playing), null, this::showNowPlaying);
		nowRow.highlight = true;
		s.rows.add(nowRow);
		if (api.isLoggedIn()) {
			List<String> order = homeOrder();
			order.removeAll(hiddenTabs());
			for (int i = 0; i < order.size(); i++) {
				String key = order.get(i);
				if (key.equals("recents")) {
					s.rows.add(header(t(R.string.recent)));
					loadInto(s, "/me/player/recently-played?limit=50", this::parseRecentShortcuts);
					if (i < order.size() - 1) {
						s.rows.add(header(t(R.string.library)));
					}
				} else {
					s.rows.add(homeRow(key));
				}
			}
		} else {
			s.rows.add(new Row(R.drawable.ic_search, t(R.string.search), t(R.string.search_in_spotify), this::showSearch));
		}
		s.rows.add(new Row(R.drawable.ic_settings, t(R.string.settings), null, this::showSettings));
		push(s);
		updateNow();
	}

	private List<String> homeOrder() {
		String saved = getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_HOME_ORDER, "");
		List<String> order = new ArrayList<>();
		for (String k : saved.split(",")) {
			if (Arrays.asList(HOME_KEYS).contains(k) && !order.contains(k)) {
				order.add(k);
			}
		}
		for (String k : HOME_KEYS) {
			if (!order.contains(k)) {
				order.add(k); // entries added in a newer version go last
			}
		}
		return order;
	}

	private Row homeRow(String key) {
		switch (key) {
			case "recents":
				return new Row(R.drawable.ic_history, t(R.string.recent), t(R.string.recent_sub), null);
			case "search":
				return new Row(R.drawable.ic_search, t(R.string.search), null, this::showSearch);
			case "playlists":
				return new Row(R.drawable.ic_playlist, t(R.string.playlists), null, this::openPlaylists);
			case "liked":
				return new Row(R.drawable.ic_heart, t(R.string.liked), null, () -> openList(t(R.string.liked), "/me/tracks?limit=50", j -> parseTracks(j, "track")));
			case "albums":
				return new Row(R.drawable.ic_album, t(R.string.albums), null, () -> openList(t(R.string.albums), "/me/albums?limit=50", this::parseSavedAlbums));
			case "artists":
				return new Row(R.drawable.ic_artist, t(R.string.artists), null, () -> openList(t(R.string.artists), "/me/following?type=artist&limit=50", this::parseFollowedArtists));
			case "podcasts":
				return new Row(R.drawable.ic_podcast, t(R.string.podcasts), null, () -> openList(t(R.string.podcasts), "/me/shows?limit=50", this::parseShows));
			default:
				return new Row(R.drawable.ic_history, t(R.string.history), null, () -> openList(t(R.string.history), "/me/player/recently-played?limit=50", this::parseRecent));
		}
	}

	private Set<String> hiddenTabs() {
		String saved = getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_HOME_HIDDEN, "");
		return new HashSet<>(Arrays.asList(saved.split(",")));
	}

	/** t(R.string.home_tabs): OK on an entry offers to move it or to hide / show it. */
	private void showReorder() {
		Screen s = new Screen(t(R.string.home_tabs));
		s.reorder = true;
		Set<String> hidden = hiddenTabs();
		for (String key : homeOrder()) {
			Row r = homeRow(key);
			r.kind = key;
			r.dim = hidden.contains(key);
			r.sub = r.dim ? t(R.string.tab_hidden) : t(R.string.tab_shown);
			r.action = () -> onTabRow(r);
			s.rows.add(r);
		}
		push(s);
		list.setSelection(0);
	}

	private void onTabRow(Row r) {
		Screen s = current();
		if (s.moving >= 0) {
			dropTab(s);
			return;
		}
		String[] choices = {t(R.string.move), r.dim ? t(R.string.show_on_home) : t(R.string.hide_from_home)};
		new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
			.setTitle(r.label)
			.setItems(choices, (dialog, which) -> {
				if (which == 0) {
					s.moving = s.rows.indexOf(r);
					r.highlight = true;
					r.sub = t(R.string.moving_hint);
				} else {
					r.dim = !r.dim;
					r.sub = r.dim ? t(R.string.tab_hidden) : t(R.string.tab_shown);
					saveTabs(s);
					toast(t(r.dim ? R.string.tab_hidden_toast : R.string.tab_shown_toast, r.label));
				}
				adapter.notifyDataSetChanged();
				updateSoftKeys();
			})
			.show();
	}

	private void dropTab(Screen s) {
		Row r = s.rows.get(s.moving);
		s.moving = -1;
		r.highlight = false;
		r.sub = r.dim ? t(R.string.tab_hidden) : t(R.string.tab_shown);
		saveTabs(s);
		toast(t(R.string.order_saved));
		adapter.notifyDataSetChanged();
		updateSoftKeys();
	}

	private void saveTabs(Screen s) {
		StringBuilder order = new StringBuilder();
		StringBuilder hidden = new StringBuilder();
		for (Row x : s.rows) {
			order.append(order.length() > 0 ? "," : "").append(x.kind);
			if (x.dim) {
				hidden.append(hidden.length() > 0 ? "," : "").append(x.kind);
			}
		}
		getSharedPreferences("settings", MODE_PRIVATE).edit()
			.putString(PREF_HOME_ORDER, order.toString())
			.putString(PREF_HOME_HIDDEN, hidden.toString())
			.apply();
	}

	private void moveReorderRow(Screen s, int delta) {
		int to = s.moving + delta;
		if (to < 0 || to >= s.rows.size()) {
			return;
		}
		Row r = s.rows.remove(s.moving);
		s.rows.add(to, r);
		s.moving = to;
		adapter.notifyDataSetChanged();
		list.setSelection(to);
	}

	private void showSettings() {
		Screen s = new Screen(t(R.string.settings));
		if (api.isLoggedIn()) {
			s.rows.add(new Row(R.drawable.ic_logout, t(R.string.logout), null, () -> {
				api.logout();
				toast(t(R.string.logged_out));
				showHome();
			}));
		} else {
			s.rows.add(new Row(R.drawable.ic_login, t(R.string.login), t(R.string.login_sub), this::login));
		}
		String id = api.clientId();
		s.rows.add(new Row(R.drawable.ic_device, t(R.string.client_id),
			id.isEmpty() ? t(R.string.not_set) : id.substring(0, 6) + "…", () -> askClientId(null)));
		if (api.isLoggedIn()) {
			s.rows.add(new Row(R.drawable.ic_reorder, t(R.string.home_tabs), t(R.string.home_tabs_sub), this::showReorder));
		}
		s.rows.add(new Row(R.drawable.ic_device, t(R.string.player), chosen.isEmpty() ? t(R.string.automatic) : appLabel(chosen), this::showPlayers));
		s.rows.add(new Row(R.drawable.ic_language, t(R.string.language), languageName(), this::showLanguages));
		s.rows.add(new Row(R.drawable.ic_help, t(R.string.key_help), null, this::showHelp));
		push(s);
	}

	private String languageName() {
		String lang = getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_LANG, "");
		for (String[] l : LANGUAGES) {
			if (l[0].equals(lang)) {
				return l[1];
			}
		}
		return t(R.string.lang_system);
	}

	private void showLanguages() {
		String current = getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_LANG, "");
		Screen s = new Screen(t(R.string.language));
		s.rows.add(new Row(current.isEmpty() ? R.drawable.ic_check : R.drawable.ic_language, t(R.string.lang_system), null, () -> chooseLanguage("")));
		for (String[] l : LANGUAGES) {
			s.rows.add(new Row(l[0].equals(current) ? R.drawable.ic_check : R.drawable.ic_language, l[1], null, () -> chooseLanguage(l[0])));
		}
		push(s);
	}

	/** Saves the language and restarts the screen in it. */
	private void chooseLanguage(String lang) {
		getSharedPreferences("settings", MODE_PRIVATE).edit().putString(PREF_LANG, lang).apply();
		recreate();
	}

	private String appLabel(String pkg) {
		try {
			return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
		} catch (Exception e) {
			return pkg;
		}
	}

	/** Lists the music apps: those receiving media buttons, those with a media session now, and Spotify. */
	private void showPlayers() {
		Set<String> pkgs = new LinkedHashSet<>();
		for (String pkg : PLAYERS) {
			try {
				getPackageManager().getApplicationInfo(pkg, 0);
				pkgs.add(pkg);
			} catch (Exception ignored) {
			}
		}
		for (ResolveInfo ri : getPackageManager().queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0)) {
			pkgs.add(ri.activityInfo.packageName);
		}
		try {
			MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
			for (MediaController c : msm.getActiveSessions(new ComponentName(this, NotifListener.class))) {
				pkgs.add(c.getPackageName());
			}
		} catch (SecurityException ignored) {
		}
		pkgs.remove(getPackageName());

		Screen s = new Screen(t(R.string.player));
		s.rows.add(new Row(chosen.isEmpty() ? R.drawable.ic_check : R.drawable.ic_device, t(R.string.automatic),
			t(R.string.player_auto_sub), () -> choosePlayer("")));
		for (String pkg : pkgs) {
			s.rows.add(new Row(pkg.equals(chosen) ? R.drawable.ic_check : R.drawable.ic_device, appLabel(pkg), pkg, () -> choosePlayer(pkg)));
		}
		push(s);
	}

	private void choosePlayer(String pkg) {
		chosen = pkg;
		getSharedPreferences("settings", MODE_PRIVATE).edit().putString(PREF_PLAYER, pkg).apply();
		setController(null);
		findSession();
		toast(t(R.string.player_set, pkg.isEmpty() ? t(R.string.automatic) : appLabel(pkg)));
		showHome();
	}

	private void showHelp() {
		Screen s = new Screen(t(R.string.key_help));
		boolean web = api.isLoggedIn();
		String[][] help = {
			{t(R.string.h_updown), t(R.string.h_updown_d)}, {t(R.string.h_ok), t(R.string.h_ok_d)},
			{t(R.string.h_back), t(R.string.h_back_d)},
			{"5", t(R.string.h_play_d)}, {"1 / 3", t(R.string.h_track_d)},
			{"4 / 6", t(R.string.h_seek_d)}, {"2 / 8", t(R.string.h_volume_d)},
			{"7", t(web ? R.string.h_shuffle_d : R.string.h_radio_d)}, {"9", t(R.string.h_repeat_d)},
			{t(R.string.h_zero), t(R.string.h_zero_d)}, {"✱", t(R.string.h_like_d)},
			{"#", t(R.string.h_pound_d)},
			{t(R.string.h_menu, t(R.string.sk_menu)), t(R.string.h_menu_d)},
			{t(R.string.h_queue, t(R.string.sk_queue)), t(R.string.queue_title)},
		};
		for (String[] h : help) {
			s.rows.add(new Row(0, h[0], h[1], null));
		}
		push(s);
	}

	private void showNowPlaying() {
		Screen cur = current();
		if (cur != null && cur.nowPlaying) {
			return;
		}
		Screen s = new Screen(t(R.string.now_playing));
		s.nowPlaying = true;
		push(s);
	}

	private void showSearch() {
		Screen s = new Screen(t(R.string.search));
		s.search = true;
		if (!api.isLoggedIn()) {
			s.rows.add(header(t(R.string.type_then_ok)));
		}
		searchBox.setText("");
		push(s);
	}

	private void runSearch() {
		String q = searchBox.getText().toString().trim();
		if (q.isEmpty()) {
			return;
		}
		((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
		if (api.isLoggedIn()) {
			webSearch(q);
			return;
		}
		stack.remove(stack.size() - 1);
		showNowPlaying();
		// Spotify ignores playFromSearch() from apps it does not know, but opens its results page for a search link.
		Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))
			.setPackage(player())
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			startActivity(i);
		} catch (Exception e) {
			toast(t(R.string.spotify_missing));
		}
	}

	/**
	 * Adds the player's own buttons (playback speed, add to collection, radio…) to a menu.
	 * The 15 s seek buttons are left out: keys 4 and 6 do that.
	 */
	private void addSessionActions(List<String> labels, List<Runnable> actions) {
		PlaybackState st = controller != null ? controller.getPlaybackState() : null;
		if (st == null) {
			return;
		}
		for (PlaybackState.CustomAction a : st.getCustomActions()) {
			String id = a.getAction().toUpperCase(Locale.ROOT);
			if (id.contains("SEEK")) {
				continue;
			}
			labels.add(actionName(id, String.valueOf(a.getName())));
			actions.add(() -> {
				controller.getTransportControls().sendCustomAction(a, null);
				toast(actionName(id, String.valueOf(a.getName())));
			});
		}
	}

	private String actionName(String id, String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (id.contains("SPEED")) return t(R.string.action_speed);
		if (id.contains("RADIO")) return t(R.string.action_radio);
		if (id.contains("REPEAT")) return t(R.string.action_repeat);
		if (id.contains("SHUFFLE")) return t(R.string.action_shuffle);
		if (n.startsWith("remove")) return t(R.string.action_collection_remove);
		if (n.startsWith("add") || id.contains("COLLECTION") || id.contains("EPISODES")) return t(R.string.action_collection_add);
		return name;
	}

	// ---- Web API: login (authorization code + PKCE in the phone's browser) ----

	/** Asks for the Client ID of the user's own app on developer.spotify.com. */
	private void askClientId(Runnable then) {
		EditText input = new EditText(this);
		input.setSingleLine(true);
		input.setHint(R.string.client_id_hint);
		input.setText(api.clientId());
		new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
			.setTitle(t(R.string.client_id))
			.setMessage(t(R.string.client_id_msg))
			.setView(input)
			.setPositiveButton("OK", (dialog, which) -> {
				String id = input.getText().toString().trim();
				if (!Api.isValidClientId(id)) {
					toast(t(R.string.client_id_invalid));
					return;
				}
				api.setClientId(id);
				toast(t(R.string.client_id_saved));
				if (then != null) {
					then.run();
				} else {
					showHome();
				}
			})
			.setNegativeButton(t(R.string.cancel), null)
			.show();
	}

	private void login() {
		if (!Api.isValidClientId(api.clientId())) {
			askClientId(this::login);
			return;
		}
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(api.loginUrl(SCOPES))));
			toast(t(R.string.login_in_browser));
		} catch (Exception e) {
			toast(t(R.string.no_browser));
		}
	}

	private void handleLoginRedirect(Intent intent) {
		Uri data = intent != null ? intent.getData() : null;
		if (data == null || !BuildConfig.REDIRECT_URI.startsWith(data.getScheme() + "://")) {
			return;
		}
		intent.setData(null);
		String code = data.getQueryParameter("code");
		String state = data.getQueryParameter("state");
		if (code == null) {
			toast(t(R.string.login_cancelled, data.getQueryParameter("error")));
			return;
		}
		toast(t(R.string.logging_in));
		io.execute(() -> {
			try {
				api.exchangeCode(code, state);
				toast(t(R.string.logged_in));
				main.post(this::showHome);
			} catch (Exception e) {
				Log.w(TAG, "login", e);
				toast(t(R.string.login_failed, errorText(e)));
			}
		});
	}

	// ---- Web API: browsing ----

	private void openList(String title, String url, Parser parser) {
		Screen s = new Screen(title);
		loadInto(s, url, parser);
		push(s);
	}

	private void addPlayRows(Screen s, String contextUri) {
		s.contextUri = contextUri;
		s.rows.add(new Row(R.drawable.ic_play, t(R.string.play_all), null, () -> playContext(contextUri, false)));
		s.rows.add(new Row(R.drawable.ic_shuffle, t(R.string.shuffle_play), null, () -> playContext(contextUri, true)));
	}

	private void openPlaylist(String name, String id, String uri) {
		Screen s = new Screen(name);
		addPlayRows(s, uri);
		// Spotify only lists the tracks of playlists you own or collaborate on; the others still play.
		loadInto(s, "/playlists/" + id + "/items?limit=50", j -> parseTracks(j, "item"));
		push(s);
	}

	private void openAlbum(String name, String id, String uri) {
		Screen s = new Screen(name);
		addPlayRows(s, uri);
		loadInto(s, "/albums/" + id + "/tracks?limit=50", j -> parseTracks(j, null));
		push(s);
	}

	private void openArtist(String name, String id, String uri) {
		Screen s = new Screen(name);
		s.rows.add(new Row(R.drawable.ic_play, t(R.string.play_artist), null, () -> playContext(uri, false)));
		s.rows.add(new Row(R.drawable.ic_shuffle, t(R.string.shuffle_play), null, () -> playContext(uri, true)));
		s.rows.add(header(t(R.string.albums_singles)));
		loadInto(s, "/artists/" + id + "/albums?include_groups=album,single&limit=50", this::parseAlbums);
		push(s);
	}

	private void openShow(String name, String id, String uri) {
		Screen s = new Screen(name);
		s.rows.add(new Row(R.drawable.ic_play, t(R.string.play), null, () -> playContext(uri, false)));
		s.rows.add(libraryRow(uri, t(R.string.follow_podcast), t(R.string.unfollow_podcast)));
		loadInto(s, "/shows/" + id + "/episodes?limit=50", this::parseEpisodes);
		push(s);
	}

	private void webSearch(String q) {
		Screen s = current();
		s.rows.clear();
		s.selection = 0;
		String base = "/search?q=" + Api.enc(q) + "&limit=10&type=";
		loadInto(s, base + "track,artist,album,playlist,show", json -> {
			Page p = new Page();
			appendSection(p, t(R.string.tracks), json.optJSONObject("tracks"), j -> parseTracks(j, null));
			appendSection(p, t(R.string.artists), json.optJSONObject("artists"), this::parseArtists);
			appendSection(p, t(R.string.albums), json.optJSONObject("albums"), this::parseAlbums);
			appendSection(p, t(R.string.playlists), json.optJSONObject("playlists"), this::parsePlaylists);
			appendSection(p, t(R.string.podcasts), json.optJSONObject("shows"), this::parseShowItems);
			if (p.rows.isEmpty()) {
				p.rows.add(header(t(R.string.no_results)));
			}
			return p;
		});
		adapter.notifyDataSetChanged();
		list.requestFocus();
	}

	private void appendSection(Page out, String title, JSONObject paging, Parser parser) throws Exception {
		if (paging == null) {
			return;
		}
		Page p = parser.parse(paging);
		if (p.rows.isEmpty()) {
			return;
		}
		out.rows.add(header(title));
		out.rows.addAll(p.rows);
		if (p.next != null) {
			// search "next" pages wrap the paging object in its type key, e.g. {"tracks": {...}}
			out.rows.add(moreRow(t(R.string.more_of, title.toLowerCase()), p.next, j -> {
				JSONObject inner = j.optJSONObject(j.keys().next());
				return parser.parse(inner != null && inner.has("items") ? inner : j);
			}));
		}
	}

	private void loadInto(Screen s, String url, Parser parser) {
		Row placeholder = header(t(R.string.loading));
		s.rows.add(placeholder);
		io.execute(() -> {
			Page page = null;
			String error = null;
			try {
				page = parser.parse(api.get(url));
			} catch (Exception e) {
				Log.w(TAG, "load " + url, e);
				error = errorText(e);
			}
			Page result = page;
			String err = error;
			main.post(() -> {
				int at = s.rows.indexOf(placeholder);
				if (at < 0) {
					return;
				}
				s.rows.remove(at);
				if (result != null) {
					s.rows.addAll(at, result.rows);
					if (result.next != null) {
						s.rows.add(at + result.rows.size(), moreRow(t(R.string.load_more), result.next, parser));
					}
					if (result.rows.isEmpty() && result.next == null) {
						s.rows.add(at, header(t(R.string.empty)));
					}
				} else {
					s.rows.add(at, header(err));
					if (!api.isLoggedIn()) {
						toast(err);
						showHome();
						return;
					}
				}
				if (current() == s) {
					adapter.notifyDataSetChanged();
					int sel = list.getSelectedItemPosition();
					if (sel < 0 || sel >= s.rows.size() || s.rows.get(sel).header) {
						selectFirstEnabled(s.home ? 0 : at);
					}
				}
			});
		});
	}

	private Row moreRow(String label, String nextUrl, Parser parser) {
		Row more = new Row(R.drawable.ic_more, label, null, null);
		more.action = () -> loadMore(more, nextUrl, parser);
		return more;
	}

	private void loadMore(Row more, String nextUrl, Parser parser) {
		Screen s = current();
		String label = more.label;
		more.header = true;
		more.label = t(R.string.loading);
		adapter.notifyDataSetChanged();
		io.execute(() -> {
			Page page = null;
			String error = null;
			try {
				page = parser.parse(api.get(nextUrl));
			} catch (Exception e) {
				error = errorText(e);
			}
			Page result = page;
			String err = error;
			main.post(() -> {
				int at = s.rows.indexOf(more);
				if (at < 0) {
					return;
				}
				if (result == null) {
					more.header = false;
					more.label = t(R.string.retry_err, err);
				} else {
					s.rows.remove(at);
					s.rows.addAll(at, result.rows);
					if (result.next != null) {
						s.rows.add(at + result.rows.size(), moreRow(label, result.next, parser));
					}
				}
				if (current() == s) {
					adapter.notifyDataSetChanged();
					list.setSelection(Math.min(at, s.rows.size() - 1));
				}
			});
		});
	}

	private void selectFirstEnabled(int from) {
		List<Row> rows = adapter.rows;
		for (int i = Math.max(0, from); i < rows.size(); i++) {
			if (!rows.get(i).header) {
				list.setSelection(i);
				return;
			}
		}
	}

	private String errorText(Exception e) {
		if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException) {
			return t(R.string.no_internet);
		}
		if (e instanceof Api.ApiException && ((Api.ApiException) e).status == 403) {
			return t(R.string.premium_required, e.getMessage());
		}
		return e.getMessage() != null ? e.getMessage() : e.toString();
	}

	// ---- Web API: JSON parsing ----

	private static String artists(JSONObject o) {
		JSONArray a = o.optJSONArray("artists");
		StringBuilder b = new StringBuilder();
		for (int i = 0; a != null && i < a.length(); i++) {
			JSONObject x = a.optJSONObject(i);
			if (x != null) {
				if (b.length() > 0) {
					b.append(", ");
				}
				b.append(x.optString("name"));
			}
		}
		return b.toString();
	}

	private static String nextUrl(JSONObject json) {
		return json.isNull("next") ? null : json.optString("next", null);
	}

	private Row trackRow(JSONObject t) {
		String sub = "episode".equals(t.optString("type"))
			? duration(t.optLong("duration_ms"))
			: artists(t) + " · " + duration(t.optLong("duration_ms"));
		Row r = new Row("episode".equals(t.optString("type")) ? R.drawable.ic_podcast : R.drawable.ic_note, t.optString("name"), sub, null);
		r.uri = t.optString("uri");
		r.kind = "episode".equals(t.optString("type")) ? "episode" : "track";
		r.data = t;
		r.action = () -> playRow(r);
		return r;
	}

	/** Paging object of tracks. wrapKey: "track" / "item" when each entry wraps the track, null for plain tracks. */
	private Page parseTracks(JSONObject json, String wrapKey) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		int offset = json.optInt("offset", 0);
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject t = items.optJSONObject(i);
			if (t != null && wrapKey != null) {
				JSONObject inner = t.optJSONObject(wrapKey);
				t = inner != null ? inner : t.optJSONObject("track");
			}
			if (t == null || t.optString("uri").isEmpty()) {
				continue;
			}
			Row r = trackRow(t);
			r.index = offset + i;
			p.rows.add(r);
		}
		p.next = nextUrl(json);
		return p;
	}

	private Row playlistRow(JSONObject o) {
		JSONObject owner = o.optJSONObject("owner");
		JSONObject count = o.optJSONObject("items");
		if (count == null) {
			count = o.optJSONObject("tracks");
		}
		String sub = (owner != null ? owner.optString("display_name") : "")
			+ (count != null ? " · " + t(R.string.n_tracks, count.optInt("total")) : "");
		String name = o.optString("name"), id = o.optString("id"), uri = o.optString("uri");
		return tag(new Row(R.drawable.ic_playlist, name, sub, () -> openPlaylist(name, id, uri)), "playlist", o);
	}

	private Page parsePlaylists(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject o = items.optJSONObject(i);
			if (o != null) {
				p.rows.add(playlistRow(o));
			}
		}
		p.next = nextUrl(json);
		return p;
	}

	private Row albumRow(JSONObject o) {
		String year = o.optString("release_date", "");
		year = year.length() >= 4 ? " · " + year.substring(0, 4) : "";
		String name = o.optString("name"), id = o.optString("id"), uri = o.optString("uri");
		return tag(new Row(R.drawable.ic_album, name, artists(o) + year, () -> openAlbum(name, id, uri)), "album", o);
	}

	private Page parseAlbums(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject o = items.optJSONObject(i);
			if (o != null) {
				p.rows.add(albumRow(o));
			}
		}
		p.next = nextUrl(json);
		return p;
	}

	private Page parseSavedAlbums(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject o = items.optJSONObject(i);
			JSONObject a = o != null ? o.optJSONObject("album") : null;
			if (a != null) {
				p.rows.add(albumRow(a));
			}
		}
		p.next = nextUrl(json);
		return p;
	}

	private Row artistRow(JSONObject o) {
		String name = o.optString("name"), id = o.optString("id"), uri = o.optString("uri");
		return tag(new Row(R.drawable.ic_artist, name, t(R.string.artist), () -> openArtist(name, id, uri)), "artist", o);
	}

	private Page parseArtists(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject o = items.optJSONObject(i);
			if (o != null) {
				p.rows.add(artistRow(o));
			}
		}
		p.next = nextUrl(json);
		return p;
	}

	private Page parseFollowedArtists(JSONObject json) {
		JSONObject artists = json.optJSONObject("artists");
		return parseArtists(artists != null ? artists : json);
	}

	private Page parseRecent(JSONObject json) {
		Page p = parseTracks(json, "track");
		p.next = null; // cursor based paging, the last 50 are enough
		return p;
	}

	private Row showRow(JSONObject show) {
		String name = show.optString("name"), id = show.optString("id"), uri = show.optString("uri");
		return tag(new Row(R.drawable.ic_podcast, name, t(R.string.podcast), () -> openShow(name, id, uri)), "show", show);
	}

	/**
	 * The 4 things played last: the playlists, albums, artists and podcasts they were played from,
	 * or the track itself when it was played on its own. Runs on the io thread.
	 */
	private Page parseRecentShortcuts(JSONObject json) {
		Map<String, JSONObject> picks = new LinkedHashMap<>();
		try {
			// the history only lists music: add the podcast playing now
			JSONObject item = api.get("/me/player/currently-playing?additional_types=episode").optJSONObject("item");
			JSONObject show = item != null ? item.optJSONObject("show") : null;
			if (show != null) {
				picks.put(show.optString("uri"), show);
			}
		} catch (Exception e) {
			Log.w(TAG, "currently playing", e);
		}
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length() && picks.size() < 4; i++) {
			JSONObject item = items.optJSONObject(i);
			if (item == null) {
				continue;
			}
			JSONObject ctx = item.optJSONObject("context");
			JSONObject track = item.optJSONObject("track");
			String key = ctx != null ? ctx.optString("uri") : track != null ? track.optString("uri") : "";
			if (!key.isEmpty() && !picks.containsKey(key)) {
				picks.put(key, ctx != null ? ctx : track);
			}
		}
		Page p = new Page();
		for (Map.Entry<String, JSONObject> e : picks.entrySet()) {
			String[] parts = e.getKey().split(":");
			if (parts.length < 3) {
				continue;
			}
			String type = parts[1], id = parts[parts.length - 1];
			try {
				switch (type) {
					case "playlist":
						p.rows.add(playlistRow(api.get("/playlists/" + id + "?fields=name,id,uri,owner(display_name),items(total),tracks(total)")));
						break;
					case "album":
						p.rows.add(albumRow(api.get("/albums/" + id)));
						break;
					case "artist":
						p.rows.add(artistRow(api.get("/artists/" + id)));
						break;
					case "show":
						p.rows.add(showRow(e.getValue().has("name") ? e.getValue() : api.get("/shows/" + id)));
						break;
					case "track":
					case "episode":
						Row r = trackRow(e.getValue());
						r.action = () -> playUris(r.uri);
						p.rows.add(r);
						break;
				}
			} catch (Exception ex) {
				Log.w(TAG, "shortcut " + e.getKey(), ex);
			}
		}
		if (p.rows.isEmpty()) {
			p.rows.add(header(t(R.string.nothing_recent)));
		}
		return p;
	}

	private Page parseShows(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject o = items.optJSONObject(i);
			JSONObject show = o != null ? o.optJSONObject("show") : null;
			if (show != null) {
				p.rows.add(showRow(show));
			}
		}
		p.next = nextUrl(json);
		return p;
	}

	private Page parseShowItems(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject show = items.optJSONObject(i);
			if (show != null) {
				p.rows.add(showRow(show));
			}
		}
		p.next = nextUrl(json);
		return p;
	}

	private Page parseEpisodes(JSONObject json) {
		Page p = new Page();
		JSONArray items = json.optJSONArray("items");
		for (int i = 0; items != null && i < items.length(); i++) {
			JSONObject o = items.optJSONObject(i);
			if (o == null) {
				continue;
			}
			Row r = new Row(R.drawable.ic_podcast, o.optString("name"),
				o.optString("release_date") + " · " + duration(o.optLong("duration_ms")), null);
			r.uri = o.optString("uri");
			r.kind = "episode";
			r.data = o;
			r.action = () -> playRow(r);
			p.rows.add(r);
		}
		p.next = nextUrl(json);
		return p;
	}

	// ---- Web API: playback ----

	private interface ApiCall {
		void run(String deviceId) throws Exception;
	}

	/** Runs a player call on the phone's Spotify device, waking Spotify first when it is not listed. */
	private void onDevice(String done, ApiCall call) {
		io.execute(() -> {
			try {
				String device = findDevice();
				if (device == null) {
					main.post(this::wakePlayer);
					for (int i = 0; i < 6 && device == null; i++) {
						Thread.sleep(1500);
						device = findDevice();
					}
				}
				if (device == null) {
					toast(t(R.string.spotify_not_ready));
					return;
				}
				try {
					call.run(device);
				} catch (Api.ApiException e) {
					if (e.status != 404) {
						throw e;
					}
					// the device went away (Spotify was killed): wake it and retry once
					api.setDeviceId(null);
					main.post(this::wakePlayer);
					Thread.sleep(4000);
					device = findDevice();
					if (device == null) {
						throw e;
					}
					call.run(device);
				}
				if (done != null) {
					toast(done);
				}
			} catch (Exception e) {
				Log.w(TAG, "player", e);
				toast(errorText(e));
			}
		});
	}

	/** Picks this phone among the Spotify Connect devices of the account. */
	private String findDevice() throws Exception {
		JSONArray devices = api.get("/me/player/devices").optJSONArray("devices");
		if (devices == null || devices.length() == 0) {
			return null;
		}
		String saved = api.deviceId();
		String model = Build.MODEL.toLowerCase(Locale.ROOT);
		JSONObject best = null;
		int bestScore = -1;
		for (int i = 0; i < devices.length(); i++) {
			JSONObject d = devices.getJSONObject(i);
			if (d.optBoolean("is_restricted")) {
				continue;
			}
			int score = 0;
			if (d.optString("id").equals(saved)) score += 8;
			if ("Smartphone".equalsIgnoreCase(d.optString("type"))) score += 4;
			if (d.optString("name").toLowerCase(Locale.ROOT).contains(model)) score += 2;
			if (d.optBoolean("is_active")) score += 1;
			if (score > bestScore) {
				best = d;
				bestScore = score;
			}
		}
		if (best == null || bestScore < 4) {
			return null; // only other devices (PC, speaker): wait for the phone's Spotify
		}
		api.setDeviceId(best.optString("id"));
		return best.optString("id");
	}

	private void playContext(String contextUri, boolean shuffle) {
		onDevice(null, device -> {
			api.put("/me/player/shuffle?state=" + shuffle + "&device_id=" + device, null);
			api.put("/me/player/play?device_id=" + device, new JSONObject().put("context_uri", contextUri).toString());
			shuffleOn = shuffle;
		});
		showNowPlaying();
	}

	private void playUris(String uri) {
		onDevice(null, device -> api.put("/me/player/play?device_id=" + device,
			new JSONObject().put("uris", new JSONArray().put(uri)).toString()));
		showNowPlaying();
	}

	/** Plays a track row: inside its playlist/album when known, else as part of the screen's track list. */
	private void playRow(Row r) {
		Screen s = current();
		JSONObject body = new JSONObject();
		try {
			if (s.contextUri != null && r.index >= 0) {
				body.put("context_uri", s.contextUri);
				body.put("offset", new JSONObject().put("position", r.index));
			} else {
				JSONArray uris = new JSONArray();
				int pos = 0;
				for (Row x : s.rows) {
					if (x.uri != null) {
						if (x == r) {
							pos = uris.length();
						}
						uris.put(x.uri);
					}
				}
				body.put("uris", uris);
				body.put("offset", new JSONObject().put("position", pos));
			}
		} catch (Exception ignored) {
		}
		onDevice(null, device -> api.put("/me/player/play?device_id=" + device, body.toString()));
		showNowPlaying();
	}

	private void queueRow(Row r) {
		onDevice(t(R.string.queued, r.label),
			device -> api.post("/me/player/queue?uri=" + Api.enc(r.uri) + "&device_id=" + device));
	}

	/** Reads shuffle / repeat from the Web API for the t(R.string.now_playing) screen. */
	private void refreshPlayerOptions() {
		if (!api.isLoggedIn()) {
			updatePlayerOptions();
			return;
		}
		io.execute(() -> {
			try {
				JSONObject p = api.get("/me/player");
				if (p.has("shuffle_state")) {
					shuffleOn = p.optBoolean("shuffle_state");
					repeatMode = p.optString("repeat_state", "off");
				}
			} catch (Exception e) {
				Log.w(TAG, "player state", e);
			}
			main.post(this::updatePlayerOptions);
		});
	}

	private void updatePlayerOptions() {
		int visibility = api.isLoggedIn() ? View.VISIBLE : View.INVISIBLE;
		npShuffle.setVisibility(visibility);
		npShuffle.setColorFilter(shuffleOn ? GREEN : DIM);
		npRepeat.setVisibility(visibility);
		npRepeat.setImageResource("track".equals(repeatMode) ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
		npRepeat.setColorFilter("off".equals(repeatMode) ? DIM : GREEN);
	}

	private void toggleShuffleWeb() {
		boolean on = !shuffleOn;
		onDevice(on ? t(R.string.shuffle_on) : t(R.string.shuffle_off), device -> {
			api.put("/me/player/shuffle?state=" + on + "&device_id=" + device, null);
			shuffleOn = on;
			main.post(this::updatePlayerOptions);
		});
	}

	private void cycleRepeatWeb() {
		String next = "off".equals(repeatMode) ? "context" : "context".equals(repeatMode) ? "track" : "off";
		String label = "off".equals(next) ? t(R.string.repeat_off) : "context".equals(next) ? t(R.string.repeat_all) : t(R.string.repeat_one);
		onDevice(label, device -> {
			api.put("/me/player/repeat?state=" + next + "&device_id=" + device, null);
			repeatMode = next;
			main.post(this::updatePlayerOptions);
		});
	}

	/** Likes or unlikes the current track with the Web API. */
	private void toggleLikeWeb() {
		MediaMetadata md = controller != null ? controller.getMetadata() : null;
		String sessionId = md != null ? md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) : null;
		io.execute(() -> {
			try {
				String uri = sessionId != null && sessionId.startsWith("spotify:") ? sessionId : null;
				if (uri == null) {
					JSONObject item = api.get("/me/player/currently-playing?additional_types=episode").optJSONObject("item");
					uri = item != null ? item.optString("uri", null) : null;
				}
				if (uri == null) {
					toast(t(R.string.nothing_playing));
					return;
				}
				String q = "/me/library?uris=" + Api.enc(uri);
				boolean saved = api.getArray("/me/library/contains?uris=" + Api.enc(uri)).optBoolean(0);
				if (saved) {
					api.delete(q);
					toast(t(R.string.unliked));
				} else {
					api.put(q, null);
					toast(t(R.string.liked_added));
				}
			} catch (Exception e) {
				toast(errorText(e));
			}
		});
	}

	// ---- pins (kept in MiniSpot: Spotify's own pins are not in the Web API) ----

	private static Row tag(Row r, String kind, JSONObject data) {
		r.kind = kind;
		r.data = data;
		return r;
	}

	private JSONArray loadPins() {
		try {
			return new JSONArray(getSharedPreferences("settings", MODE_PRIVATE).getString(PREF_PINS, "[]"));
		} catch (Exception e) {
			return new JSONArray();
		}
	}

	private boolean isPinned(String uri) {
		JSONArray pins = loadPins();
		for (int i = 0; i < pins.length(); i++) {
			JSONObject pin = pins.optJSONObject(i);
			if (pin != null && uri.equals(pin.optJSONObject("data").optString("uri"))) {
				return true;
			}
		}
		return false;
	}

	private void togglePin(Row r) {
		String uri = r.data.optString("uri");
		JSONArray pins = loadPins();
		JSONArray out = new JSONArray();
		boolean removed = false;
		for (int i = 0; i < pins.length(); i++) {
			JSONObject pin = pins.optJSONObject(i);
			if (pin != null && uri.equals(pin.optJSONObject("data").optString("uri"))) {
				removed = true;
			} else if (pin != null) {
				out.put(pin);
			}
		}
		if (!removed) {
			try {
				out.put(new JSONObject().put("kind", r.kind).put("data", r.data));
			} catch (Exception ignored) {
			}
		}
		getSharedPreferences("settings", MODE_PRIVATE).edit().putString(PREF_PINS, out.toString()).apply();
		toast(removed ? t(R.string.unpinned) : t(R.string.pinned_toast));
	}

	private Row rowFor(String kind, JSONObject data) {
		switch (kind) {
			case "album":
				return albumRow(data);
			case "artist":
				return artistRow(data);
			case "show":
				return showRow(data);
			default:
				return playlistRow(data);
		}
	}

	/** Playlists, with the pinned items (playlists, albums, artists, podcasts) first. */
	private void openPlaylists() {
		Screen s = new Screen(t(R.string.playlists));
		JSONArray pins = loadPins();
		Set<String> pinned = new HashSet<>();
		if (pins.length() > 0) {
			s.rows.add(header(t(R.string.pinned)));
			for (int i = 0; i < pins.length(); i++) {
				JSONObject pin = pins.optJSONObject(i);
				if (pin != null) {
					Row r = rowFor(pin.optString("kind"), pin.optJSONObject("data"));
					r.icon = R.drawable.ic_pin;
					s.rows.add(r);
					pinned.add(r.data.optString("uri"));
				}
			}
			s.rows.add(header(t(R.string.all_playlists)));
		}
		loadInto(s, "/me/playlists?limit=50", j -> {
			Page p = parsePlaylists(j);
			p.rows.removeIf(r -> r.data != null && pinned.contains(r.data.optString("uri")));
			return p;
		});
		push(s);
	}

	// ---- library (Spotify's "add to library" for tracks, episodes, albums, playlists, artists, podcasts) ----

	/** A row that adds / removes uri from the library; its label shows the current state. */
	private Row libraryRow(String uri, String addLabel, String removeLabel) {
		Row r = new Row(R.drawable.ic_heart, "…", null, null);
		boolean[] saved = new boolean[1];
		r.action = () -> setInLibrary(uri, !saved[0], () -> {
			saved[0] = !saved[0];
			r.label = saved[0] ? removeLabel : addLabel;
			adapter.notifyDataSetChanged();
		});
		io.execute(() -> {
			boolean in = inLibrary(uri);
			main.post(() -> {
				saved[0] = in;
				r.label = in ? removeLabel : addLabel;
				adapter.notifyDataSetChanged();
			});
		});
		return r;
	}

	/** Runs on the io thread. */
	private boolean inLibrary(String uri) {
		try {
			return api.getArray("/me/library/contains?uris=" + Api.enc(uri)).optBoolean(0);
		} catch (Exception e) {
			Log.w(TAG, "library contains", e);
			return false;
		}
	}

	private void setInLibrary(String uri, boolean add, Runnable done) {
		io.execute(() -> {
			try {
				String q = "/me/library?uris=" + Api.enc(uri);
				if (add) {
					api.put(q, null);
				} else {
					api.delete(q);
				}
				toast(add ? t(R.string.library_added) : t(R.string.library_removed));
				if (done != null) {
					main.post(done);
				}
			} catch (Exception e) {
				toast(errorText(e));
			}
		});
	}

	// ---- menu key: options of the selected item ----

	private void showMenu() {
		Screen s = current();
		boolean np = s != null && s.nowPlaying;
		if (!api.isLoggedIn()) {
			// no Web API: the player's own buttons, and the settings
			List<String> labels = new ArrayList<>();
			List<Runnable> actions = new ArrayList<>();
			addSessionActions(labels, actions);
			labels.add(t(R.string.settings));
			actions.add(this::showSettings);
			showChoices(t(R.string.options), labels, actions);
			return;
		}
		if (np) {
			io.execute(() -> {
				try {
					JSONObject item = api.get("/me/player/currently-playing?additional_types=episode").optJSONObject("item");
					if (item == null) {
						main.post(() -> {
							List<String> labels = new ArrayList<>();
							List<Runnable> actions = new ArrayList<>();
							addSessionActions(labels, actions);
							showChoices(t(R.string.options), labels, actions);
						});
						return;
					}
					Row r = trackRow(item);
					main.post(() -> menuFor(r, true));
				} catch (Exception e) {
					toast(errorText(e));
				}
			});
			return;
		}
		Row r = selectedRow();
		if (r == null || r.kind == null || r.data == null) {
			toast(t(R.string.no_options_row));
			return;
		}
		menuFor(r, false);
	}

	private void showChoices(String title, List<String> labels, List<Runnable> actions) {
		if (labels.isEmpty()) {
			toast(t(R.string.no_options));
			return;
		}
		new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
			.setTitle(title)
			.setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
			.show();
	}

	private void menuFor(Row r, boolean withSession) {
		String uri = r.data.optString("uri");
		io.execute(() -> {
			boolean saved = inLibrary(uri);
			main.post(() -> openMenu(r, uri, saved, withSession));
		});
	}

	private void openMenu(Row r, String uri, boolean saved, boolean withSession) {
		List<String> labels = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();
		JSONObject d = r.data;
		switch (r.kind) {
			case "track":
			case "episode": {
				boolean episode = r.kind.equals("episode");
				labels.add(t(R.string.add_to_queue));
				actions.add(() -> queueRow(r));
				labels.add(episode
					? (saved ? t(R.string.episode_remove) : t(R.string.episode_save))
					: (saved ? t(R.string.like_remove) : t(R.string.like_add)));
				actions.add(() -> setInLibrary(uri, !saved, null));
				JSONObject show = d.optJSONObject("show");
				if (show != null) {
					labels.add(t(R.string.go_podcast));
					actions.add(() -> openShow(show.optString("name"), show.optString("id"), show.optString("uri")));
				}
				JSONArray artists = d.optJSONArray("artists");
				JSONObject artist = artists != null ? artists.optJSONObject(0) : null;
				if (artist != null) {
					labels.add(t(R.string.go_artist));
					actions.add(() -> openArtist(artist.optString("name"), artist.optString("id"), artist.optString("uri")));
				}
				JSONObject album = d.optJSONObject("album");
				if (album != null) {
					labels.add(t(R.string.go_album));
					actions.add(() -> openAlbum(album.optString("name"), album.optString("id"), album.optString("uri")));
				}
				break;
			}
			default: {
				labels.add(t(R.string.play));
				actions.add(() -> playContext(uri, false));
				if (!r.kind.equals("show")) {
					labels.add(t(R.string.shuffle_play));
					actions.add(() -> playContext(uri, true));
				}
				labels.add(isPinned(uri) ? t(R.string.unpin) : t(R.string.pin));
				actions.add(() -> {
					togglePin(r);
					Screen s = current();
					if (s != null && t(R.string.playlists).equals(s.title)) {
						stack.remove(stack.size() - 1);
						openPlaylists();
					}
				});
				boolean follow = r.kind.equals("artist") || r.kind.equals("show");
				labels.add(follow
					? (saved ? t(R.string.unfollow) : t(R.string.follow))
					: (saved ? t(R.string.library_remove) : t(R.string.library_add)));
				actions.add(() -> setInLibrary(uri, !saved, null));
				JSONArray artists = d.optJSONArray("artists");
				JSONObject artist = artists != null ? artists.optJSONObject(0) : null;
				if (r.kind.equals("album") && artist != null) {
					labels.add(t(R.string.go_artist));
					actions.add(() -> openArtist(artist.optString("name"), artist.optString("id"), artist.optString("uri")));
				}
				break;
			}
		}
		if (withSession) {
			addSessionActions(labels, actions);
		}
		showChoices(r.label, labels, actions);
	}

	// ---- queue (bottom right key) ----

	private void showQueue() {
		Screen cur = current();
		if (cur != null && t(R.string.queue_title).equals(cur.title)) {
			return;
		}
		Screen s = new Screen(t(R.string.queue_title));
		loadInto(s, "/me/player/queue", json -> {
			Page p = new Page();
			JSONObject now = json.optJSONObject("currently_playing");
			if (now != null) {
				p.rows.add(header(t(R.string.now_playing)));
				Row r = trackRow(now);
				r.action = this::showNowPlaying;
				p.rows.add(r);
			}
			JSONArray queue = json.optJSONArray("queue");
			if (queue != null && queue.length() > 0) {
				p.rows.add(header(t(R.string.up_next)));
				for (int i = 0; i < queue.length(); i++) {
					JSONObject t = queue.optJSONObject(i);
					if (t == null) {
						continue;
					}
					Row r = trackRow(t);
					int skips = i + 1;
					r.action = () -> skipTo(skips);
					p.rows.add(r);
				}
			} else {
				p.rows.add(header(t(R.string.queue_empty)));
			}
			return p;
		});
		push(s);
	}

	/** Plays the n-th queued track by skipping to it. */
	private void skipTo(int skips) {
		onDevice(null, device -> {
			for (int i = 0; i < skips; i++) {
				api.post("/me/player/next?device_id=" + device);
			}
		});
		showNowPlaying();
	}

	// ---- media session actions ----

	private boolean isPlaying() {
		if (optimisticPlaying != null && SystemClock.uptimeMillis() < optimisticUntil) {
			return optimisticPlaying;
		}
		PlaybackState st = controller != null ? controller.getPlaybackState() : null;
		return st != null && st.getState() == PlaybackState.STATE_PLAYING;
	}

	private void togglePlay() {
		MediaController.TransportControls c = controls();
		if (c == null) {
			return;
		}
		boolean playing = isPlaying();
		if (playing) {
			c.pause();
		} else {
			c.play();
		}
		// show the new state at once; the session callback confirms it
		optimisticPlaying = !playing;
		optimisticUntil = SystemClock.uptimeMillis() + 3000;
		updateNow();
	}

	private void seek(long deltaMs) {
		PlaybackState st = controller != null ? controller.getPlaybackState() : null;
		MediaController.TransportControls c = controls();
		if (c != null && st != null) {
			c.seekTo(Math.max(0, position(st) + deltaMs));
		}
	}

	/** Sends the first custom action whose id or name contains one of the keywords. */
	private void customAction(String... keywords) {
		PlaybackState st = controller != null ? controller.getPlaybackState() : null;
		if (st != null) {
			for (PlaybackState.CustomAction a : st.getCustomActions()) {
				String key = (a.getAction() + " " + a.getName()).toLowerCase(Locale.ROOT);
				for (String k : keywords) {
					if (key.contains(k)) {
						controller.getTransportControls().sendCustomAction(a, null);
						toast(String.valueOf(a.getName()));
						return;
					}
				}
			}
		}
		toast(t(R.string.action_unavailable));
	}

	private void volume(boolean up) {
		audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
			up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
	}

	// ---- now playing ----

	private static long position(PlaybackState st) {
		long pos = st.getPosition();
		if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
			pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
		}
		return pos;
	}

	private static String duration(long ms) {
		long s = Math.max(0, ms) / 1000;
		return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
	}

	private void updateNow() {
		MediaMetadata md = controller != null ? controller.getMetadata() : null;
		String title = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
		String artist = md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
		String album = md != null ? md.getString(MediaMetadata.METADATA_KEY_ALBUM) : null;
		boolean playing = isPlaying();

		if (controller == null) {
			nowBar.setText(t(R.string.connecting_player));
			nowBar.setCompoundDrawables(null, null, null, null);
		} else if (title == null) {
			nowBar.setText(t(R.string.nothing_playing));
			nowBar.setCompoundDrawables(null, null, null, null);
		} else {
			nowBar.setText(title + (artist != null ? " · " + artist : ""));
			nowBar.setCompoundDrawables(icon(playing ? R.drawable.ic_equalizer : R.drawable.ic_pause, GREEN, 14), null, null, null);
		}

		if (nowRow != null) {
			nowRow.icon = playing ? R.drawable.ic_equalizer : R.drawable.ic_pause;
			nowRow.label = title != null ? title : t(R.string.now_playing);
			nowRow.sub = title != null ? artist : null;
			Screen s = current();
			if (s != null && s.home) {
				adapter.notifyDataSetChanged();
			}
		}

		Screen s = current();
		if (s == null || !s.nowPlaying) {
			return;
		}
		npTitle.setText(title != null ? title : controller == null ? t(R.string.connecting_player) : t(R.string.nothing_playing));
		npArtist.setText(artist != null ? artist : "");
		npAlbum.setText(album != null ? album : "");
		npPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
		updateSoftKeys();
		updateProgress();
	}

	private void updateProgress() {
		Screen s = current();
		if (s == null || !s.nowPlaying || controller == null) {
			return;
		}
		PlaybackState st = controller.getPlaybackState();
		MediaMetadata md = controller.getMetadata();
		if (st == null || md == null) {
			npProgress.setProgress(0);
			npTime.setText("");
			return;
		}
		long dur = Math.max(1, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
		long pos = Math.min(position(st), dur);
		npProgress.setProgress((int) (pos * 1000 / dur));
		npTime.setText(duration(pos) + " / " + duration(dur));
	}

	// ---- keys ----

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (getCurrentFocus() instanceof EditText && keyCode != KeyEvent.KEYCODE_BACK) {
			return super.onKeyDown(keyCode, event);
		}
		Screen s = current();
		boolean np = s != null && s.nowPlaying;
		boolean web = api.isLoggedIn();
		if (event.getRepeatCount() > 0 && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
			&& keyCode != KeyEvent.KEYCODE_2 && keyCode != KeyEvent.KEYCODE_8) {
			return true;
		}
		MediaController.TransportControls c;
		switch (keyCode) {
			case KeyEvent.KEYCODE_5:
				togglePlay();
				return true;
			case KeyEvent.KEYCODE_1:
				if ((c = controls()) != null) c.skipToPrevious();
				return true;
			case KeyEvent.KEYCODE_3:
				if ((c = controls()) != null) c.skipToNext();
				return true;
			case KeyEvent.KEYCODE_4:
				seek(-15_000);
				return true;
			case KeyEvent.KEYCODE_6:
				seek(15_000);
				return true;
			case KeyEvent.KEYCODE_2:
				volume(true);
				return true;
			case KeyEvent.KEYCODE_8:
				volume(false);
				return true;
			case KeyEvent.KEYCODE_STAR:
				if (web) {
					toggleLikeWeb();
				} else {
					customAction("collection", "like", "heart");
				}
				return true;
			case KeyEvent.KEYCODE_7:
				if (web) {
					toggleShuffleWeb();
				} else {
					customAction("radio");
				}
				return true;
			case KeyEvent.KEYCODE_9:
				if (web) {
					cycleRepeatWeb();
				} else {
					customAction("repeat");
				}
				return true;
			case KeyEvent.KEYCODE_POUND: {
				Row sel = selectedRow();
				if (web && sel != null && sel.uri != null) {
					queueRow(sel);
				} else {
					showSearch();
				}
				return true;
			}
			case KeyEvent.KEYCODE_MENU:
			case KeyEvent.KEYCODE_SOFT_LEFT:
			case KeyEvent.KEYCODE_F1:
			case KeyEvent.KEYCODE_F11:
				showMenu();
				return true;
			case KeyEvent.KEYCODE_SOFT_RIGHT:
			case KeyEvent.KEYCODE_F2:
			case KeyEvent.KEYCODE_F12:
				if (web) {
					showQueue();
				}
				return true;
			case KeyEvent.KEYCODE_0:
			case KeyEvent.KEYCODE_CALL:
				if (np) {
					pop();
				} else {
					showNowPlaying();
				}
				return true;
		}
		if (np) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_DPAD_CENTER:
				case KeyEvent.KEYCODE_ENTER:
					togglePlay();
					return true;
				case KeyEvent.KEYCODE_DPAD_LEFT:
					if ((c = controls()) != null) c.skipToPrevious();
					return true;
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					if ((c = controls()) != null) c.skipToNext();
					return true;
				case KeyEvent.KEYCODE_DPAD_UP:
					volume(true);
					return true;
				case KeyEvent.KEYCODE_DPAD_DOWN:
					volume(false);
					return true;
			}
		} else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
			pop();
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		// the list would move the selection itself: take the arrows while an entry is being moved
		Screen s = current();
		int code = event.getKeyCode();
		if (s != null && s.reorder && s.moving >= 0
			&& (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
			if (event.getAction() == KeyEvent.ACTION_DOWN) {
				moveReorderRow(s, code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
			}
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	private Row selectedRow() {
		int pos = list.getSelectedItemPosition();
		if (list.getVisibility() != View.VISIBLE || pos < 0 || pos >= adapter.rows.size()) {
			return null;
		}
		return adapter.rows.get(pos);
	}

	@Override
	public void onBackPressed() {
		Screen s = current();
		if (s != null && s.search && searchBox.hasFocus() && !s.rows.isEmpty() && api.isLoggedIn()) {
			list.requestFocus();
			return;
		}
		pop();
	}

	private void toast(String msg) {
		main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
	}
}
