# Shared baseline and remediation

`setup.edn` is the requirement inventory; `assets/` holds the public configuration baseline. There is no automatic installer. Installing this Pi package registers `/dotfiles`, `/skill:dotfiles`, and `/prompt` only.

## Required Pi packages

Propose these commands for missing declarations; do not run them during audit:

```sh
pi install npm:pi-mcp-adapter
pi install npm:pi-web-access
pi install npm:@juicesharp/rpiv-ask-user-question
pi list
```

Then use `/reload` in the affected Pi session. MCP server configuration is machine-specific and separate; do not publish endpoints containing credentials. The audit checks personal settings declarations, not credentials, cache health, or runtime extension behavior. Object entries and pinned npm versions are accepted; project overrides and resource filters need review.

## Configuration deployment plan

For each missing/drifted file, show its exact bundled asset and target from setup.edn. First back up the target, review a local diff, merge rather than replacing unrelated settings, and describe how to restore the backup. Do not propose unconditional mass copy commands. Deploy shared shell fragments before adding hooks:

```sh
# Add once to ~/.zshrc after removing conflicting prompt/tool initialization:
. "$HOME/.config/my-pi/shell/zshrc"
# Add once to ~/.bashrc:
. "$HOME/.config/my-pi/shell/bashrc"
```

Bash login startup must source ~/.bashrc. Non-interactive/login PATH may need mise shims for exec-path-from-shell; decide in review instead of executing a login shell in the audit. Remove wtc and wt hooks from existing rc files. Do not import unrelated chezmoi worktree/editor helpers. The shell fragments retain Emacs aliases, pit, mise, fzf, zoxide, hindsight and build environment variables; no broot, direnv or opencode dependency is added.

## Emacs

The config targets Emacs **31+**, terminal-only, and declares packages through Elpaca/use-package. The two bundled init files contain the required configuration; no active site-lisp/EAF dependency is present. Do not import unused site-lisp trees, package sources, caches, custom.el approvals, history, bookmarks, project lists, local_only overrides or note contents.

The first ordinary Emacs launch can clone/build Elpaca and packages and create cache directories. That is an approved setup step, not an audit probe. Configure a daemon using the actual platform's startup mechanism, avoiding the existing compositor daemon if present. The probe explicitly uses `--alternate-editor=false` so it never starts a daemon as a fallback.

Optional integrations in the init are not additional mandatory tools: RustRover for Rust projects, kdlfmt for KDL formatting, external clipboard tools, private local_only config, and credentials for gptel. The gptel function reads an existing Pi auth file only when used; no auth file or key is bundled or read by the auditor.

## Herdr, reviewr and Ghostty

Reviewr comes from https://github.com/persiyanov/herdr-reviewr; the source machine had version 0.39.0. This is provenance, not a pin. Require Herdr >= 0.7.5 and install the plugin only after approval:

```sh
herdr plugin install persiyanov/herdr-reviewr
```

Do not copy plugins.json, downloaded plugin code/binaries, session state or absolute managed paths. Recreate registration through Herdr's installer. `assets/configs/herdr/reviewr.toml` is the separate reviewr theme config. Herdr's Emacs-tab action also needs jq. Do not invoke plugin actions or inspect/control live panes during audit.

Ghostty sends Ctrl-T sequences; Herdr uses the same prefix. cmd+8 opens scratch, cmd+9 opens/focuses Emacs, cmd+0 opens notes. The notes popup refers to `$HOME/.local/share/hldenote/_TODO.org`; adapt this reference locally without copying private notes. cmd+shift+enter sends prefix+u, for which no custom binding was established on the source machine; explicitly resolve that preference. Runtime shortcuts and terminal permissions need manual checks.

## Pi instructions and role selection

Restore TEAM.md and each role SYSTEM.md to the named ~/.pi/agent paths only through a reviewed setup step. They are not package resources and must not be injected automatically. The `pit` alias explicitly selects the team and orchestrator prompts. Role launches explicitly select team plus role using `--append-system-prompt`; preserve the prompts' exact model-resolution procedure and ask when a model is unavailable.

Global AGENTS.md is different: **presence is required, content equality is not**. If it exists and is non-empty, note that instructions may be adapted between machines and preserve it. If absent, install the bundled starting template after approval and adjust ~/repos/ for that machine. Also check for local global override/context files manually; the audit does not read arbitrary additional prompts.

The Herdr skill is included as a setup asset because the orchestrator requires it. It does not become a second automatically exposed package skill.
