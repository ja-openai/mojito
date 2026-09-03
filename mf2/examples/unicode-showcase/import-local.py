"""Create a separate local showcase. Refuses to overwrite an existing repository."""

import json
import os
import pathlib
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
LOCAL_USER = os.environ.get("MOJITO_LOCAL_USER", "admin")
DATA = json.loads(pathlib.Path(__file__).with_name("dataset.json").read_text())


def request(path, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-forwarded-user": LOCAL_USER},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{path}: HTTP {error.code}: {error.read().decode()}") from error


def wait(task):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = request(f"/api/pollableTasks/{task['id']}")
        if result.get("errorMessage"):
            raise RuntimeError(result["errorMessage"])
        if result.get("isAllFinished", result.get("allFinished", False)):
            return result
        time.sleep(0.5)
    raise TimeoutError(f"Import task {task['id']} did not finish; inspect before retrying")


existing = request("/api/repositories")
if any(repo["name"] == DATA["name"] for repo in existing):
    raise SystemExit(f"Repository {DATA['name']} already exists; leaving it untouched.")
locales = {locale["bcp47Tag"]: locale for locale in request("/api/locales")}
repo = request("/api/repositories", {
    "name": DATA["name"], "description": DATA["description"],
    "sourceLocale": locales[DATA["sourceLocale"]], "checkSLA": False,
    "assetIntegrityCheckers": [],
    "repositoryLocales": [
        {"locale": locales[locale], "toBeFullyTranslated": locale == "fr"}
        for locale in DATA["locales"]
    ],
})
print(f"Created repository {repo['id']}: {repo['name']}", flush=True)
asset = request("/api/virtualAssets", {"repositoryId": repo["id"], "path": DATA["assetPath"]})
print(f"Created asset {asset['id']}", flush=True)
units = [
    {"name": message["id"], "content": message["source"],
     "comment": f"{message['title']}\nProvenance: {message['provenance']['kind']}\n{message['provenance']['url']}\n{message['notes']}"}
    for message in DATA["messages"]
]
wait(request(f"/api/virtualAssets/{asset['id']}/textUnits", units))
print(f"Imported {len(units)} source messages", flush=True)
targets = [
    {"repositoryName": repo["name"], "assetPath": DATA["assetPath"],
     "name": unit["name"], "comment": unit["comment"],
     "targetLocale": locale, "target": target,
     "targetComment": "Unicode showcase sample; review before any production use. "
     + " ".join(message.get("knownLimitations", [])),
     "status": "REVIEW_NEEDED", "includedInLocalizedFile": True}
    for unit, message in zip(units, DATA["messages"])
    for locale, target in message["translations"].items()
]
wait(request("/api/textunitsBatch", {"integrityCheckSkipped": False, "textUnits": targets}))
print(f"Imported {len(targets)} translations as REVIEW_NEEDED", flush=True)
rows = request("/api/textunits/search-hybrid", {
    "repositoryIds": [repo["id"]], "localeTags": DATA["locales"],
    "pluralFormFiltered": True, "pluralFormExcluded": False, "limit": 100, "offset": 0,
})
assert "results" in rows, rows
for message in DATA["messages"]:
    for locale, target in message["translations"].items():
        matching = [r for r in rows["results"] if r["name"] == message["id"] and r["targetLocale"] == locale]
        assert len(matching) == 1, (message["id"], locale, matching)
        row = matching[0]
        assert row["source"] == message["source"] and row["target"] == target, row["name"]
        assert row["status"] == "REVIEW_NEEDED", row
        assert row.get("messageFormat") == "MF2", row
state = {"repositoryId": repo["id"], "assetId": asset["id"], "rows": [
    {key: row.get(key) for key in ["tmTextUnitId", "name", "targetLocale", "messageFormat", "status"]}
    for row in rows["results"] if row.get("target") is not None
]}
path = pathlib.Path("/tmp/mojito-unicode-showcase-import.json")
path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
print(f"Verified stored source, target, MF2 metadata, and draft status. IDs: {path}", flush=True)
print(json.dumps(state, ensure_ascii=False, indent=2))
