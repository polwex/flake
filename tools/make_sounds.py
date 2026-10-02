#!/usr/bin/env python3
"""Synthesizes sorchat's sound effects into app/src/main/res/raw/ (soft, warm, short).

Original sounds, so no licensing questions: sine partials with smooth envelopes.
Run from the repo root: python3 tools/make_sounds.py
"""
import math
import struct
import wave

RATE = 44100
OUT = "app/src/main/res/raw"


def note(freq, start, length, volume=0.5, attack=0.006, harmonics=((1, 1.0), (2, 0.18), (3, 0.05)), decay=6.0):
    """A plucked/bell-ish tone: fast attack, exponential decay, a little harmonic warmth."""
    return (freq, start, length, volume, attack, harmonics, decay)


def render(notes, total, fade_out=0.02):
    samples = [0.0] * int(total * RATE)
    for freq, start, length, volume, attack, harmonics, decay in notes:
        s0 = int(start * RATE)
        for i in range(int(length * RATE)):
            t = i / RATE
            env = min(1.0, t / attack) * math.exp(-decay * t)
            # Fade the tail to zero so nothing clicks.
            env *= min(1.0, (length - t) / 0.01)
            v = sum(a * math.sin(2 * math.pi * freq * h * t) for h, a in harmonics)
            if s0 + i < len(samples):
                samples[s0 + i] += volume * env * v
    n_fade = int(fade_out * RATE)
    for i in range(n_fade):
        samples[-1 - i] *= i / n_fade
    peak = max(1e-9, max(abs(x) for x in samples))
    scale = min(1.0, 0.85 / peak)
    return [x * scale for x in samples]


def save(name, samples):
    with wave.open(f"{OUT}/{name}.wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32767)) for x in samples))


# Notes (Hz)
C5, E5, G5, A5, C6, D6, E6, G4, E4, C4 = 523.25, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.5, 392.0, 329.63, 261.63

# Sent: a quick upward "bloop" (two notes a fifth apart, very short).
save("sound_send", render([note(G5, 0, 0.12, 0.45, decay=22), note(D6, 0.05, 0.16, 0.4, decay=18)], 0.22))

# Received: a soft two-note chime, downward third.
save("sound_receive", render([note(E6, 0, 0.35, 0.35, decay=9), note(C6, 0.09, 0.45, 0.4, decay=7)], 0.55))

# Call connected: a warm rising major arpeggio.
save("sound_call_connected", render([note(C5, 0, 0.5, 0.35, decay=5), note(E5, 0.08, 0.5, 0.35, decay=5), note(G5, 0.16, 0.6, 0.35, decay=4.5)], 0.8))

# Call ended: a gentle falling "drop".
save("sound_call_ended", render([note(G4, 0, 0.35, 0.45, decay=8), note(E4, 0.12, 0.35, 0.45, decay=8), note(C4, 0.24, 0.5, 0.45, decay=6)], 0.8))

# Ringback while calling (loops): two soft pulses, then quiet. 2.5 s per cycle.
pulse = dict(attack=0.03, decay=2.5, harmonics=((1, 1.0), (2, 0.1)))
save("sound_ringback", render([note(A5, 0.0, 0.45, 0.3, **pulse), note(E5, 0.0, 0.45, 0.2, **pulse),
                              note(A5, 0.55, 0.45, 0.3, **pulse), note(E5, 0.55, 0.45, 0.2, **pulse)], 2.5, fade_out=0.005))
