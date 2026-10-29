"""Regenerate test-only media with Pillow and FFmpeg (with -display_rotation).

No downloaded artwork is used. Run from any directory with Python and Pillow.
"""
from pathlib import Path
import random
import subprocess
from PIL import Image, ImageDraw

assets = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets"
assets.mkdir(parents=True, exist_ok=True)
base = Image.new("RGBA", (240, 160))
draw = ImageDraw.Draw(base)
draw.rectangle((20, 20, 110, 140), fill="red")
draw.ellipse((130, 30, 220, 120), fill="blue")
base.save(assets / "source.png")
frames = []
for x in (10, 40, 70):
    image = Image.new("RGBA", (120, 100))
    ImageDraw.Draw(image).rectangle((x, 20, x + 25, 70), fill="red")
    frames.append(image)
frames[0].save(assets / "disposal.gif", save_all=True,
               append_images=frames[1:], duration=[80, 120, 200],
               loop=0, disposal=[2, 3, 2], optimize=False)
frames[0].save(assets / "animated.webp", save_all=True,
               append_images=frames[1:], duration=[80, 120, 200], loop=0, lossless=True)
full = [frame.resize((512, 512)) for frame in frames]
full[0].save(assets / "compatible.webp", save_all=True,
             append_images=full[1:], duration=[80, 120, 200], loop=0, lossless=True)
full[0].save(assets / "static.webp", lossless=True)
rng = random.Random(42)
noise = [Image.frombytes("RGB", (256, 256), rng.randbytes(256 * 256 * 3))
         .quantize(colors=128) for _ in range(20)]
noise[0].save(assets / "noisy.gif", save_all=True,
              append_images=noise[1:], duration=50, loop=0, disposal=2)
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=size=160x96:rate=20:duration=0.8", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", "-y", str(assets / "video.mp4")], check=True)
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error",
                "-display_rotation:v:0", "90", "-i", str(assets / "video.mp4"),
                "-c", "copy", "-y", str(assets / "rotated.mp4")], check=True)
