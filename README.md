# 1AI

A chat client for Anthropic, OpenAI and Google Gemini that runs on Android 1.6 and is
driven entirely from a physical keyboard — built for a BlackBerry.

```
minSdkVersion 4 (Android 1.6 Donut)   targetSdkVersion 28   compileSdk 34
APK: ~560 KB                          Dependencies at runtime: none
```

---

## Quick start

```bash
./build.sh fetch-deps     # one-off: fetch and checksum-verify R8, Bouncy Castle
./build.sh                # -> build/1ai.apk
adb install build/1ai.apk
```

Add an API key in **Settings** (the `Settings` button in the title bar, or press
`Menu`), pick a provider, and start typing. `N` starts a new conversation.

### Requirements

* JDK 11+ (built and tested with 21) and an Android SDK with `build-tools` and one
  `platforms/android-*`. Set `ANDROID_HOME` or install to `~/android-sdk`.
* No Gradle, no AndroidX, no dependency resolution. `./build.sh` drives
  `aapt2` → `javac` → `R8` → `zipalign` → `apksigner` directly.

---

## Why it is built this way

Two constraints shaped everything, and both are worth knowing before changing anything.

**The vendor SDKs cannot be used.** `anthropic-java` and its peers pull in OkHttp and
Jackson, which require Android 5.0+. Using them would make the entire premise of this
app — running on Android 1.6 — impossible. All three providers are therefore spoken to
as raw HTTPS + SSE. That is also the only approach that behaves identically on Android
1.6 and Android 14.

**Gradle and AndroidX impose minSdk floors** (14, 19, 21+ depending on the artifact).
Removing them is what buys `minSdkVersion 4`. Nothing in this repo resolves a dependency
at runtime; the app links only against the framework.

### The floor is API 4 for a specific reason

`android.os.Build.VERSION.SDK_INT` — which any backwards-compatible branching depends on
— was itself only added in API 4. Going lower would mean comparing
`Build.VERSION.SDK` strings, which buys nothing.

---

## Keyboard reference

Everything is reachable without touching the screen.

| Key | Where | Action |
|---|---|---|
| `↑` `↓` | anywhere | Move focus / selection |
| `Enter` | chat | Send |
| `Shift+Enter`, `Alt+Enter` | chat | Insert a newline |
| `Enter` | lists | Open the focused item |
| `Esc` | anywhere | Back |
| `Menu` | chats | Actions for the selected conversation |
| `Menu` | chat | Model, system prompt, retry, copy, clear |
| `N` | chats | New conversation |
| `R` | chats | Rename selected |
| `Del` | chats | Delete selected |
| `Ctrl+R` | chat | Retry the last turn |
| `Ctrl+K` | chat | Change model |

Focus is always visible: the focused row is drawn in the era's own highlight colour, and
text colour flips with it so the contrast holds. Focus is explicitly restored after
sending and after dialogs close, so the keyboard never goes dead mid-session.

`Esc` maps to Back because that is what a BlackBerry keyboard actually sends.

---

## The UI changes with the Android version

The app looks like it belongs on the device it is running on, rather than like a modern
app awkwardly ported down. One layout structure, three skins, selected automatically:

| Era | Android | Look |
|---|---|---|
| 1 | 1.6 – 2.3 | Black background, grey gradient chrome, **solid amber focus highlight**, Gingerbread green accents |
| 2 | 3.0 – 4.4W | Holo: near-black surfaces, `#33b5e5` blue accents |
| 3 | 5.0+ | Material: light surfaces, teal accent |

This works because every drawable references colours *by name* and the palettes are
overridden per era in `values/`, `values-v11/` and `values-v21/`. Adding an era means
adding one colours file, not touching layouts.

Two details that are easy to get wrong and are handled deliberately:

* **Text colour flips with focus.** Era 1's focus colour is a light amber block needing
  dark text; Holo's is a saturated blue needing light text. Hard-coding either one
  produces unreadable text in the other era.
