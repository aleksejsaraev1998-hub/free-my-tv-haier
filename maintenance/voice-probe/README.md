# Bento Voice Test

Standalone foreground diagnostic, package `io.freemytv.voiceprobe`, not a HOME or an assistant replacement. Build with JDK 17 and SDK 34 via `bash build.sh`, then sign with a retained private development key. No keys belong in git.

All tests require an explicit button press. The PCM probe requests RECORD_AUDIO, runs for at most eight seconds, uses nonblocking reads and stops on Pause, Back, Home or Stop. It reports sample counts, RMS/peak and the routed audio device, retaining no audio files. Nonzero samples do not prove capture of speech or the remote's microphone. No foreground/background service, INTERNET permission or cloud API credentials are included. The separate system-recognition flow may use the installed provider's network services; recognized test text is shown and logged under `BentoVoiceTest`. Do not dictate private content during diagnostics. TTS reports available Russian voices and network requirements, with callbacks for the sample utterance. A successful callback does not prove audible speech: the owner must confirm that separately.

Compare the TV-body and remote microphones, the physical mute switch, and each audio source in a controlled session. Keep Google Assistant enabled. Tests must not change the assistant role, Bluetooth pairing, system permissions beyond the app's runtime microphone grant, or firmware.
