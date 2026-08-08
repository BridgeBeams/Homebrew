# w-Ai-de mobile

The chat half of w-Ai-de, rebuilt as a real Android app. No web GUI, no server, no
Ollama, no network. The phone loads a GGUF model file straight off storage and runs
it on the CPU.

Built for a Galaxy Z Fold 7 (Snapdragon 8 Elite, 12 GB RAM). It will run on any
arm64 Android 10 or newer phone.

---

## Getting the APK built

You never install anything on your PC. GitHub compiles it for you.

1. Go to github.com and create a **new private repository**. Call it `waide-android`.
2. On the new empty repo page, click **uploading an existing file**.
3. Drag in everything from this folder. Keep the folder structure intact. The
   `.github` folder matters, so if your file browser hides dotted folders, turn on
   "show hidden files" before you drag.
4. Commit. The build starts on its own.
5. Click the **Actions** tab. The run takes about 15 to 25 minutes the first time,
   because it compiles the whole inference engine from source. Later runs are faster.
6. When the run shows a green check, open it and download the **w-Ai-de-apk**
   artifact at the bottom. It arrives as a zip.
7. Unzip it, put `w-Ai-de.apk` on your phone, and tap it. Android will ask you to
   allow installing from that source. Allow it.

If the run shows a red X, open it, click the failed step, and send me the last 30
lines. That is usually a one line fix.

## First run on the phone

1. Open w-Ai-de. Go to the **Models** tab.
2. Tap **Grant file access**. Android opens a settings page. Turn on "Allow access
   to manage all files" and come back. This is what lets the app read model files
   out of your Downloads folder.
3. On your phone, open Chrome and download a `.gguf` model (see below). It lands in
   Downloads.
4. Back in w-Ai-de, tap **Rescan**. Your model appears. Tap it.
5. Loading takes 10 to 60 seconds depending on size. Then it drops you into Chat.

To add a model later, download another `.gguf` and hit Rescan. That is the whole
sideload mechanism. No reinstall, ever.

## Models worth putting on a Fold 7

Search these on huggingface.co and download the `Q4_K_M` file. Tap the download
arrow next to the file name, not the repo page.

**Start here to prove it works, then move up.**

| Model | Size | What to expect |
|---|---|---|
| Llama 3.2 3B Instruct | ~2.0 GB | Fast. Roughly 12 to 20 tokens a second. Good daily driver. |
| Qwen2.5 3B Instruct | ~1.9 GB | Similar speed, stronger at reasoning and numbers. |
| Dolphin 3.0 Llama 3.1 8B | ~4.9 GB | Your uncensored pick from the desktop build. Roughly 4 to 7 tokens a second. Usable, not snappy. |
| Qwen2.5 7B Instruct | ~4.7 GB | Best quality that will still fit comfortably. Same speed range. |

Anything above 8B is not worth trying on a phone. A 13B at Q4 needs about 8 GB of
RAM just for weights, and Android will kill the app before it finishes loading.

Look for repos with "GGUF" in the name (TheBloke, bartowski, and lmstudio-community
all publish them). Grab the Q4_K_M variant unless you have a reason not to.

## Settings that matter

- **Context size** defaults to 4096. Raising it to 8192 costs a few hundred MB of
  RAM and lets the model hold a longer conversation. Takes effect the next time you
  load a model.
- **CPU threads** defaults to 4. On the Fold's 8 Elite, 4 uses the big cores and is
  usually the fastest. Going to 8 often makes it slower and always makes it hotter.
- **System prompt** is your standing instruction. Applied when you start a new chat.

## Honest limits

**It gets warm and it drains.** Sustained generation on an 8B model will heat the
phone and pull the battery down noticeably. Android will also throttle the CPU after
a few minutes of hard work, so the first reply is usually the fastest one.

**Chat only.** No plan reading, no take-offs, no image generation. Those were the
weak parts on the desktop build and they would be worse on a phone.

**Small models are small.** A 3B model on a phone is useful for drafting, thinking
out loud, quick explanations, and questions you would rather not send to a server.
It is not a substitute for the big models when the answer has to be right.

**The signing key is in this repo.** `app/waide-release.jks` with the password in
`app/build.gradle.kts`. That is deliberate: it keeps the signature stable so new
builds install over the old app instead of forcing an uninstall. Because the key is
in the repo, keep the repo private. This app is never going on the Play Store, so
that tradeoff is fine.

## Changing the engine version later

`app/src/main/cpp/CMakeLists.txt` has one line: `set(LLAMA_TAG "b9453")`. That pins
which build of llama.cpp gets compiled. To pick up newer model support, change it to
a newer tag from https://github.com/ggml-org/llama.cpp/tags and push. If a newer tag
ever breaks the build, put b9453 back.

## What is in here

```
app/src/main/cpp/waide-jni.cpp   the engine bridge: loads GGUF, streams tokens
app/src/main/java/.../LlamaBridge.kt    Kotlin side of that bridge
app/src/main/java/.../ChatViewModel.kt  chat state and the streaming loop
app/src/main/java/.../ModelStore.kt     settings and the .gguf file scanner
app/src/main/java/.../ui/               Chat, Models, Settings screens
.github/workflows/build-apk.yml         the cloud build
```
