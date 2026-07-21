import os
import re
import shutil
import subprocess
from pathlib import Path

TARGETS = [
    ('all', 'pornhub'),
    ('all', 'supjav'),
    ('all', 'missav'),
]

root = Path('.')
apk_dir = root / 'apk'
apk_apk_dir = apk_dir / 'apk'

apk_dir.mkdir(exist_ok=True)
apk_apk_dir.mkdir(exist_ok=True)

print("1. Bumping extVersionCode by +1 for pornhub, supjav, missav...")
for lang, name in TARGETS:
    bg = root / 'src' / lang / name / 'build.gradle'
    if bg.exists():
        content = bg.read_text(encoding='utf-8')
        m = re.search(r'extVersionCode\s*=\s*(\d+)', content)
        if m:
            old_code = int(m.group(1))
            new_code = old_code + 1
            content = re.sub(r'extVersionCode\s*=\s*\d+', f'extVersionCode = {new_code}', content)
            if 'overrideVersionCode' in content:
                content = re.sub(r'overrideVersionCode\s*=\s*\d+', f'overrideVersionCode = {new_code}', content)
            bg.write_text(content, encoding='utf-8')
            print(f"Bumped {lang}:{name} extVersionCode {old_code} -> {new_code}")

print("\n2. Building release APKs...")
assemble_tasks = [f":src:{lang}:{name}:assembleRelease" for lang, name in TARGETS]
cmd = [".\\gradlew.bat"] + assemble_tasks + ["--no-daemon"]
res = subprocess.run(cmd, shell=True)
if res.returncode != 0:
    print(f"Error building release APKs! Exit code: {res.returncode}")
    exit(1)

print("\n3. Syncing release APKs across 3 paths...")
count = 0
for lang, name in TARGETS:
    bg = root / 'src' / lang / name / 'build.gradle'
    if not bg.exists():
        continue
    content = bg.read_text(encoding='utf-8')
    m_ver = re.search(r'extVersionCode\s*=\s*(\d+)', content)
    if not m_ver:
        continue
    
    release_dir = root / 'src' / lang / name / 'build' / 'outputs' / 'apk' / 'release'
    if not release_dir.exists():
        continue
    
    apk_files = list(release_dir.glob('*.apk'))
    if not apk_files:
        continue
    
    built_apk = apk_files[0]
    pkg = f"aniyomi-{lang}.{name}"
    
    for p in [root, apk_dir, apk_apk_dir]:
        for old in p.glob(f"{pkg}-v*.apk"):
            old.unlink()
            print(f"Removing old {old}")

    new_apk_name = f"{pkg}-v14.{m_ver.group(1)}.apk"
    
    shutil.copy2(built_apk, root / new_apk_name)
    shutil.copy2(built_apk, apk_dir / new_apk_name)
    shutil.copy2(built_apk, apk_apk_dir / new_apk_name)
    print(f"Updated {new_apk_name} across 3 paths")
    count += 1

print(f"\nTotal {count} APKs updated across 3 fallback paths!")

print("\n4. Rebuilding index manifests...")
import rebuild_index
rebuild_index.main() if hasattr(rebuild_index, 'main') else None

print("\nCompleted build for top 3 adult extensions!")
