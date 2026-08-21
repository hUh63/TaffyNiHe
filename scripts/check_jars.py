import zipfile, glob, sys
ok = True
for f in sorted(glob.glob('app/libs/*.jar')):
    try:
        z = zipfile.ZipFile(f)
        print(f'{f}: OK ({len(z.namelist())} entries)')
    except Exception as e:
        ok = False
        print(f'{f}: CORRUPT ({e})')
sys.exit(0 if ok else 1)
