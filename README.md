# my_pi

My personal [Pi](https://pi.dev) package: extensions, prompts, and a `dotfiles` skill that aligns a computer with my baseline config.

## Install

```sh
pi install git:github.com/favetelinguis/my_pi
```

Then `/reload`. To update, run `pi update --extensions`. To remove, run `pi remove git:github.com/favetelinguis/my_pi`.

## Resources

| Command | What it does |
|---|---|
| `/skill:dotfiles` | Diff this computer against the baseline, then apply the changes you approve (with backups) |
| `/skill:dotfiles check` | Report differences only |
| `/prompt <rough request>` | Interview me and rewrite a request as a self-contained coding-agent prompt |

The skill is explicit-only, so it never loads automatically.

## Baseline

`skills/dotfiles/home/` mirrors `$HOME`. A leading `dot_` means `.`, as in chezmoi (`dot_emacs.d/init.el` → `~/.emacs.d/init.el`). Tools, Pi packages and per-file rules are in `skills/dotfiles/SKILL.md`.

To change the baseline, edit the file under `home/` and commit, or let the skill promote a local file into this clone (`~/repos/my_pi`) and commit that.

## Adding an extension

Add `extensions/<name>.ts`, list it in `package.json` under `pi.extensions`, and test it with `pi -e ./extensions/<name>.ts`. For type checking, add `@earendil-works/pi-coding-agent` and `typescript` as dev dependencies.
