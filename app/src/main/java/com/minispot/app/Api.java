package com.minispot.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal Spotify Web API client. All calls block: run them off the main thread.
 * Each user brings their own Client ID (their own app on developer.spotify.com, owned by a Premium account);
 * it is stored on the phone, never built into the APK.
 */
class Api {
	static class ApiException extends Exception {
		final int status;

		ApiException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static final String BASE = "https://api.spotify.com/v1";
	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

	private final SharedPreferences prefs;
	/** For error messages, in the language chosen in MiniSpot. */
	private final Context context;

	Api(Context context) {
		this.context = context;
		prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
	}

	boolean isLoggedIn() {
		return prefs.getString("refresh", null) != null || prefs.getString("access", null) != null;
	}

	void saveLogin(String access, String refresh, int expiresIn) {
		SharedPreferences.Editor e = prefs.edit()
			.putString("access", access)
			.putLong("expires", System.currentTimeMillis() + expiresIn * 1000L - 60_000);
		if (refresh != null) {
			e.putString("refresh", refresh);
		}
		e.apply();
	}

	/** Clears the session but keeps the Client ID. */
	void logout() {
		String clientId = clientId();
		prefs.edit().clear().putString("client_id", clientId).apply();
	}

	String clientId() {
		return prefs.getString("client_id", "");
	}

	void setClientId(String id) {
		prefs.edit().putString("client_id", id.trim()).apply();
	}

	static boolean isValidClientId(String id) {
		return id != null && id.trim().matches("[0-9a-fA-F]{32}");
	}

	String deviceId() {
		return prefs.getString("device", null);
	}

	void setDeviceId(String id) {
		prefs.edit().putString("device", id).apply();
	}

	private synchronized String token() throws Exception {
		String access = prefs.getString("access", null);
		if (access != null && System.currentTimeMillis() < prefs.getLong("expires", 0)) {
			return access;
		}
		refresh();
		return prefs.getString("access", null);
	}

	// ---- login (authorization code + PKCE, done in the phone's browser) ----

	/** Builds the Spotify login page URL and remembers the PKCE verifier and state for {@link #exchangeCode}. */
	String loginUrl(String[] scopes) throws Exception {
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[48];
		random.nextBytes(bytes);
		String verifier = base64Url(bytes);
		byte[] stateBytes = new byte[16];
		random.nextBytes(stateBytes);
		String state = base64Url(stateBytes);
		String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		prefs.edit().putString("verifier", verifier).putString("state", state).apply();
		return "https://accounts.spotify.com/authorize"
			+ "?client_id=" + enc(clientId())
			+ "&state=" + state
			+ "&response_type=code"
			+ "&redirect_uri=" + enc(BuildConfig.REDIRECT_URI)
			+ "&code_challenge_method=S256"
			+ "&code_challenge=" + challenge
			+ "&scope=" + enc(TextUtils.join(" ", scopes));
	}

	/**
	 * Trades the code from minispot://callback for tokens. The state must match the one sent:
	 * the callback is open to any app, this stops one from logging MiniSpot into another account.
	 */
	void exchangeCode(String code, String state) throws Exception {
		String verifier = prefs.getString("verifier", null);
		String expected = prefs.getString("state", null);
		if (verifier == null || expected == null || !expected.equals(state)) {
			throw new ApiException(400, context.getString(R.string.err_login_expired));
		}
		tokenRequest("grant_type=authorization_code"
			+ "&code=" + enc(code)
			+ "&redirect_uri=" + enc(BuildConfig.REDIRECT_URI)
			+ "&client_id=" + enc(clientId())
			+ "&code_verifier=" + enc(verifier));
		prefs.edit().remove("verifier").remove("state").apply();
	}

	private static String base64Url(byte[] bytes) {
		return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
	}

	private void refresh() throws Exception {
		String refresh = prefs.getString("refresh", null);
		if (refresh == null) {
			throw new ApiException(401, context.getString(R.string.err_session_expired));
		}
		tokenRequest("grant_type=refresh_token"
			+ "&refresh_token=" + enc(refresh)
			+ "&client_id=" + enc(clientId()));
	}

	private void tokenRequest(String body) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
		c.setConnectTimeout(15_000);
		c.setReadTimeout(15_000);
		c.setRequestMethod("POST");
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		try (OutputStream os = c.getOutputStream()) {
			os.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int status = c.getResponseCode();
		String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
		c.disconnect();
		if ((status == 400 || status == 401) && body.startsWith("grant_type=refresh_token")) {
			logout();
			throw new ApiException(401, context.getString(R.string.err_session_expired));
		}
		if (status >= 400) {
			throw new ApiException(status, context.getString(R.string.err_http_login, status));
		}
		JSONObject json = new JSONObject(text);
		saveLogin(json.getString("access_token"), json.optString("refresh_token", null), json.optInt("expires_in", 3600));
	}

	JSONObject get(String path) throws Exception {
		return request("GET", path, null);
	}

	JSONObject put(String path, String jsonBody) throws Exception {
		return request("PUT", path, jsonBody != null ? jsonBody : "");
	}

	JSONObject post(String path) throws Exception {
		return request("POST", path, "");
	}

	JSONObject delete(String path) throws Exception {
		return request("DELETE", path, null);
	}

	/** GET returning a bare JSON array, like /me/library/contains. */
	JSONArray getArray(String path) throws Exception {
		return new JSONArray(raw("GET", path, null));
	}

	private JSONObject request(String method, String path, String jsonBody) throws Exception {
		String text = raw(method, path, jsonBody);
		text = text.trim();
		return text.startsWith("{") ? new JSONObject(text) : new JSONObject();
	}

	private String raw(String method, String path, String jsonBody) throws Exception {
		for (int attempt = 0; ; attempt++) {
			String url = path.startsWith("http") ? path : BASE + path;
			HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(15_000);
			c.setReadTimeout(20_000);
			c.setRequestMethod(method);
			c.setRequestProperty("Authorization", "Bearer " + token());
			if (jsonBody != null) {
				byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				c.setFixedLengthStreamingMode(bytes.length);
				try (OutputStream os = c.getOutputStream()) {
					os.write(bytes);
				}
			}
			int status = c.getResponseCode();
			String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
			c.disconnect();

			if (status == 401 && attempt == 0) {
				prefs.edit().remove("access").apply();
				continue;
			}
			if (status == 429 && attempt < 2) {
				Thread.sleep(2000);
				continue;
			}
			if (status >= 400) {
				String msg = context.getString(R.string.err_http, status);
				try {
					msg = context.getString(R.string.err_detail, msg, new JSONObject(text).getJSONObject("error").getString("message"));
				} catch (Exception ignored) {
				}
				throw new ApiException(status, msg);
			}
			return text;
		}
	}

	private static String read(InputStream in) throws Exception {
		if (in == null) {
			return "";
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
		}
		in.close();
		return out.toString("UTF-8");
	}

	static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (Exception e) {
			return s;
		}
	}
}