* **Modern theme parents cannot appear in unqualified resources.** Style parents resolve
  at *runtime*, so a `Theme.Holo` parent in plain `values/` crashes on API 4 with
  `Resources$NotFoundException`. The base theme is API-1-safe and only colours vary.

---

## Compatibility — what actually works

Verified by running the app, not by inspection.

| Capability | Android 1.6 – 2.3 (API 4–10) | 3.0 – 4.0 (11–15) | 4.1 – 4.4W (16–20) | 5.0+ (21+) |
|---|---|---|---|---|
| Install and launch | ✅ tested on API 10 | ✅ | ✅ | ✅ tested on API 21 |
| UI, storage, conversations | ✅ | ✅ | ✅ | ✅ |
| Keyboard navigation | ✅ tested on API 10 | ✅ | ✅ | ✅ |
| **Live API calls** | ❌ | ❌ | ✅ | ✅ tested |

### The networking floor is Android 4.1, and it is a hard one

Anthropic, OpenAI and Google all require **TLS 1.2**. Android only gained TLS 1.2 in
API 16, and only enables it by default in API 20. Below API 16 there is no TLS 1.2 on the
device at all, so those hosts cannot be reached however the app is written.

This was addressed head-on rather than accepted: the app bundles **Bouncy Castle's
pure-Java TLS 1.2** (`bctls`) and a current **Mozilla CA bundle** (121 roots, including
ISRG Root X1 and GTS Root R1, which old devices predate). `BcTlsTransport` implements the
handshake, SNI, chain validation and RFC 6125 hostname checking, then does its own
HTTP/1.1 — request writing, response parsing and chunked transfer decoding — because
there is no `HttpsURLConnection` equivalent underneath it.

**It works, and it does not solve the problem.** Measured, not assumed:

* On **Android 5.0 (ART)** it completes a real handshake with `api.anthropic.com` and
  returns HTTP 401 for a deliberately invalid key — the whole stack, verified end to end.
* On **Android 2.3 (Dalvik)** it fails with `VerifyError: org.bouncycastle.tls.TlsUtils`.
  This is not a shrinking artefact: plain `d8` with **no R8 at all** produces the same
  failure, so it is the dexer's output failing Dalvik's bytecode verifier. There is no
  app-level fix.

So the bundled TLS only runs on ART, where the platform stack is already fine. The honest
summary: **Android 4.1+ for live API calls, and the bundled TLS is insurance rather than
a bridge.** The Settings screen says this plainly on affected devices instead of failing
obscurely, and **Test connection** in Settings diagnoses DNS/TLS/HTTP without spending a
request against your quota.

That floor is not a problem for the intended hardware: BlackBerry 10 devices run an
Android 4.3 runtime (API 18) and KEYone/KEY2 run 7.1/8.1.

### API keys are stored unencrypted

They live in app-private `SharedPreferences`. This is stated rather than papered over:
the Android Keystore does not exist below API 18, and the devices this targets are below
it. If that is unacceptable for a key, use a scoped or disposable one.

---

## Architecture

```
app/src/com/oneai/
├── net/            Conn, Transport, SseReader          (pure Java)
│   └── android/    PlatformTransport, BcTlsTransport, ChunkedInputStream
├── provider/       Anthropic, OpenAI, Google, ChatClient (pure Java)
├── chat/           StreamRunner, ConnectionProbe
├── data/           Db (SQLite), Prefs, Store
├── ui/             three activities, two adapters, ModelPicker
└── util/           Markdown, Hostname, Pem
```

`Transport` is the seam that matters. Providers see only `Conn` — a status, headers and a
byte stream — so the entire provider layer is free of Android types and runs in plain JVM
tests. Swapping the platform TLS stack for the bundled one is a one-line change at
runtime, and mocking the network in tests is the same operation.

### Wire formats, verified against live documentation

Details that a client written from memory gets wrong, all handled:

