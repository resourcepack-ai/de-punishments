// Punishments' screens: the Dialog-style documents in dialogs-src/dialogs.json,
// drawn by Studio into the folders Dialog Engine keeps a drawn dialog in, and
// shipped in the jar.
//
//   node tools/make-dialogs.mjs [--studio https://studio.resourcepack.ai]
//
// Writes src/main/resources/drawn/punishments/<id>/ for each: dialog.yml (the
// document, which a server owner can edit with /de edit), .built (its hash, so
// Dialog Engine does not ask Studio to draw it again), dialogs.json and pack/
// (Studio's drawing, from the same /api/rpengine/build a hand edit is redrawn
// through), and files.txt beside them, which is how Dialog Engine finds them in
// the jar (installDrawn). Run it after changing a document, and bump the version.
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
// The `yaml` package: installed here, or else Studio's copy beside this repository.
const YAML = (() => {
  for (const from of [import.meta.url, join(here, "../../../studio/package.json")]) {
    try {
      return createRequire(from)("yaml");
    } catch {}
  }
  throw new Error("make-dialogs.mjs needs the yaml package: npm install yaml");
})();
const args = process.argv.slice(2);
const studio = (args.includes("--studio") ? args[args.indexOf("--studio") + 1] : "https://studio.resourcepack.ai").replace(/\/$/, "");
const NAMESPACE = "punishments";
const root = join(here, "../src/main/resources/drawn", NAMESPACE);
const sources = JSON.parse(readFileSync(join(here, "../dialogs-src/dialogs.json"), "utf8"));

const HEADER = [
  "One of Punishments' screens, drawn with a resource pack by Dialog Engine.",
  "Edit it with /de edit punishments:<name>, or by hand here and run /de reload:",
  "a changed file is redrawn by Studio (resourcepack.ai), which needs the internet.",
  "An edited screen is kept when Punishments updates; delete its folder to take",
  "the new version's. Its clicks run /punish and /punishments act ..., which",
  "Punishments answers, and the words on it come from messages.yml.",
];

rmSync(root, { recursive: true, force: true });
const listed = [];
for (const source of sources) {
  const spec = { style: "dialog", ...source.spec };
  const body = {
    client: "DialogEngine/punishments",
    kind: "dialog",
    label: `${NAMESPACE}:${source.id}`,
    minecraftVersion: "1.21.11",
    dialog: { path: `drawn/${NAMESPACE}/${source.id}`, key: source.id, spec },
  };
  const res = await fetch(`${studio}/api/rpengine/build`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "User-Agent": "DialogEngine/punishments" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${source.id}: ${res.status} ${text.slice(0, 400)}`);
  const pull = JSON.parse(text);
  const dir = join(root, source.id);
  mkdirSync(dir, { recursive: true });
  const yml = HEADER.map((l) => `# ${l}`).join("\n") + "\n" + YAML.stringify(pull.dialog.spec ?? spec, { lineWidth: 0 });
  writeFileSync(join(dir, "dialog.yml"), yml);
  writeFileSync(join(dir, ".built"), createHash("sha256").update(yml.replace(/\r\n/g, "\n"), "utf8").digest("hex"));
  writeFileSync(join(dir, "dialogs.json"), JSON.stringify(pull.dialog.built));
  listed.push(`${source.id}/dialog.yml`, `${source.id}/.built`, `${source.id}/dialogs.json`);
  for (const f of pull.files ?? []) {
    if (!/^pack\/[A-Za-z0-9._/-]+$/.test(f.path) || f.path.includes("..")) throw new Error(`refused ${f.path}`);
    const target = join(dir, f.path);
    mkdirSync(dirname(target), { recursive: true });
    let content = f.encoding === "base64" ? Buffer.from(f.content, "base64") : f.content;
    if (f.path === "pack/pack.mcmeta") {
      const mcmeta = JSON.parse(content.toString());
      mcmeta.pack.description = `Punishments: ${source.id} (Minecraft ${body.minecraftVersion})`;
      content = JSON.stringify(mcmeta, null, 2);
    }
    writeFileSync(target, content);
    listed.push(`${source.id}/${f.path}`);
  }
  console.log(`${source.id}: ${pull.dialog.built.length} page(s), ${(pull.files ?? []).length} pack files`);
}
writeFileSync(join(root, "files.txt"), listed.join("\n") + "\n");
