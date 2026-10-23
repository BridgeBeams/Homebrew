wAide SERVER
============

This sets a Windows PC up as your private wAide server: the model engine (Ollama),
the private tunnel (Tailscale), and the PIN-locked conversations service, all in
one shot. Re-runnable and safe; it only does what isn't already done.

FIRST RUN
  1. If Windows SmartScreen warns ("unknown publisher"), click "More info" then
     "Run anyway". This installer is self-built and unsigned; that's expected.
  2. When it asks, let it set the server up.
  3. When Tailscale opens a browser, sign in with the SAME account your phone uses,
     then return to the black window.
  4. Let it pull the default models (large, one-time). You can skip and add models
     from the phone later.
  5. At the end it prints two addresses:
        Model engine (amnesia Server tab): http://100.x.y.z:11434
        Conversations service:             http://100.x.y.z:11435

ON YOUR PHONE
  wAide app > Settings > Machines > add the machine with those addresses.

DEFAULT MODELS
  The models a new server pulls automatically are listed in default-models.txt
  (Start Menu > "Edit default models list"). Add or remove lines to change them,
  then re-run setup, or rebuild the installer on GitHub. You can always search for
  and pull other models from the phone.

RAW .gguf FILES (optional)
  If you have a model file that isn't on Ollama or Hugging Face, drop the .gguf into
  the "wAide Models" folder (Start Menu > "Open models folder") and run
  "Import dropped .gguf models". It registers them with Ollama so they show up
  in the app.

START MENU SHORTCUTS
  - Set up or Update wAide Server  (re-run anytime; safe)
  - Import dropped .gguf models
  - Open models folder
  - Edit default models list

HONEST NOTES
  - Needs internet during setup (it downloads Ollama, Tailscale, Python, models).
  - The conversations service stores your conversations on THIS PC as JSON, locked
    by a 6-digit PIN (default 313131, change it from the phone). It's private
    because it's your machine behind your tunnel, PIN-gated, with nothing cached on
    the phone. It is not hidden from this PC itself (that's by design, so agents can
    read your conversations).
  - More servers later: run this same installer on each. They don't talk to each
    other; each has its own conversations and agents.