| | Anthropic | OpenAI | Google |
|---|---|---|---|
| Endpoint | `/v1/messages` | `/v1/chat/completions` | `/v1beta/models/{m}:streamGenerateContent?alt=sse` |
| Auth | `x-api-key` | `Authorization: Bearer` | `x-goog-api-key` |
| Assistant role | `assistant` | `assistant` | **`model`** |
| Token cap | `max_tokens` | **`max_completion_tokens`** | `maxOutputTokens` |
| Stream ends | `message_stop` | `data: [DONE]` | **connection close** |

* **Anthropic rejects `temperature`/`top_p` with a 400 on every current model** (Opus 5.5,
  Sonnet 5.5, Haiku 5.5, Opus 4.7/4.8, Fable 5). Sampling params are omitted by default
  and hidden behind an advanced toggle for older models only. Offering the slider would
  have meant offering a button that always fails.
* **OpenAI hard-rejects `max_tokens`** on GPT-5-and-later; `max_completion_tokens` is sent
  instead. Its final usage chunk carries an **empty `choices` array**, which crashes any
  client that indexes `choices[0]` unguarded — at the end of every successful response.
* **Google returns a JSON array, not SSE, unless `alt=sse` is set**, and emits **no
  terminator** — clean EOF is the end of the stream. Chunks may contain parts with no text.
* OpenAI interleaves **SSE comment lines** and pads deltas with junk unless
  `include_obfuscation` is set false; the parser skips comments and ignores unknown fields.

Models default to `claude-opus-5-5`, `gpt-6-astra` and `gemini-3.8-flash`, with the rest
of each current family selectable. Any model id can be typed in, so a model released after
this build still works.

### Streaming performance

Re-parsing Markdown on every token would stutter badly on a 2010 CPU. Deltas update a
plain-text view coalesced to ~10 updates/second; the Markdown pass runs once, when the
stream finishes. The message list auto-scrolls only when you are already at the bottom,
so scrolling up to re-read is not yanked away.

---

## Testing

```bash
./test/run.sh     # 40 tests, no framework required
```

Covers the request JSON each provider emits, SSE parsing (frames split across reads,
multi-byte UTF-8 split mid-character, CRLF, `[DONE]` vs EOF, thinking deltas), error
mapping for each provider's real error envelope, and hostname verification including the
rejection cases — wildcards that would match a bare domain, cross label boundaries, or
cover a public suffix.

The build also runs two checks that the compiler cannot do for you:

* **`tools/apicheck.py`** rejects source that compiles against android-34 but does not
  exist on API 4 — `onBackPressed`, `Editor.apply()`, `setBackground`,
  `try`-with-resources, `java.util.function`, and a dozen more. The compiler is happy to
  accept every one of those and they fail at runtime on the target hardware.
* **A DEX scan** after shrinking for references to `java.util.function`,
  `java.util.stream`, `java/nio/file`, `java/time` and `AutoCloseable`. The current
  output contains **none**, which is what makes the bundled TLS safe to ship: the
  `jdk15to18` Bouncy Castle build targets Java 5 bytecode, so it needs no desugaring.

`adb`-driven checks were run on real emulators: API 10 (Gingerbread), API 15, API 18 and
API 21 (Lollipop).

### Two build-tool bugs worth knowing about

* The R8 bundled in build-tools 34 is an `8.2.2-dev` build that crashes with an internal
  `NullPointerException` on any class carrying an `InnerClasses` attribute — that is,
  on virtually all real Java. `build.sh` pins a stable R8 from Google's Maven instead.
* Android 2.x requires `layout_width`/`layout_height` on an `<include>` tag and on every
  view whose style does not supply them. Omitting them throws
  `"You must supply a layout_width attribute"` at inflate time. Layout params therefore
  live on the elements, never only in styles.

---

## Not included

Image and vision input, tool use, and attachments. Each adds a lot of surface area for
little value on a keyboard-first device this old, and the `Transport`/`Provider` seams
leave room for them.

## Licence

None chosen yet — add a `LICENSE` file before relying on this being reusable.
