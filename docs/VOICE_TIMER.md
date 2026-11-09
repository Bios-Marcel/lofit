# Voice timer

Enable “Voice commands” in the active workout's options menu. It defaults to off
for each new workout and stays enabled only for that workout, including after
reopening it. When disabled, no model is prepared and no
microphone access is requested.

Say “time” while an unfinished timed exercise is visible in an active workout.
Lofit asks for microphone access the first time this applies. The command starts
the first unfinished set in the first visible eligible exercise, in workout
order. Say “time” again to stop the running manual timer, using the same action
as a tap. The running timer takes priority over other sets, including failure
sets that have passed their goal. Scale-controlled sets are excluded;
switching “Use scale” off enables voice start for those exercises.

Listening stays on while a manual timer is running and its exercise is visible.
It stops when a scale measurement is running, no eligible
exercise is visible, the drawer or another window covers the workout, the
screen changes, or the activity pauses. A queued result cannot start a hidden
or different set. Denying microphone permission leaves manual timers available.

The 40 MB English speech model is bundled with the APK. Recognition runs locally, without
Android speech services, accounts, runtime downloads, or network access. Audio
is processed in memory and is not saved. The model stays warm during the
workout's lifetime, uses 20 ms microphone reads and streaming partial results, and
does not wait for a full dictation result. Similar words compete in the decoder;
only “time” is an action. Source, license, and checksums are in
`app/src/main/assets/voice-time/NOTICE.txt`.

Finishing or discarding the workout stops capture and releases the recognizer
and model from native memory. Cleanup waits for any in-flight model load or
audio processing on the background worker. The extracted files stay on disk;
a later eligible workout loads a fresh model.

Recognition is not perfect: pronunciation, distance, music, and gym noise can
cause misses or false starts. Automated checks use synthetic US/UK speech,
male/female natural voices, quieter speech, background hiss, and similar words.
They do not replace testing the user's voice and phone in the actual gym.

Run recognition and lifecycle checks on the default two emulators:

```sh
./scripts/test-sharded.py --shards 2 --class lofit.TimeKeywordDetectorTest,lofit.VoiceTimerFlowTest
```
