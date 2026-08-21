import zipfile, glob, sys, hashlib
ok = True
for f in sorted(glob.glob('app/libs/*.jar')):
    try:
        z = zipfile.ZipFile(f)
        bad = z.testzip()
        h = hashlib.sha256(open(f,'rb').read()).hexdigest()[:16]
        print(f'{f}: {len(z.namelist())} entries, testzip={bad or "OK"}, sha256={h}')
        if bad is not None: ok = False
    except Exception as e:
        ok = False
        print(f'{f}: CORRUPT ({e})')
sys.exit(0 if ok else 1)
