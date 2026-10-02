# macOS profile

Shared tools and configurations are defined, but this profile has **not been exercised on a macOS machine**. Report native paths/permissions and daemon behavior as unknown until checked there. Requesting --platform macos on Linux must not produce false macOS compliance.

Install mise, Ghostty and JetBrains Toolbox using their documented upstream/macOS methods. If Homebrew is already the chosen package manager, verify current formula/cask names before proposing brew commands; do not make Homebrew a new mandatory preference. Install IntelliJ IDEA through Toolbox, with the idea launcher available to Emacs. Toolbox launcher scripts typically live under ~/Library/Application Support/JetBrains/Toolbox/scripts; verify actual versions/layout.

The init needs **Emacs 31+**, including TTY features; do not accept an older system Emacs. Propose a reviewed launchd user daemon definition with the actual binary path and avoid duplicate servers. A specific Emacs distribution and plist have not been chosen, so ask before applying one.

Keep the baseline ~/.config/ghostty/config and also inspect ~/Library/Application Support/com.mitchellh.ghostty/config for overriding settings. Global quick-terminal shortcuts may need Accessibility permissions and conflict with system shortcuts. Verify the actual cmd/ctrl key behavior through Ghostty and Herdr.

The shared shell config uses BSD ls -G instead of GNU --color flags and uses the macOS Toolbox scripts directory. The e alias falls back to the current TERM if xterm-direct is unavailable. macOS pbcopy/pbpaste provide native clipboard support. Verify that the selected Bash version supports the tool integrations; do not automatically change the login shell.

Global Pi AGENTS.md may differ from Linux and is not required to match the template. Shared team and role prompts remain explicitly selected assets, and model availability/authentication is a separate manual check.
