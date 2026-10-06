#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Generates platform token files from design/tokens.json.
//   node tools/gen-tokens.mjs          write generated files
//   node tools/gen-tokens.mjs --check  fail if generated files are stale (CI)
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const tokens = JSON.parse(readFileSync(resolve(root, "design/tokens.json"), "utf8"));

const KOTLIN_OUT = "mobile/core/designsystem/src/main/kotlin/org/bastion/core/designsystem/tokens/Tokens.kt";
const CSS_OUT = "desktop/src/lib/theme/tokens.css";
const HEADER = "GENERATED from design/tokens.json by tools/gen-tokens.mjs. Do not edit.";

const HEX = /^#[0-9A-Fa-f]{6}$/;

function assertTokens(t) {
  for (const [name, value] of Object.entries(t.color)) {
    if (!HEX.test(value)) throw new Error(`color.${name} must be #RRGGBB, got ${value}`);
  }
  if (Object.keys(t.typeScale).length > 5) throw new Error("type scale is limited to 5 sizes");
  for (const [name, s] of Object.entries(t.typeScale)) {
    if (![400, 500, 600].includes(s.weight)) throw new Error(`typeScale.${name}: weight must be 400/500/600`);
  }
  for (const [name, ms] of Object.entries(t.motion)) {
    if (name !== "easing" && (ms < 120 || ms > 200)) throw new Error(`motion.${name} must be within 120-200 ms`);
  }
}

// WCAG 2.x relative luminance / contrast, used to enforce AA on text tokens.
function luminance(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const lin = c.map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}
export function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

function assertContrast(t) {
  const c = t.color;
  const pairs = [
    ["textPrimary", "background"],
    ["textPrimary", "surfaceRaised"],
    ["textSecondary", "background"],
    ["textSecondary", "surfaceRaised"],
    ["accent", "background"],
    ["onAccent", "accent"],
    ["danger", "background"],
    ["success", "background"],
    ["warning", "background"],
  ];
  // textTertiary is reserved for disabled controls and non-text separators (WCAG 1.4.3
  // exempts inactive components); it must still reach 3:1 against the background.
  if (contrast(c.textTertiary, c.background) < 3) throw new Error("textTertiary/background < 3:1");
  for (const [fg, bg] of pairs) {
    const ratio = contrast(c[fg], c[bg]);
    if (ratio < 4.5) throw new Error(`contrast ${fg}/${bg} = ${ratio.toFixed(2)} < 4.5 (WCAG AA)`);
  }
}

const camelToKebab = (s) => s.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
const cap = (s) => s[0].toUpperCase() + s.slice(1);

function kotlin(t) {
  const lines = [];
  lines.push(`// ${HEADER}`);
  lines.push("@file:Suppress(\"MagicNumber\")");
  lines.push("");
  lines.push("package org.bastion.core.designsystem.tokens");
  lines.push("");
  lines.push("import androidx.compose.ui.graphics.Color");
  lines.push("import androidx.compose.ui.unit.dp");
  lines.push("");
  lines.push("object ColorTokens {");
  for (const [k, v] of Object.entries(t.color)) lines.push(`    val ${cap(k)} = Color(0xFF${v.slice(1).toUpperCase()})`);
  lines.push("}");
  lines.push("");
  lines.push("object SpacingTokens {");
  for (const [k, v] of Object.entries(t.spacing)) lines.push(`    val ${cap(k)} = ${v}.dp`);
  lines.push("}");
  lines.push("");
  lines.push("object RadiusTokens {");
  for (const [k, v] of Object.entries(t.radius)) lines.push(`    val ${cap(k)} = ${v}.dp`);
  lines.push("}");
  lines.push("");
  lines.push("object BorderTokens {");
  lines.push(`    val Width = ${t.border.width}.dp`);
  lines.push("}");
  lines.push("");
  lines.push("data class TypeToken(val size: Float, val lineHeight: Float, val weight: Int, val tracking: Float)");
  lines.push("");
  lines.push("object TypeScaleTokens {");
  for (const [k, s] of Object.entries(t.typeScale)) {
    lines.push(`    val ${cap(k)} = TypeToken(size = ${s.size}f, lineHeight = ${s.lineHeight}f, weight = ${s.weight}, tracking = ${s.tracking}f)`);
  }
  lines.push("}");
  lines.push("");
  lines.push("object MotionTokens {");
  for (const [k, v] of Object.entries(t.motion)) {
    if (k === "easing") lines.push(`    val Easing = floatArrayOf(${v.map((x) => `${x}f`).join(", ")})`);
    else lines.push(`    const val ${cap(k)}Millis = ${v}`);
  }
  lines.push("}");
  lines.push("");
  lines.push("object SizeTokens {");
  lines.push(`    val TouchTargetMin = ${t.touchTarget.min}.dp`);
  lines.push("}");
  lines.push("");
  lines.push("object FontTokens {");
  lines.push(`    const val SANS = "${t.font.sans}"`);
  lines.push(`    const val MONO = "${t.font.mono}"`);
  lines.push("}");
  return lines.join("\n") + "\n";
}

function css(t) {
  const out = [`/* ${HEADER} */`, ":root {"];
  for (const [k, v] of Object.entries(t.color)) out.push(`  --color-${camelToKebab(k)}: ${v.toLowerCase()};`);
  for (const [k, v] of Object.entries(t.spacing)) out.push(`  --space-${k}: ${v}px;`);
  for (const [k, v] of Object.entries(t.radius)) out.push(`  --radius-${k}: ${v}px;`);
  out.push(`  --border-width: ${t.border.width}px;`);
  out.push(`  --font-sans: "${t.font.sans}", system-ui, sans-serif;`);
  out.push(`  --font-mono: "${t.font.mono}", ui-monospace, monospace;`);
  for (const [k, s] of Object.entries(t.typeScale)) {
    out.push(`  --type-${k}-size: ${s.size}px;`);
    out.push(`  --type-${k}-line-height: ${s.lineHeight}px;`);
    out.push(`  --type-${k}-weight: ${s.weight};`);
    out.push(`  --type-${k}-tracking: ${s.tracking}px;`);
  }
  for (const [k, v] of Object.entries(t.motion)) {
    if (k === "easing") out.push(`  --motion-easing: cubic-bezier(${v.join(", ")});`);
    else out.push(`  --motion-${k}: ${v}ms;`);
  }
  out.push(`  --touch-target-min: ${t.touchTarget.min}px;`);
  out.push("}");
  out.push("");
  out.push("@media (prefers-reduced-motion: reduce) {");
  out.push("  :root {");
  for (const k of Object.keys(t.motion)) if (k !== "easing") out.push(`    --motion-${k}: 0ms;`);
  out.push("  }");
  out.push("}");
  return out.join("\n") + "\n";
}

function main() {
  assertTokens(tokens);
  assertContrast(tokens);
  const check = process.argv.includes("--check");
  const outputs = { [KOTLIN_OUT]: kotlin(tokens), [CSS_OUT]: css(tokens) };
  let stale = false;
  for (const [rel, content] of Object.entries(outputs)) {
    const path = resolve(root, rel);
    const current = existsSync(path) ? readFileSync(path, "utf8") : null;
    if (check) {
      if (current !== content) {
        console.error(`stale: ${rel}`);
        stale = true;
      }
    } else if (current !== content) {
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, content);
      console.log(`wrote ${rel}`);
    }
  }
  if (stale) {
    console.error("Design tokens are out of date. Run `pnpm tokens:gen`.");
    process.exit(1);
  }
}

main();
