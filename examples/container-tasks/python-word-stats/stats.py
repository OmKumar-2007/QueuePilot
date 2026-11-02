"""QueuePilot container task: word statistics.

Contract: read /work/input.json, write /work/output.json, print "PROGRESS <0-100> <message>" lines.
Runs without network, as an unprivileged user, on a read-only root filesystem.
"""
import collections
import json
import re

with open("/work/input.json", encoding="utf-8") as f:
    task_input = json.load(f)

text = task_input.get("text", "")
lines = text.splitlines() or [""]
counts = collections.Counter()
for i, line in enumerate(lines, 1):
    counts.update(re.findall(r"[a-z0-9']+", line.lower()))
    if i % max(1, len(lines) // 10) == 0:
        print(f"PROGRESS {int(100 * i / len(lines))} processed {i} lines", flush=True)

result = {
    "wordCount": sum(counts.values()),
    "uniqueWords": len(counts),
    "topWords": [{"word": w, "count": c} for w, c in counts.most_common(int(task_input.get("topN", 5)))],
}
with open("/work/output.json", "w", encoding="utf-8") as f:
    json.dump(result, f)
print("PROGRESS 100 done", flush=True)
