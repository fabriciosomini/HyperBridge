"""Renders clips/*.json (the Hyper Pet's Lottie clips) to app/src/main/res/raw/hyper_pet_<clip>.mp4.

HyperOS's island plugin only plays Lottie files from its own assets, so the pet ships as looping
MP4s on a black background (MP4 has no alpha; the pill is black).

    pip install rlottie-python imageio-ffmpeg pillow
    python tools/hyper-pet/render.py
"""
import json
import subprocess
from pathlib import Path

import imageio_ffmpeg
from PIL import Image
from rlottie_python import LottieAnimation

HERE = Path(__file__).resolve().parent
RAW = HERE.parents[1] / "app/src/main/res/raw"
SIZE = 144  # the slot is ~62 px, so this is already more than 2x
FPS = 30
RENDER = 480  # drawn large, then cropped to the pet and scaled down
PROBE = 120  # the size used to find where the pet is


class Clip:
    def __init__(self, source):
        lottie = json.loads(source.read_text(encoding="utf-8"))
        self.name = source.stem
        self.anim = LottieAnimation.from_file(str(source))
        # The last frame equals the first, so stopping one step short makes the loop seamless.
        self.frames = range(int(lottie["ip"]), int(lottie["op"]), round(lottie["fr"] / FPS))

    def frame(self, n, size):
        return self.anim.render_pillow_frame(frame_num=n, width=size, height=size).convert("RGBA")


def main():
    clips = [Clip(source) for source in sorted((HERE / "clips").glob("*.json"))]
    # A pet rarely fills its canvas, and the slot is tiny, so keep only the square it moves in.
    # One square for every clip, or the dog would change size from one clip to the next.
    boxes = [b for clip in clips for n in clip.frames[::2]
             if (b := clip.frame(n, PROBE).getchannel("A").getbbox())]
    scale = RENDER / PROBE
    left, top = min(b[0] for b in boxes) * scale, min(b[1] for b in boxes) * scale
    right, bottom = max(b[2] for b in boxes) * scale, max(b[3] for b in boxes) * scale
    side = round(max(right - left, bottom - top) * 1.06)
    x, y = round((left + right - side) / 2), round((top + bottom - side) / 2)

    for clip in clips:
        out = RAW / f"hyper_pet_{clip.name}.mp4"
        ffmpeg = subprocess.Popen(
            [
                imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{SIZE}x{SIZE}", "-r", str(FPS), "-i", "-",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline", "-crf", "22",
                "-movflags", "+faststart", str(out),
            ],
            stdin=subprocess.PIPE,
        )
        for n in clip.frames:
            # crop() pads with transparency past the canvas edge.
            black = Image.new("RGBA", (side, side), (0, 0, 0, 255))
            pet = Image.alpha_composite(black, clip.frame(n, RENDER).crop((x, y, x + side, y + side)))
            ffmpeg.stdin.write(pet.convert("RGB").resize((SIZE, SIZE), Image.LANCZOS).tobytes())
        ffmpeg.stdin.close()
        if ffmpeg.wait() != 0:
            raise SystemExit(f"ffmpeg failed on {clip.name}")
        print(f"wrote {out.name} ({out.stat().st_size} bytes, {len(clip.frames)} frames)")


if __name__ == "__main__":
    main()
