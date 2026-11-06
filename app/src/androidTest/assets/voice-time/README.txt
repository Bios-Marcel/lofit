Synthetic regression audio, generated locally.

eSpeak NG at speed 155:
Voices: en-us (time-us), en-gb (time-uk), en-us+f3 (time-robotic-female).
Positive text: time
Negative text (en-us): start. stop. nine. tiny. climb. turn. set. hello. workout.

espeak-ng -v VOICE -s 155 -w output.wav TEXT

Piper TTS, default synthesis settings:
en_US-lessac-medium (time-natural and lessac-*),
en_US-amy-medium (time-female and amy-*).
Source models: https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US
Each file speaks the word in its name. Competing words test false starts.
These voice-generation models are not bundled in the app.

WAV output is linearly resampled from 22050 Hz to mono 16000 Hz, with 200 ms
of leading silence and 1 second of trailing silence. Files are raw signed
little-endian PCM16. These fixtures test streaming recognition and latency;
they do not establish real-world accuracy across speakers or noisy gyms.
