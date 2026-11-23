# Bittu

A personal voice assistant for Android that lives on your own phone. You say "Bittu" and it does things on the
phone, answers questions, remembers what you tell it, and can pick up phone calls for you and talk to the caller.

Everything runs on the phone itself. There is no Bittu server, no account and no call forwarding. The only things
that leave the phone are the requests to the AI services whose keys you add yourself.

> **Status: a personal project, built and tested on one phone** (Xiaomi 11 Lite NE, Android 14, HyperOS 2.0).
> It is not on the Play Store and could not be: it relies on developer features that Play does not allow.
> Expect to read logs. Read [What does not work](#what-does-not-work) before you install it.

## What it does

**Talking to Bittu**
- Wake word: say "Bittu" at any time, also from the lock screen. Follow-up sentences within 12 seconds need no name.
- Plain commands run at once without any AI call: open an app, torch, Wi-Fi, Bluetooth, volume, brightness, home,
  back, scroll, modes. Most take under 0.2 seconds.
- Everything else goes to Claude (your Anthropic key) with tools: call a person, send a message, read and tap what
  is on the screen in any app, read and reply to notifications, look things up on the internet, find where the
  phone is, read the phone's own details, run any shell command.
- Speaks with the phone's own voice, ElevenLabs or Fish Audio.

**Answering calls**
- In a mode such as Driving, Bittu rings you first, then picks up and talks to the caller with Gemini Live.
- It knows the caller from your contacts, greets saved contacts in Telugu and others in English, then follows the
  caller's language.
- It takes a message, can buzz your phone so you take the call over, and leaves a summary notification afterwards.
- If Gemini Live cannot be reached it falls back to a three-step chain: hear (ElevenLabs, Fish Audio or AssemblyAI),
  think (Claude or Gemini text), speak (ElevenLabs or Fish Audio).

**Modes**
- Driving, Meeting, Sleep, Strangers, Off, and modes you make yourself with your own sentence for callers.
- A mode can run for a set time ("for 1 hour", "until 7 AM") and ends by itself.

**Who hears what**
- Each person has a sharing level, from "nothing" to "everything".
- Family, listed by exact phone number, may ask where you are, what you are doing next and what is in your
  calendar. Bittu only answers what they ask and never volunteers it.
- What a caller is not allowed to hear is left out of the AI's instructions entirely, so it cannot be talked out of it.

**Memory**
- Starts empty. Bittu saves what you tell it about yourself, your people and your routines, and keeps a diary line
  per conversation.
- No limit: recent notes are folded into a short profile automatically, and the originals are kept for look-up.
- Stored only in the app's private storage. A Memory page shows it and can erase one item or all of it.

**Model pickers**
- Model and voice lists are fetched from each service with your own key and refreshed daily.

## How it is built

| Part | What it is |
|---|---|
| `ui/` | The interface: plain HTML, CSS and JavaScript modules, no framework and no build step. Runs in a WebView. |
| `android/` | The app: Kotlin, no UI toolkit. Hosts the WebView and holds every background part. |
| `android/.../shell/` | The **helper**: a small program that runs as Android's `shell` user, started through the phone's own Wireless debugging, the way Shizuku is. |
| `probe/` | Test tools: a regression check that drives the real phone, and a call-audio probe. |

**Why a helper?** An ordinary app cannot put sound into a phone call, hear the caller, flip protected switches or
answer a call silently. Android's `shell` user can. The app pairs once with the phone's Wireless debugging and from
then on starts the helper by itself, with no computer. The helper puts Bittu's voice straight onto the call line
(the telephony output) and reads the caller's side from it.

The helper lives inside Android's debugging service, so Android stops it whenever that service restarts (plugging a
cable or charger in or out does this). Bittu notices and starts it again within about 10 seconds, also when locked
and asleep.

## Every permission, and why

Bittu asks for a lot, because its job is to operate the whole phone for its owner. Nothing here is used for
advertising or analytics; there is none in the code.

