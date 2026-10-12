# 1AI

A chat client for Anthropic, OpenAI, Google Gemini and about a dozen other providers,
with image and PDF attachments. Runs on Android 1.6 and is driven entirely from a
physical keyboard — built for a BlackBerry.

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

Add an API key — ideally by dropping a file on the device, see below — pick a provider, and
start typing. `N` starts a new conversation.

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
| — | chat | `+` button or `Menu` attaches an image or PDF |

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

## API keys from a file

Typing a sixty-character key on a phone keyboard is miserable and getting it wrong is
silent until a request fails. Put a file called **`1ai-keys.txt`** anywhere the app can
find it and the keys load themselves:

```
# 1ai-keys.txt
DEEPSEEK=[sk-...]
GOOGLE=[AIza...]
CLAUDE/ANTHROPIC=[sk-ant-...]
OPENAI=[sk-...]
OPENROUTER=[sk-or-...]

MODEL_GOOGLE=gemini-3.8-flash
BASE_URL_DEEPSEEK=https://api.deepseek.com/v1
```

The app looks in these places, in order, and imports the first one it finds:

```
<app external files dir>/1ai-keys.txt     no permission needed on any version
/storage/sdcard/1ai-keys.txt
/storage/sdcard/Download/1ai-keys.txt
/storage/sdcard/Documents/1ai-keys.txt
/storage/sdcard/1ai/1ai-keys.txt
```

It is picked up on launch whenever the file has changed since the last import, so
replacing the file is all it takes. **Settings → Import keys from a file** does it on
demand and shows where it looked.

Names are matched loosely, so `Claude`, `CLAUDE/ANTHROPIC`, `claude_anthropic` and
`anthropic` all mean the same thing, as do `GOOGLE`/`GEMINI` and `OPENAI`/`GPT`. Any
provider id works, including every compatible endpoint. Surrounding `[brackets]` or
`"quotes"` are stripped, because documentation writes placeholders that way and people
paste them literally — and a key of `[sk-abc...]` produces a 401 that looks like a wrong
key rather than a formatting mistake.

`MODEL_` and `BASE_URL_` prefixes carry settings in the same file, so one file can be a
whole configuration.

Anything unrecognised is reported rather than silently dropped, because a typo in a name
is otherwise invisible until a request fails:

```
Imported 4 API keys, 1 endpoint, 1 model from /storage/sdcard/1ai-keys.txt.
Providers: DeepSeek, Google, Anthropic, OpenRouter
Not recognised: TYPO_NAME
```

A file picker is offered as a fallback where the app finds no file, but it is not relied
on: plenty of Android builds — including the BlackBerry 10 runtime this was written for —
ship without a document provider at all, and a feature that needs one is no feature
there.

## Attachments

Images and PDFs, picked through the system file picker — no permissions, and no
storage access beyond the one file chosen.

The interesting part is what happens before an image is sent. Every image is decoded
with bounds inspection first, subsampled in powers of two, scaled exactly, and
re-encoded, so a 12-megapixel photo becomes something a 2010 phone can hold in memory.
`AttachmentStore.MAX_DIMENSION` is **1568px**, and that single number is doing real
work: it is Anthropic's long-edge limit for its standard resolution tier, it sits under
the ~1600px that OpenAI's patch budget allows its largest models, and it is far below
Google's 3072px. It also keeps requests under Anthropic's stricter per-image limit,
which kicks in for *every* image once a request carries more than twenty.

Shrinking on the device is not only about memory. All three providers downscale
oversized images server-side anyway, and image cost is billed per 28×28 or 32×32 patch,
so sending more pixels than the model will use wastes bandwidth and money for no gain
in quality.

Re-encoding also sidesteps a real incompatibility rather than working around it:
**Gemini does not accept GIF at all**, and Anthropic and OpenAI accept it only as a
still first frame. Everything is normalised to JPEG, or PNG when the source had
transparency, so no provider ever has to care what the user picked.

Each API wants a different shape, and the differences are not guessable:

