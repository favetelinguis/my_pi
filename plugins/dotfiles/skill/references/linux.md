# Linux profile

The baseline was curated from Arch Linux. On other distributions, shared config still applies but use that distribution's documented installation methods; do not translate Arch package names by guessing.

On Arch, proposed prerequisite commands can use `pacman -S` for available official packages such as git, jq, zsh, bash, mise and ghostty. Review packages before applying, and separate privileged installation from the read-only audit. Emacs must be version 31+ for this init; the source machine uses emacs-wayland 31.1. Toolbox may need an upstream or reviewed AUR installation depending on repositories; do not treat an AUR package as an official package.

After approving the complete mise config, `mise install` installs its declared tool policies. Latest policy is preserved, not pinned to the source machine's resolved version. Review mise's supply-chain cooldown exclusion for your hindsight repository before deploying.

Ghostty, Herdr and reviewr configs use ~/.config. Toolbox's launcher directory is ~/.local/share/JetBrains/Toolbox/scripts. IntelliJ IDEA is required; RustRover is optional. The shell baseline adds Toolbox scripts to PATH when present.

The source Niri configuration starts `emacs --daemon`; do not add a second daemon. On a machine without compositor-managed startup, propose a reviewed systemd user service using the actual Emacs path. No Niri config or desktop selection is imposed by this package.

For Wayland clipboard integration, wl-copy/wl-paste are optional enhancements; terminal OSC52 remains a fallback. Verify terminal key handling and reviewr manually after approved configuration changes.
