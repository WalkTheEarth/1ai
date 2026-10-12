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

Tool use and audio. Neither fits a keyboard-first client on hardware this old, and the
`Transport`/`Provider` seams leave room for both.

PDFs can be attached and sent, and the APIs read them, but there is no in-app PDF viewer
— only images open full-size.

## Licence

None chosen yet — add a `LICENSE` file before relying on this being reusable.