| | Anthropic | OpenAI | Google |
|---|---|---|---|
| Image | `{"type":"image","source":{"type":"base64",...}}` | `{"type":"image_url","image_url":{"url":"data:..."}}` | `{"inlineData":{"mimeType":...}}` |
| PDF | `{"type":"document","source":{...}}` | `{"type":"file","file":{"file_data":"data:..."}}` | `{"inlineData":{"mimeType":"application/pdf"}}` |
| Text first? | yes | yes | yes |

Two traps worth naming. OpenAI's `image_url` is an **object** on chat completions but a
bare string on `/v1/responses` — mixing them up fails quietly. And Gemini takes
`inlineData`/`mimeType` in camelCase, while Google's own curl samples for the same
endpoint use snake_case; the published JSON schema says camelCase, so that is what is
sent.

Attachments are only ever sent on **user** turns. Anthropic rejects image blocks in
assistant turns outright, so replaying a model's own images back to it is an error
rather than merely pointless.

Because all three APIs are stateless, history is re-sent in full on every turn,
images included. Only the newest eight messages carry their images; older ones keep
their text and lose their pictures. On a device with a heap measured in tens of
megabytes, sending less beats being killed mid-request.

Files live in app-private storage and only their metadata goes in SQLite — base64 blobs
in the database would inflate it by a third and make every conversation query read
them. Deleting a conversation, a message, or clearing a chat removes the files too, and
attachments picked but never sent are cleaned up when the screen is left.

Tapping a thumbnail opens it full-size in a viewer of our own. Handing the file to
another app would mean passing a `file://` URI out of the process, which throws
`FileUriExposedException` from API 24 onward and would need a ContentProvider to fix —
a lot of machinery to display a picture we already own.

## More providers

Beyond Anthropic, OpenAI and Google, `CompatibleEndpoints` ships presets for services
that speak the OpenAI chat-completions format:

> OpenRouter · Groq · DeepSeek · xAI (Grok) · Mistral · Together · Fireworks · Cerebras ·
> SambaNova · Hyperbolic · Meta Llama · Ollama · LM Studio · vLLM

Each is a full provider with its own API key, model and **editable endpoint**, so
anything not on the list can be added by typing a base URL. Every base URL here was
read off the vendor's own documentation rather than recalled, because a wrong one
produces a confusing failure rather than an obvious one — and they are genuinely
inconsistent: most include `/v1` in the base URL, DeepSeek's does not, and Meta's
compatible surface lives under `/compat/v1`.

Two popular services are deliberately absent. **Perplexity**'s documented base is now
`/router/v1` and its chat-completions support ended in September 2026. **Azure OpenAI**
cannot be expressed as a base URL at all — it is deployment-based with a mandatory
`api-version` query parameter and authenticates with an `api-key` header rather than a
bearer token, so it needs a provider of its own rather than a preset.

Adding a service is one entry in one array; the parser is shared.

## Compatibility — what actually works

Verified by running the app, not by inspection.

| Capability | Android 1.6 – 2.3 (API 4–10) | 3.0 – 4.0 (11–15) | 4.1 – 4.4W (16–20) | 5.0+ (21+) |
|---|---|---|---|---|
| Install and launch | ✅ tested on API 10 | ✅ | ✅ | ✅ tested on API 21 |
| UI, storage, conversations | ✅ | ✅ | ✅ | ✅ |
| Keyboard navigation | ✅ tested on API 10 | ✅ | ✅ | ✅ |
| **Live API calls** | ❌ | ❌ | ✅ | ✅ tested |

### TLS: how the floor was lowered, and where it still is

Anthropic, OpenAI and Google all require **TLS 1.2**. Android only gained TLS 1.2 in API
16, and only enables it by default in API 20 — so on Android 4.1–4.4 the capability is
present but switched off, and below 4.1 it does not exist at all. Servers are moving the
other way too: **DeepSeek now refuses TLS 1.0 and 1.1 outright**, so a phone offering
only those cannot reach it however correct the app is.

Two stacks are therefore shipped and chained:

