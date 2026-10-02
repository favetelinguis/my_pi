import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const skillDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "skill");
const help = "Usage: /dotfiles [check|plan|help] [linux|macos]\n" +
  "Default: inspect this machine and produce a compliance plan. No setup changes are applied.\n" +
  "An explicit platform selects the desired profile; it does not pretend the host is that platform.";

export default function dotfiles(pi: ExtensionAPI) {
  // Registration only: no file reads, audit, or prompt injection at startup.
  pi.registerCommand("dotfiles", {
    description: "Audit computer setup and plan compliance (explicit-only, no changes)",
    getArgumentCompletions: (prefix) => {
      const items = ["check", "plan", "help", "check linux", "check macos", "plan linux", "plan macos"]
        .filter((value) => value.startsWith(prefix))
        .map((value) => ({ value, label: value }));
      return items.length ? items : null;
    },
    handler: async (args, ctx) => {
      const [mode = "plan", profile, ...extra] = args.trim().split(/\s+/).filter(Boolean);
      if (!["check", "plan", "help"].includes(mode) ||
          (profile !== undefined && !["linux", "macos"].includes(profile)) || extra.length) {
        ctx.ui.notify(help, "warning");
        return;
      }
      if (mode === "help") {
        ctx.ui.notify(help, "info");
        return;
      }
      if (!ctx.isIdle()) {
        ctx.ui.notify("Wait for the current turn to finish before running /dotfiles.", "warning");
        return;
      }
      try {
        const markdown = await readFile(resolve(skillDirectory, "SKILL.md"), "utf8");
        const instructions = markdown.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, "");
        pi.sendUserMessage(
          `<skill name="dotfiles" location=${JSON.stringify(skillDirectory)}>\n${instructions}\n</skill>\n\n` +
          `Explicit user request: ${mode === "check" ? "audit findings only" : "audit findings and a compliance plan"}. ` +
          `Desired platform profile: ${profile ?? "detect the actual host"}. ` +
          "Do not apply changes. Resolve all bundled paths from the skill location above.",
        );
      } catch {
        ctx.ui.notify("Cannot load the bundled dotfiles skill. Check the package installation and /reload.", "error");
      }
    },
  });
}
