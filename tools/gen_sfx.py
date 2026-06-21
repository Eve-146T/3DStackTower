#!/usr/bin/env python3
"""Generates the game's sound effects procedurally into assets/sfx/.

Pure-stdlib synthesis (wave + math) so the assets are reproducible:
    python3 tools/gen_sfx.py
"""
import math
import os
import random
import struct
import wave

SR = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "assets", "sfx")

TWO_PI = 2.0 * math.pi


def write_wav(name, samples):
    path = os.path.join(OUT_DIR, name)
    frames = b"".join(
        struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples
    )
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(frames)
    peak = max(abs(s) for s in samples)
    print(f"{name}: {len(samples) / SR * 1000:.0f} ms, peak {peak:.2f}")


def fade_edges(samples, attack_s=0.0015, release_s=0.004):
    """Short linear ramps at both ends so one-shots never click."""
    n = len(samples)
    a = max(1, int(attack_s * SR))
    r = max(1, int(release_s * SR))
    for i in range(min(a, n)):
        samples[i] *= i / a
    for i in range(min(r, n)):
        samples[n - 1 - i] *= i / r
    return samples


def place():
    """Soft percussive 'thock' for a normal block landing."""
    n = int(SR * 0.10)
    rng = random.Random(7)
    out = []
    phase = 0.0
    lp = 0.0
    for i in range(n):
        t = i / SR
        freq = 210.0 + 310.0 * math.exp(-t / 0.022)  # 520 Hz -> 210 Hz knock
        phase += TWO_PI * freq / SR
        body = math.sin(phase) * math.exp(-t / 0.030)
        noise = rng.random() * 2.0 - 1.0
        lp += 0.25 * (noise - lp)  # dull the click a little
        click = lp * math.exp(-t / 0.004)
        out.append(math.tanh(1.6 * (0.9 * body + 0.5 * click)) * 0.9)
    return fade_edges(out)


def perfect():
    """Bright two-strike chime for frame-perfect placements."""
    n = int(SR * 0.34)

    def strike(t, f0, t0, tau):
        if t < t0:
            return 0.0
        tt = t - t0
        a = math.exp(-tt / tau) * min(1.0, tt / 0.001)
        return a * (
            math.sin(TWO_PI * f0 * tt)
            + 0.35 * math.sin(TWO_PI * 2.0 * f0 * tt + 0.3)
            + 0.12 * math.sin(TWO_PI * 3.01 * f0 * tt)
        )

    out = []
    for i in range(n):
        t = i / SR
        s = 0.55 * strike(t, 1174.66, 0.0, 0.10)   # D6
        s += 0.55 * strike(t, 1567.98, 0.045, 0.12)  # G6
        out.append(math.tanh(1.3 * s) * 0.85)
    return fade_edges(out, release_s=0.02)


def gameover():
    """Low descending 'womp' with a touch of rumble."""
    n = int(SR * 0.55)
    rng = random.Random(13)
    out = []
    phase = 0.0
    lp = 0.0
    for i in range(n):
        t = i / SR
        freq = 64.0 + 176.0 * math.exp(-t / 0.16)  # 240 Hz -> 64 Hz slide
        phase += TWO_PI * freq / SR
        tone = (
            math.sin(phase)
            + 0.30 * math.sin(2.0 * phase)
            + 0.15 * math.sin(3.0 * phase)
        )
        noise = rng.random() * 2.0 - 1.0
        lp += 0.05 * (noise - lp)
        rumble = lp * 0.25 * math.exp(-t / 0.20)
        env = math.exp(-t / 0.28)
        out.append(math.tanh(1.7 * (0.8 * tone * env + rumble)) * 0.9)
    return fade_edges(out, attack_s=0.004, release_s=0.03)


def click():
    """Tiny UI tick for buttons / taps."""
    n = int(SR * 0.035)
    out = []
    for i in range(n):
        t = i / SR
        a = math.exp(-t / 0.008)
        s = a * (math.sin(TWO_PI * 1900.0 * t) + 0.2 * math.sin(TWO_PI * 3800.0 * t))
        out.append(s * 0.6)
    return fade_edges(out, attack_s=0.0005)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    write_wav("place.wav", place())
    write_wav("perfect.wav", perfect())
    write_wav("gameover.wav", gameover())
    write_wav("click.wav", click())


if __name__ == "__main__":
    main()
