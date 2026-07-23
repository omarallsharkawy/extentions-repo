import os
import re
import shutil
import subprocess
from pathlib import Path

# All 22 adult extension modules
ADULT_MODULES = [
    ('ar', 'aflamk1'),
    ('ar', 'arabshentai'),
    ('ar', 'arabx'),
    ('ar', 'arabxn'),
    ('ar', 'nxxhentai'),
    ('ar', 'sexalarab'),
    ('ar', 'sexmahali'),
    ('en', 'hahomoe'),
    ('en', 'hanime'),
    ('en', 'hentaimama'),
    ('en', 'hstream'),
    ('en', 'rule34video'),
    ('all', 'hentaitorrent'),
    ('all', 'jable'),
    ('all', 'javgg'),
    ('all', 'javguru'),
    ('all', 'missav'),
    ('all', 'pornhub'),
    ('all', 'ptorrent'),
    ('all', 'rouvideo'),
    ('all', 'supjav'),
    ('all', 'xnxx'),
    ('all', 'xvideos'),
]

root = Path('.')
apk_dir = root / 'apk'
apk_apk_dir = apk_dir / 'apk'

apk_dir.mkdir(exist_ok=True)
apk_apk_dir.mkdir(exist_ok=True)

print("1. Bumping extVersionCode by +1 for all adult (+18) extensions...")
for lang, name in ADULT_MODULES:
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

print("\n2. Building release APKs in batches...")
assemble_tasks = [f":src:{lang}:{name}:assembleRelease" for lang, name in ADULT_MODULES]
batch_size = 5
for i in range(0, len(assemble_tasks), batch_size):
    batch = assemble_tasks[i:i + batch_size]
    print(f"Building batch {i // batch_size + 1}: {batch}")
    cmd = [".\\gradlew.bat"] + batch + ["--no-daemon"]
    res = subprocess.run(cmd, shell=True)
    if res.returncode != 0:
        print(f"Warning: Batch returned exit code {res.returncode}")

print("\n3. Syncing release APKs across 3 paths...")
count = 0
for lang, name in ADULT_MODULES:
    bg = root / 'src' / lang / name / 'build.gradle'
    if not bg.exists():
        continue
    content = bg.read_text(encoding='utf-8')
    m_ver = re.search(r'extVersionCode\s*=\s*(\d+)', content)
    if not m_ver:
        continue
    
    # Locate built APK
    release_dir = root / 'src' / lang / name / 'build' / 'outputs' / 'apk' / 'release'
    if not release_dir.exists():
        continue
    
    apk_files = list(release_dir.glob('*.apk'))
    if not apk_files:
        continue
    
    built_apk = apk_files[0]
    
    # Target name in index
    pkg = f"aniyomi-{lang}.{name}"
    
    # Remove old version APKs
    for p in [root, apk_dir, apk_apk_dir]:
        for old in p.glob(f"{pkg}-v*.apk"):
            old.unlink()
            print(f"Removing old {old}")

    # Discover version string from build or default
    # Read index pattern filename or copy directly
    new_apk_name = f"{pkg}-v14.{m_ver.group(1)}.apk"
    
    shutil.copy2(built_apk, root / new_apk_name)
    shutil.copy2(built_apk, apk_dir / new_apk_name)
    shutil.copy2(built_apk, apk_apk_dir / new_apk_name)
    print(f"Updated {new_apk_name} across 3 paths")
    count += 1

print(f"\nTotal {count} adult APKs updated across 3 fallback paths!")

print("\n4. Rebuilding index manifests...")
import rebuild_index
rebuild_index.main()

print("\nCompleted bump and build for all adult extensions!")

