#!/usr/bin/env python3
"""Render original completion and royal record cues; no external tools needed."""

import argparse
import math
from pathlib import Path
import struct
import wave


SAMPLE_RATE = 44_100
DURATION = 1.2
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/completion_chime.wav"


def render():
    samples = [0.0] * round(SAMPLE_RATE * DURATION)
    for start, frequency, level, decay in (
        (0.0, 523.251, 0.85, 0.23),
        (0.19, 659.255, 1.0, 0.28),
    ):
        for index in range(round(start * SAMPLE_RATE), len(samples)):
            elapsed = index / SAMPLE_RATE - start
            # Rounded attack and mostly fundamental keep the timbre warm.
            attack = 1.0 - math.exp(-elapsed / 0.009)
            phase = math.tau * frequency * elapsed
            tone = (
                math.sin(phase) * math.exp(-elapsed / decay)
                + 0.12 * math.sin(2 * phase) * math.exp(-elapsed / 0.12)
                + 0.025 * math.sin(3 * phase) * math.exp(-elapsed / 0.07)
            )
            samples[index] += level * attack * tone

    dry = samples.copy()
    for delay, level in ((0.037, 0.10), (0.071, 0.06), (0.113, 0.03)):
        offset = round(delay * SAMPLE_RATE)
        for index in range(offset, len(samples)):
            samples[index] += level * dry[index - offset]

    # Fade the tail to zero, avoiding a click at the end of the sample.
    fade_samples = round(0.15 * SAMPLE_RATE)
    for index in range(fade_samples):
        samples[-fade_samples + index] *= 0.5 * (1 + math.cos(math.pi * index / (fade_samples - 1)))

    gain = 0.68 / max(abs(sample) for sample in samples)
    pcm = b"".join(struct.pack("<h", round(sample * gain * 32767)) for sample in samples)
    with wave.open(str(OUTPUT), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm)


def render_record():
    samples = [0.0] * round(SAMPLE_RATE * DURATION)
    # A short major-key brass fanfare, ending on a warm C-major chord.
    notes = (
        (0.00, 0.11, 391.995, 0.75),
        (0.13, 0.11, 523.251, 0.80),
        (0.26, 0.11, 659.255, 0.85),
        (0.39, 0.13, 783.991, 0.90),
        (0.54, 0.48, 1046.502, 1.0),
        (0.54, 0.48, 523.251, 0.30),
        (0.54, 0.48, 659.255, 0.25),
        (0.54, 0.48, 783.991, 0.25),
    )
    for start, duration, frequency, level in notes:
        for index in range(round(start * SAMPLE_RATE), round((start + duration) * SAMPLE_RATE)):
            elapsed = index / SAMPLE_RATE - start
            attack = 1.0 - math.exp(-elapsed / 0.008)
            release = min(1.0, max(0.0, (duration - elapsed) / 0.045))
            envelope = attack * (0.5 - 0.5 * math.cos(math.pi * release))
            phase = math.tau * frequency * elapsed
            brass = sum(
                amplitude * math.sin(harmonic * phase)
                for harmonic, amplitude in ((1, 1.0), (2, 0.40), (3, 0.22), (4, 0.10), (5, 0.05))
            )
            samples[index] += level * envelope * brass

    dry = samples.copy()
    for delay, level in ((0.037, 0.09), (0.073, 0.05), (0.12, 0.025)):
        offset = round(delay * SAMPLE_RATE)
        for index in range(offset, len(samples)):
            samples[index] += level * dry[index - offset]

    gain = 0.65 / max(abs(sample) for sample in samples)
    pcm = b"".join(struct.pack("<h", round(sample * gain * 32767)) for sample in samples)
    destination = OUTPUT.with_name("record_fanfare.wav")
    with wave.open(str(destination), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true", help="render the royal record fanfare")
    args = parser.parse_args()
    render_record() if args.record else render()
