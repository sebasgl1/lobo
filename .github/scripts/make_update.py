"""
Prepara los archivos de actualización para el Release:
- lobo.apk (la app completa)
- lobo-desde-vX.patch: parches desde las 3 versiones anteriores (solo si ahorran al menos 20%)
- update.json: lo que lee la app para saber si hay versión nueva y qué descargar
"""
import hashlib, json, os, re, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))
from delta import crear, aplicar

REPO = os.environ["GITHUB_REPOSITORY"]
gradle = open("app/build.gradle.kts").read()
name = re.search(r'versionName = "([^"]+)"', gradle).group(1)
code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
tag = f"v{name}"
new = open("lobo.apk", "rb").read()
base = f"https://github.com/{REPO}/releases/download/{tag}"

def gh(*args):
    return subprocess.run(["gh", "api", *args], check=True, capture_output=True).stdout

releases = json.loads(gh(f"repos/{REPO}/releases?per_page=15"))
prev = [r for r in releases if r["tag_name"] != tag and not r["draft"]
        and any(a["name"] == "lobo.apk" for a in r["assets"])][:3]

patches, files = [], ["lobo.apk", "update.json"]
for r in prev:
    asset = next(a for a in r["assets"] if a["name"] == "lobo.apk")
    old = gh("-H", "Accept: application/octet-stream", f"repos/{REPO}/releases/assets/{asset['id']}")
    if hashlib.sha256(old).hexdigest() == hashlib.sha256(new).hexdigest():
        continue
    p = crear(old, new)
    assert aplicar(old, p) == new
    print(f"{r['tag_name']} → {tag}: parche {len(p)/1e6:.2f} MB / completo {len(new)/1e6:.2f} MB")
    if len(p) > 0.8 * len(new):
        continue
    fname = f"lobo-desde-{r['tag_name']}.patch"
    open(fname, "wb").write(p)
    files.append(fname)
    patches.append({"from": r["tag_name"], "fromSha256": hashlib.sha256(old).hexdigest(),
                    "url": f"{base}/{fname}", "size": len(p), "sha256": hashlib.sha256(p).hexdigest()})

# Novedades: los commits desde la versión anterior
notes = ""
if prev:
    try:
        log = subprocess.run(["git", "log", "--pretty=%s", f"{prev[0]['tag_name']}..HEAD"],
                             check=True, capture_output=True, text=True).stdout
        lines = [re.sub(r"^\[skip ci\]\s*", "", l).strip() for l in log.splitlines()]
        lines = [l for l in lines if l and not l.lower().startswith(("pruebas:", "merge"))]
        notes = "\n".join("• " + l for l in lines[:12])
    except subprocess.CalledProcessError:
        pass

info = {"versionCode": code, "versionName": name, "notes": notes,
        "apk": {"url": f"{base}/lobo.apk", "size": len(new), "sha256": hashlib.sha256(new).hexdigest()},
        "patches": patches}
json.dump(info, open("update.json", "w"), ensure_ascii=False, indent=1)
with open(os.environ["GITHUB_ENV"], "a") as f:
    f.write("UPDATE_FILES<<EOF\n" + "\n".join(files) + "\nEOF\n")
print(json.dumps(info, ensure_ascii=False, indent=1))