| Permission | Why Bittu needs it |
|---|---|
| Microphone (`RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`) | To hear "Bittu" and your request. Listening uses Android's on-device recognizer; the sound of the wake-word loop is not sent anywhere. |
| Contacts (`READ_CONTACTS`) | To call or message a person by name, and to know who is calling. |
| Phone (`READ_PHONE_STATE`, `CALL_PHONE`) | To notice a ringing call and to place calls you ask for. |
| SMS (`SEND_SMS`) | To send the text you dictate, and the "I'm in a meeting" text when a mode declines a call. |
| Location, including background (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`) | For "where am I", weather, and to tell family where you are **when they ask and you allowed it**. Background access is needed because a call can arrive while the app is closed. |
| Calendar (`READ_CALENDAR`) | To tell callers with full access what you have planned today and tomorrow, when they ask. Read-only. |
| Notifications (`POST_NOTIFICATIONS`) | The "listening" notice Android requires, call summaries, and the take-over alert. |
| Notification access (a setting you switch on) | To read your notifications aloud and reply to messages from them. |
| Accessibility (a setting you switch on) | "Screen control": to read, tap, type and scroll inside other apps when you ask ("send this to Akka"). **See the warning below.** |
| Internet, network state (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`) | To reach the AI services, and to find the phone's own Wireless debugging on the local network for pairing. |
| Run at start-up, stay awake, exact alarms (`RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `SCHEDULE_EXACT_ALARM`, `FOREGROUND_SERVICE`) | To keep listening in the background, restart the helper while the phone sleeps, and end a timed mode on the minute. |
| Vibrate (`VIBRATE`) | Bittu buzzes instead of beeping when it has heard its name. |
| Secure settings (`WRITE_SECURE_SETTINGS`) | Granted by the helper, not by a prompt. Lets the app switch Wireless debugging on for a few seconds to restart the helper, then off again. |

The helper, as the `shell` user, additionally: captures and injects call audio, answers and ends calls, changes
system settings (brightness, Do Not Disturb, developer options), grants the app the permissions above, and runs
shell commands the assistant asks for.

## Read this before installing

- **Payment apps may block you.** Google Pay refuses to pay while any downloaded app has Accessibility switched
  on, and Bittu's screen control is one. To pay you must switch it off and restart the phone. Other banking and UPI
  apps may do the same. This is the payment app protecting you; Bittu does not try to hide from it.
- **Developer options and USB debugging stay on.** That is how the helper is started.
- **"Restart without Wi-Fi" keeps debugging port 5555 open.** It is off by default. With it on, the helper can
  come back on mobile data. Only devices you have approved can connect, but never tap Allow on a debugging prompt
  you did not expect.
- **The assistant can do anything on the phone.** It is told never to type passwords or PINs and never to share
  codes, but those are instructions to an AI model, not hard locks. Anyone who can speak to your unlocked phone
  can give Bittu commands; it does not recognise your voice.
- **Your keys are yours.** Keys are kept in the app's private storage and in a file only the `shell` user can
  read. They are sent only to the service they belong to.
- **Recording.** For fault-finding, the helper keeps the first 45 seconds of the caller's side of the most recent
  call in `/data/local/tmp/bittu/last_call_in.pcm`, replaced on every call. Check what the law where you live
  says about recording calls, and remove that line in `Daemon.kt` if you do not want it.

## Services and keys

| Used for | Service | Needed? |
|---|---|---|
| Thinking when you talk to Bittu | Anthropic (Claude) | Yes |
| Talking on calls | Google Gemini Live | Yes, or the backup chain below |
| Backup on calls, optional voice | ElevenLabs, Fish Audio, AssemblyAI | Optional |

You pay each service directly. There is no spending limit in the app.

## Build and install

You need: a recent JDK (it is built with JDK 25), the Android SDK (compile SDK 37), Gradle 9.8, a phone on Android 12 or newer with
Developer options, and Node.js for the test tools. There is no Gradle wrapper in the repository.

```
cd android
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Create `android/local.properties` with `sdk.dir=` pointing at your Android SDK first.

Then on the phone:
1. Open Bittu and go through **Phone access**: allow the permissions, switch on notification access and screen control.
2. Pair once: connect to Wi-Fi, tap **Start pairing**, open Wireless debugging, choose "Pair device with pairing
   code", and type the six digits into Bittu's notification.
3. Add your keys under **Brain and keys**.
4. On Xiaomi phones: allow autostart and set battery saver to "No restrictions" for Bittu. The helper sets these
   too, but check them.

`probe/check.sh` runs 28 checks against a connected phone and puts every setting back.

## What does not work

- **Only tested on one phone.** Putting voice on the call line depends on the phone listing a telephony output
  and letting the `shell` user use it. Many phones will differ.
- **WhatsApp and other internet calls** are not answered. Only normal SIM calls.
- **Bluetooth and car kits** during a Bittu call are untested.
- **A second call** while Bittu is on one just rings.
- **After a restart** the helper needs Wi-Fi once before it can start.
- **The wake word** uses Android's speech recognizer in a loop. It can mishear, it reacts to anyone saying
  "Bittu", and it costs battery.
- **Tasks inside other apps** depend on the AI reading and tapping the screen, and can go wrong.
- **Language handling** on calls is an instruction to the AI and sometimes slips.

## Credits

- Fonts: Inter and Bricolage Grotesque, both under the SIL Open Font License.
- [libadb-android](https://github.com/MuntashirAkon/libadb-android) by Muntashir Al-Islam, for talking to the
  phone's own Wireless debugging. The self-start idea follows [Shizuku](https://github.com/RikkaApps/Shizuku).
- Built with Claude Code.

## License

MIT. See [LICENSE](LICENSE).
