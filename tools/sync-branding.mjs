#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Propagates branding/product.json into files that cannot read it at build time
// (Tauri JSON config, static HTML). Gradle, Cargo and Vite read the JSON directly.
//   node tools/sync-branding.mjs          rewrite files
//   node tools/sync-branding.mjs --check  fail if files are out of sync (CI)
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const product = JSON.parse(readFileSync(resolve(root, "branding/product.json"), "utf8"));

const targets = {
  "desktop/src-tauri/tauri.conf.json": (text) => {
    const conf = JSON.parse(text);
    conf.productName = product.name;
    conf.identifier = product.desktopIdentifier;
    for (const window of conf.app.windows) window.title = product.name;
    return JSON.stringify(conf, null, 2) + "\n";
  },
  "desktop/index.html": (text) => text.replace(/<title>[^<]*<\/title>/, `<title>${product.name}</title>`),
};

const check = process.argv.includes("--check");
let stale = false;
for (const [rel, transform] of Object.entries(targets)) {
  const path = resolve(root, rel);
  const current = readFileSync(path, "utf8");
  const next = transform(current);
  if (current === next) continue;
  if (check) {
    console.error(`stale: ${rel}`);
    stale = true;
  } else {
    writeFileSync(path, next);
    console.log(`wrote ${rel}`);
  }
}
if (stale) {
  console.error("Branding is out of sync. Run `pnpm branding:sync`.");
  process.exit(1);
}
