# MiniSpot

<p align="center">
  <img width="200" alt="home" src="https://github.com/user-attachments/assets/4501633d-a4b3-4a1e-be75-b5db6fb3d036" />
  <img width="200" alt="search" src="https://github.com/user-attachments/assets/b8bf5f38-c865-4ba7-9dbd-01dec06303de" />
  <img width="200" alt="playlists" src="https://github.com/user-attachments/assets/ff7ef22e-ccc4-49f5-8ce4-987870f129bd" />
</p>
<p align="center">
  <img width="200" alt="now playing" src="https://github.com/user-attachments/assets/e5cc4af4-aa52-415a-844f-be428ea08276" />
  <img width="200" alt="menu" src="https://github.com/user-attachments/assets/791ae8f9-5340-4211-ae1a-779aa99fbbb9" />
  <img width="200" alt="queue" src="https://github.com/user-attachments/assets/48b43099-6cb6-4173-b908-6020ce0d235c" />
</p>


A Minimalistic Optimized version of Spotify for Dumbphones, YOU NEED SPOTIFY PREMIUM.

You can use it entirely with the D-pad and number keys (no more annoying cursor), while the official Spotify app plays the music in the background. It was built for the Kyocera DIGNO Keitai 3 (902KC, Android 8.1), and works on any keypad phone running Android 7 or newer. YOU NEED SPOTIFY PREMIUM

