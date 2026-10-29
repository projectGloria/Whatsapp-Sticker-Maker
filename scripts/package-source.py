"""Create the self-contained source handoff, excluding tools and build caches."""
from pathlib import Path
import os
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / "deliverables/Sticker-Studio-Source.zip"
output.parent.mkdir(exist_ok=True)
skip = {".tools", ".gradle", ".kotlin", ".git", ".cxx", ".idea",
        "build", "deliverables", "backUP", "__pycache__"}
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for directory, folders, files in os.walk(root):
        folders[:] = sorted(name for name in folders if name not in skip)
        for name in sorted(files):
            if name == "local.properties" or name.endswith(".iml"):
                continue
            path = Path(directory) / name
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo.from_file(path, relative)
            if relative == "gradlew":
                info.create_system = 3
                info.external_attr = 0o100755 << 16
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
print(output)