1. **The platform stack**, preferred wherever it works — it is the device's own tested
   implementation and costs nothing in APK size. On API 16–19 every protocol the runtime
   supports is explicitly enabled, which is what switches TLS 1.2 on there.
2. **Bouncy Castle's pure-Java TLS 1.2** (`bctls`) behind it, plus a current **Mozilla CA
   bundle** (121 roots including ISRG Root X1 and GTS Root R1, which old devices predate).
   `BcTlsTransport` does the handshake, SNI, chain validation and RFC 6125 hostname
   checking, then its own HTTP/1.1 — request writing, response parsing and chunked
   transfer decoding — because there is no `HttpsURLConnection` underneath it.

`FallbackTransport` retries on the second stack **only when the failure was at the TLS
level**. A 401 or 404 means the connection worked and the server answered, so retrying
would double the request and hide the real response.

This is not theoretical. On Android 4.3 the platform stack negotiates TLS 1.2 correctly —
the logs show `[TLSv1, TLSv1.1, TLSv1.2]` offered — and *still* fails the handshake with
`SSL23_GET_SERVER_HELLO: unsupported protocol`, because AWS (which fronts DeepSeek)
requires SNI and Android 4.3 does not send it. The fallback catches exactly that.

**What has actually been verified, on real hardware and emulators:**

| Device / level | Result |
|---|---|
| Android 2.3 emulator (API 10) | Bundled TLS **fails** — `VerifyError` in `org.bouncycastle.tls.TlsUtils` |
| Android 4.3 emulator (API 18) | Bundled TLS **works**; reaches DeepSeek with HTTP 401 |
| **BlackBerry Q5**, Android 4.3 runtime | Bundled TLS **works** — confirmed on the real device this was built for |
| Android 5.0 emulator (API 21) | Both stacks work |

That API 10 failure is not a shrinking artefact: plain `d8` with **no R8 at all**
reproduces it, so it is the dexer's output failing Dalvik's older bytecode verifier. An
earlier draft of this file concluded from that one data point that the bundled TLS only
worked on ART. **That was wrong**, and a BlackBerry Q5 disproved it: Android 4.3 is
Dalvik too, and its verifier is more than capable. The honest statement is that the
bundled stack needs Android 4.1 or newer, and the floor below which nothing can reach
these APIs is around there.

**Test connection** in Settings reports which stack actually carried the request — not
which one was configured, since those differ whenever a fallback engages — and diagnoses
DNS, TLS and HTTP without spending a request against your quota.

### Signing, and why an update can refuse to install

Android will not update an installed app whose **signing key has changed**. If every
release is signed with a different key, "install" fails with
`INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES` and the only way forward is to uninstall
first, losing all conversations.

This bit the project for real: `keystore.jks` is gitignored, so every CI run generated a
fresh random key and v1.0.0 and v1.0.1 could not install over one another. Two fixes:

* `build.sh` prefers a **stable** key at `release.jks` when one is present, so successive
  local builds upgrade each other. It is gitignored — a public repository that ships a
  signing key lets anyone sign an APK that upgrades this one on your device.
* For CI releases, set four repository secrets and `release.yml` uses them:

  ```bash
  gh secret set KEYSTORE_BASE64    --body "$(base64 -w0 release.jks)"
  gh secret set KEYSTORE_PASSWORD  --body "$(cat release-key-password.txt)"
  gh secret set KEY_ALIAS          --body "oneai"
  gh secret set KEY_PASSWORD       --body "$(cat release-key-password.txt)"
  ```

  Without them the release still builds, with a throwaway key, and says so in its notes.

The version code is now derived from the tag too — `v1.2.3` becomes `10203` — because
`aapt2` only injects `--version-code` when the manifest has none, so without
`--replace-version` every release silently shipped as `1`.

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

## Continuous integration

`.github/workflows/ci.yml` runs three jobs on every push and pull request:

