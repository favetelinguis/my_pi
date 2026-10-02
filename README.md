# my_pi

Personal plugins for [Pi](https://pi.dev), plus a curated **Linux/macOS computer setup**. This is a Git-installable Pi package, not a home-directory snapshot or an automatic machine installer.

## Install

Requires Pi **0.87.1 or newer**. Review the repository before loading its extension.

```sh
pi install git:github.com/favetelinguis/my_pi
```

Restart Pi, or enter `/reload` in an existing session. Then:

```text
/dotfiles
```

This checks alignment with the desired setup and asks the agent to produce a compliance plan. **It does not apply that plan.** The package also exposes `/prompt`, the existing request-to-coding-prompt interview template. There is no `/plan` template.

### Package management

```sh
pi list
pi update git:github.com/favetelinguis/my_pi
pi remove git:github.com/favetelinguis/my_pi
```

For project-local installation:

```sh
pi install --local git:github.com/favetelinguis/my_pi
```

Project resources load after Pi project trust is granted. Use `pi config` to enable/disable individual resources (`pi config --local` for project overrides). On Pi 0.87.1, `pi update --extensions` updates all configured packages; plain `pi update` updates Pi itself.

For reproducible installation, append a Git tag or commit: `git:github.com/favetelinguis/my_pi@<ref>`. A pinned ref does not advance to newer commits when updated.

## Commands

| Command | Result |
|---|---|
| `/dotfiles` | Audit this computer and produce a prioritized compliance plan |
| `/dotfiles check` | Audit findings and limitations only |
| `/dotfiles plan` | Audit and compliance plan, same default workflow |
| `/dotfiles help` | Usage without starting a model turn |
| `/dotfiles check linux` | Explicit Linux profile |
| `/dotfiles plan macos` | Explicit macOS profile |
| `/skill:dotfiles` | Pi's native explicit skill command |
| `/prompt <rough request>` | Interview and rewrite a request as a self-contained coding-agent prompt |

A requested profile is not a pretend operating system: selecting macOS on a Linux host yields **unknown** checks rather than false macOS compliance.

### Explicit-only loading

`SKILL.md` declares `disable-model-invocation: true`. Pi does not advertise this skill to the model or automatically select it. The small TypeScript extension registers `/dotfiles` at startup, but reads the skill only when you invoke the command. It adds no tools, background processes, session hooks, audits, or persistent system-prompt injection.

As with any invoked skill, its instructions remain in that conversation's history; explicit-only does not erase previous turns. `/new` gives you a fresh conversation.

## Desired computer setup

The initial baseline was reviewed from an Arch Linux machine, with shared requirements and macOS guidance. Native macOS behavior still needs verification on a Mac.

| Area | Managed requirements |
|---|---|
| **Herdr** | mise installation, >=0.7.5 compatibility, current config and Ctrl-T prefix |
| **reviewr** | `persiyanov/herdr-reviewr`, enabled registration/binary, toggle binding and `github-light` theme |
| **Emacs** | **31+**, current terminal-only `init.el`/`early-init.el`, Elpaca declarations, client and daemon workflow |
| **JetBrains** | Toolbox and IntelliJ IDEA, with `idea` launcher; no license or account state |
| **Ghostty** | Current Modus theme, font/cell settings, quick terminal and Herdr/clipboard shortcuts |
| **mise** | The complete current config, including every tool declaration and setting |
| **Shells** | Bash/Zsh prompts imported once from chezmoi, Emacs aliases, `pit`, mise/fzf/zoxide/hindsight and build environment |
| **Pi** | Required npm packages, global instructions, TEAM.md, all five role prompts, supporting Herdr skill and `/prompt` |

### All mise tools

The canonical [`mise/config.toml`](plugins/dotfiles/skill/assets/configs/mise/config.toml) declares:

- Node, Java, Maven, GitHub CLI.
- Clojure, clj-kondo, clojure-lsp, cljfmt, Babashka, Leiningen.
- Ruff, ty, uv, Prettier, tokei.
- hindsight, Herdr, fzf, ripgrep (`exe = "rg"`), fd, zoxide.
- `npm:@earendil-works/pi-coding-agent`.

Preserve `"latest"` policies, the Leiningen `2.12.0` pin, exact source identifiers, and the hindsight `minimum_release_age_excludes` setting. An installed `latest` alias is **not proof of upstream freshness**; the audit makes no network requests. Review the cooldown exclusion before deploying.

### Required Pi packages

The setup requires these personal package declarations:

```sh
pi install npm:pi-mcp-adapter
pi install npm:pi-web-access
pi install npm:@juicesharp/rpiv-ask-user-question
```

These are setup steps, not actions performed by `/dotfiles` or automatically run when installing `my_pi`. The auditor accepts string/object declarations and pinned npm versions. It checks settings declarations, not package-cache health or effective runtime loading; verify with `pi list` and `/reload`. Configure MCP servers separately on each machine without publishing secrets.

### Shell configuration

The legacy `favetelinguis/dotfiles` repository remains independent. Only the prompt definitions were imported from its chezmoi shell template:

- Zsh: blue working directory, newline, job-count indicator.
- Bash: blue working directory, newline, standard user/root prompt character.

The curated shell files are **fragments**, not replacements for complete existing rc files. Approved setup deploys them under `~/.config/my-pi/shell/`, then merges these hooks once:

```sh
# ~/.zshrc
. "$HOME/.config/my-pi/shell/zshrc"
# ~/.bashrc
. "$HOME/.config/my-pi/shell/bashrc"
```

Remove competing old prompt/tool initialization before adding the hooks, and ensure Bash login startup sources `.bashrc`. BSD/GNU `ls` differences and Toolbox launcher paths are handled in the shared fragment. No `wtc`, `wt` hooks or required Worktrunk plugin. No broot, direnv, opencode or unrelated chezmoi helpers are imported.

The `pit` alias explicitly selects TEAM.md and the orchestrator SYSTEM.md. Its provider/model availability must be checked on each machine, without silently substituting models.

### Global instructions and role prompts

The setup assets include:

```text
assets/pi/AGENTS.md
assets/pi/team/TEAM.md
assets/pi/orchestrator/SYSTEM.md
assets/pi/planner/SYSTEM.md
assets/pi/implementer/SYSTEM.md
assets/pi/reviewer/SYSTEM.md
assets/pi/tester/SYSTEM.md
assets/pi/herdr.md
```

Global **`~/.pi/agent/AGENTS.md` must exist and be non-empty, but its content may differ between machines**. The audit notes that explicitly instead of reporting content drift. If missing, the bundled file is a starting template to install and adapt after approval; never overwrite an existing file just to match it.

Team and role prompts are setup assets, not automatically loaded package resources. Restore them to their corresponding `~/.pi/agent/` paths after reviewing the compliance plan, then select them explicitly:

```sh
pi --append-system-prompt "$HOME/.pi/agent/team/TEAM.md" \
   --append-system-prompt "$HOME/.pi/agent/planner/SYSTEM.md"
```

The supporting Herdr skill is restored to `~/.pi/agent/skills/herdr.md`. The team/orchestrator rules govern future team runs; reading their bundled assets does not change the auditing agent's role.

## Audit implementation and safety

The deterministic auditor is written in **Babashka/Clojure**. Run it directly from a checkout:

```sh
bb plugins/dotfiles/skill/scripts/audit.clj
bb plugins/dotfiles/skill/scripts/audit.clj --json
bb plugins/dotfiles/skill/scripts/audit.clj --platform macos --json
```

The script works from any directory when given its absolute path. It prints to stdout and does not save reports. `--home <path>` audits another home/fixture and implies `--files-only`; `--files-only` skips PATH and executable probes. `--help` explains the interface. Findings do not cause a nonzero exit; invalid arguments or a broken specification exit with code 2.

Statuses are **compliant**, **missing**, **drifted**, **unknown**, and **not-applicable**. Reports include desired/observed states, paths/digests, priorities, remediation guidance and limitations. The model turns that evidence into a reviewed plan with proposed commands/edits, prerequisites, backups, rollback, privileges and verification.

The auditor:

- Checks declared files, mise installation directories/PATH, application paths, Pi package declarations, reviewr registration/binary, and shell source hooks.
- Uses only three fixed executable probes: `emacs --version`, `herdr --version`, and a pure `emacsclient` PID/version query. Each has a five-second timeout; the client uses `--alternate-editor=false` and does not start a daemon.
- Never sources shell configs, loads Emacs init, invokes Herdr session controls/actions, installs software, uses sudo, starts services or accesses external APIs.
- Limits inspected text files to 2 MiB and does not print raw config contents. Text comparisons ignore CRLF, trailing whitespace, portable home prefixes and full-line `#` comments where declared. Formatting/local additions can still report drift: review and merge, do not blindly replace.
- Treats live UI integrations, package startup, later shell overrides, native macOS paths/permissions, model availability and unusual application layouts as unverified/manual checks.

No credentials, auth files, histories, caches, private notes, machine reports, license state or downloaded plugin code are bundled. The Emacs config contains a function that uses an existing Pi credential when gptel is invoked; no credential is copied or read by the auditor. Its first ordinary startup bootstraps packages over the network, so do not execute it during an audit.

**This is a workflow constraint, not a security sandbox.** Pi and extensions retain the permissions of their user, and a model can deviate from instructions. Review source, restrict tools/OS permissions where needed, and approve setup application separately. For deterministic checks without model-driven actions, run the Babashka script directly.

## Repository layout

```text
package.json                      Explicit Pi resource manifest
prompts/prompt.md                  /prompt command
plugins/dotfiles/extension.ts      Lazy /dotfiles entry point
plugins/dotfiles/skill/SKILL.md     Explicit-only instructions
plugins/dotfiles/skill/setup.edn    Requirements and remediation
plugins/dotfiles/skill/assets/     Reviewed configuration and Pi prompt assets
plugins/dotfiles/skill/references/ Shared/Linux/macOS guidance
plugins/dotfiles/skill/scripts/    Read-only auditor
tests/                            Clojure fixtures and real Pi RPC smoke test
```

### Adding a plugin or changing the baseline

1. Add a plugin under `plugins/<name>/`.
2. List only its intended resources in `package.json`'s `pi` manifest. Do not expose setup assets through broad discovery globs.
3. Add explicit-only frontmatter where appropriate and document its command.
4. Update tests and this README.

For setup changes, edit `setup.edn` and the corresponding public assets. The mise TOML is the single source of version policies; the EDN maps each tool to its install directory and executable. Its small parser supports the flat declaration forms currently used; tests reject inventory/config disagreement rather than silently skipping new tools. Review sensitive data before committing.

## Development and verification

```sh
npm ci --ignore-scripts
npm run check
bb test
bb test-pi
pi -e ./plugins/dotfiles/extension.ts
```

- `npm run check`: TypeScript type checking.
- `bb test`: deterministic fixture tests for drift, missing/oversized files, machine-specific AGENTS.md, npm pins/object declarations, excluded shell commands, platform mismatch, version compatibility and read-only behavior.
- `bb test-pi`: real `pi install`/`pi remove` and RPC command discovery in a temporary agent directory. A loopback-only fake model captures transmitted context to verify ordinary conversations do not advertise/load the skill and explicit `/dotfiles check` does. No real API keys, providers or user settings are used. To test the published Git source instead of the local checkout, run `MY_PI_TEST_SOURCE=git:github.com/favetelinguis/my_pi bb test-pi` (this intentionally uses the network to clone/install the package).

Testing fixtures is not proof of live macOS behavior; a Mac must verify the documented native checks. Pi 0.87.1 is the tested version. This repository contains personal configuration; no redistribution license is granted (`UNLICENSED`).