*[Version française plus bas.](#français)*

> Unofficial project. Not affiliated with, endorsed or sponsored by Spotify AB.

## Features

- Home screen with **Now playing**, your **4 most recent** playlists / albums / artists / podcasts, and your library: Search, Playlists, Liked songs, Albums, Artists, Podcasts, History
- **Search** across Spotify, results shown in MiniSpot
- **Playlists** with your pinned items at the top (pins are kept in MiniSpot)
- **Menu** key: pin / unpin, add to / remove from library, follow, add to queue, go to artist / album / podcast, playback speed
- **Queue** key: see what plays next and skip to it
- Now playing screen with progress, shuffle and repeat
- Home tabs can be **reordered and hidden** (Settings → Home tabs)
- **10 languages**: English, Français, Deutsch, Español, Italiano, Português, Русский, 日本語, 中文, 한국어 (Paramètres / Settings → Language). Follows the phone's language by default. Translation fixes welcome: `app/src/main/res/values-*/strings.xml`
- About 140 KB, black theme, no images: light on old phones with little RAM
- On Kyocera phones, the grey soft key bar shows **Menu | OK | File**

Without logging in, MiniSpot still works as a basic remote (play / pause, next, seek, like, radio) for the Spotify app or any music app.

## What you need

- A **Spotify Premium** account. Spotify only lets Premium accounts own a developer app, and MiniSpot uses *your own* developer app.
- The official **Spotify** app installed on the phone and logged in, download the apk if playstore not available. It does the actual playback.
- A computer (Windows, macOS or Linux) with **Python 3** and **adb** ([Tutorial here](https://www.youtube.com/watch?v=pCBWWRMbzto)).
- USB debugging enabled on the phone (Settings → About phone → tap *Build number* 7 times, then Developer options → USB debugging).

## Setup

### 1. Install MiniSpot

From the [Releases](../../releases) page, download these files and put login.py and login.bat in the same folder:

- `MiniSpot-x.y.z.apk`: the app
- `login-pc.py`: the login script (step 3)
- `login-pc.bat`: shortcut to run the script on Windows

Move `MiniSpot-x.y.z.apk` to the Minimal ADB and Fastboot folder and run the cmd-here.exe, plug the phone in and run:

```
adb install MiniSpot-x.y.z.apk
```

### 2. Create your own Spotify app (once)

1. Go to <https://developer.spotify.com/dashboard> and log in with your Premium account.
2. **Create app**. Name and description: anything (for example "MiniSpot").
3. **Redirect URIs**: add both:
   ```
   http://127.0.0.1:8888/callback
   minispot://callback
   ```
4. **Which API/SDKs are you planning to use?** Tick **Web API**. Save.
5. Copy the **Client ID**.

Spotify apps start in *Development mode*: up to 5 users, which you add under **User Management** (your own account is already allowed). Each person can also create their own app.

### 3. Log in (once)

With the phone plugged in, run on the computer, in the folder where you saved `login-pc.py`:

```
python login-pc.py
```

(on Windows you can double-click `login-pc.bat`). Paste your Client ID when asked. Your browser opens Spotify's login page: log in and accept. The script then:

- sends the session and your Client ID to MiniSpot over USB,
- gives MiniSpot notification access, so it can see what Spotify is playing.

That's it: MiniSpot opens on the phone, logged in. The session renews itself; you only run the script again after logging out.

> Why a computer? Old phone browsers (like Android 8.1's) cannot load Spotify's login page. On a phone with a recent browser you can instead set the Client ID in MiniSpot (Settings → Spotify Client ID) and use Settings → Log in to Spotify.

## Keys

| Key | Action |
|---|---|
| Up / Down | Move |
| OK (centre) | Open / play. In *Now playing*: play / pause |
| Left or Back | Back |
| 5 | Play / pause (everywhere) |
| 1 / 3 | Previous / next track |
| 4 / 6 | Back / forward 15 s |
| 2 / 8 | Volume up / down |
| 7 | Shuffle on / off |
| 9 | Repeat (off / all / one) |
| 0 or Call key | *Now playing* screen |
| ✱ | Like / unlike the current track |
| # | Add the selected track to the queue (elsewhere: search) |
| Left soft key | Menu (options of the selected item) |
| Right soft key | Queue |

## Limits

- Spotify's pins and the home feed are not available to other apps, so MiniSpot keeps its own pins.
- In Development mode, Spotify only lists the tracks of playlists you own or collaborate on. The others still play with *Play all* / *Shuffle*.
- Spotify's listening history only includes music; the podcast playing now is added to *Recent*.
- The soft key bar labels only exist on Kyocera phones.

## Privacy and security

- No server: MiniSpot talks only to Spotify (`api.spotify.com`, `accounts.spotify.com`).
- No secret in the app or the repository. Login uses OAuth with PKCE, made for apps without a client secret. Your Client ID stays on your phone and in `client_id.txt` next to the script (ignored by git).
- The session is stored in MiniSpot's private storage; Android backup is disabled.
- The session can only be sent to the phone through adb (the receiver requires a permission only the adb shell has), and the in-app login checks an OAuth `state`.

## Build from source

Requirements: JDK 17+, Android SDK (platform 37). Then:

```
gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`. Without `keystore.properties` it is signed with the debug key. To sign releases with your own key, create a keystore and a `keystore.properties` file, either at the project root (ignored by git) or anywhere else with the environment variable `MINISPOT_KEYSTORE_PROPERTIES` pointing to it:

```
storeFile=C:/path/to/minispot-release.jks
storePassword=...
keyAlias=minispot
keyPassword=...
```

## License

MIT, see [LICENSE](LICENSE). Icons: Material Design Icons (Apache 2.0), see [NOTICE](NOTICE).

---

## Français

MiniSpot est une version minimisée et optimisée de Spotify pour dumbphone or old phones. Tout se fait au clavier ; l'app Spotify officielle joue la musique en arrière-plan.

**Il faut :** un compte **Spotify Premium**, l'app Spotify officielle sur le téléphone, un ordinateur avec **Python 3** et **adb**, et le débogage USB activé.

**Installation :**

1. Dans les [Releases](../../releases), télécharge `MiniSpot-x.y.z.apk`, `login-pc.py` et `login-pc.bat` (Windows), et mets-les dans le même dossier. Installe l'APK : `adb install MiniSpot-1.1.0.apk`
2. Sur <https://developer.spotify.com/dashboard>, avec ton compte Premium : **Create app**, ajoute les Redirect URIs `http://127.0.0.1:8888/callback` et `minispot://callback`, coche **Web API**, enregistre, puis copie le **Client ID**.
3. Téléphone branché, lance `python login-pc.py` (ou double-clique `login-pc.bat` sous Windows), colle ton Client ID, connecte-toi à Spotify dans le navigateur et accepte. MiniSpot s'ouvre connecté.

Chacun utilise **sa propre** app Spotify et son propre compte : rien n'est partagé.

**Langue :** Paramètres → Langue (10 langues, ou celle du téléphone).

**Touches :** OK ouvre / lit (dans *En cours* : lecture / pause) · 5 lecture / pause · 1 / 3 titre précédent / suivant · 4 / 6 −15 s / +15 s · 2 / 8 volume · 7 aléatoire · 9 répéter · 0 écran *En cours* · ✱ j'aime · # file d'attente · touche gauche : Menu · touche droite : File d'attente.

Projet non officiel, sans lien avec Spotify AB.