* **Provider tests** — the JVM suite. No SDK, no emulator, seconds.
* **Build** — a real APK from a real Android SDK, then `tools/verify-apk.sh`.
* **Smoke test** — installs that exact artefact and launches it on **API 18** and
  **API 21** emulators, failing on `FATAL EXCEPTION`, `VerifyError`,
  `NoSuchMethodError` or `Resources$NotFoundException`. API 18 is the level BlackBerry
  10's Android runtime sits at. Building is not the same as running, and errors of that
  shape only ever appear at runtime on the target hardware.

The smoke test lives in `tools/smoke-test.sh`, not in the workflow, for a concrete
reason: that Action runs its `script` input through `sh -c` **one line at a time**, so
an `if`/`then`/`fi` block written in the YAML fails with "Syntax error: end of file
unexpected" instead of testing anything. In the repo it also runs against a local
emulator before you push.

The verification step is worth calling out. It asserts the promises the project makes:
minSdkVersion is still 4, a launcher activity exists, v1 (JAR) signing is present —
without which the APK will not install below Android 7 — and the dex references no API
newer than the floor. Those live in `tools/verify-apk.sh` rather than in workflow YAML,
so they run on a laptop too and a developer finds out before pushing.

`release.yml` fires on a `v*` tag and publishes the APK with a SHA-256. It accepts a
real signing key from repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`); without them it still builds, with the throwaway debug
key, and says so in the release notes. That matters because Android refuses to update
an installed app whose signing key changed, so shipping each release under a different
key forces every user to uninstall first.

The build toolchain is pinnable via `ANDROID_BUILD_TOOLS` and `ANDROID_PLATFORM`, and
`build.sh` records what it used in `build/sdk.env` so the verifier inspects the APK with
the same tools that produced it. Without that, CI and a laptop each pick their own
newest build-tools, and "it passed locally" stops meaning anything.

## Testing

```bash
./test/run.sh     # 51 tests, no framework required
```

Covers the request JSON each provider emits (including the attachment shapes above),
SSE parsing (frames split across reads, multi-byte UTF-8 split mid-character, CRLF,
`[DONE]` vs EOF, thinking deltas), error mapping for each provider's real error
envelope, hostname verification including the rejection cases — wildcards that would
match a bare domain, cross label boundaries, or cover a public suffix — and the URL
rule that stops a versioned base from becoming `/v1/v1/chat/completions`.

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

### Three build-tool bugs worth knowing about

* The R8 bundled in build-tools 34 is an `8.2.2-dev` build that crashes with an internal
  `NullPointerException` on any class carrying an `InnerClasses` attribute — that is,
  on virtually all real Java. `build.sh` pins a stable R8 from Google's Maven instead.
* Android 2.x requires `layout_width`/`layout_height` on an `<include>` tag and on every
  view whose style does not supply them. Omitting them throws
  `"You must supply a layout_width attribute"` at inflate time. Layout params therefore
  live on the elements, never only in styles.
* **Android's `org.json` is not the reference `org.json`.** `optString(key, fallback)`
  returns the literal string `"null"` when the value is JSON `null`, where the reference
  implementation returns the fallback. DeepSeek sends `"content": null` on chunks with no
  text, and each one was appended as four characters — a reply that began
  `nullnullnullnull`. It compiles, it passes on the JVM, and it only misbehaves on a
  device. All possibly-null reads now go through `Json.str`, and the JVM suite pins that
  helper's contract rather than the platform's behaviour, because it cannot reproduce the
  platform's behaviour at all.
* **build-tools 37 renames a key in `aapt2 dump badging`**, from `sdkVersion` to
  `minSdkVersion` — note the capital `S`, which is why a lowercase
  `sdkVersion:'4'` check silently stops matching. The APK is identical and correct; only
  the text output changed. `build.sh` therefore prefers a known build-tools revision
  rather than taking the newest, and the verifier matches the key loosely and dumps the
  whole badging block when it cannot find one.

---

## Not included

Tool use and audio. Neither fits a keyboard-first client on hardware this old, and the
`Transport`/`Provider` seams leave room for both.

PDFs can be attached and sent, and the APIs read them, but there is no in-app PDF viewer
— only images open full-size.

## Licence

None chosen yet — add a `LICENSE` file before relying on this being reusable.
