# Shared by interactive Bash and Zsh. Sourced after mise activation so installed tools are on PATH.
case "$(uname -s)" in
    Darwin)
        alias ls='ls -G'
        alias ll='ls -lhaG'
        toolbox_scripts="$HOME/Library/Application Support/JetBrains/Toolbox/scripts"
        ;;
    *)
        alias ls='ls --color=auto'
        alias ll='ls -lha --color=auto'
        toolbox_scripts="$HOME/.local/share/JetBrains/Toolbox/scripts"
        ;;
esac
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) export PATH="$HOME/.local/bin:$PATH" ;;
esac
if [ -d "$toolbox_scripts" ]; then
    case ":$PATH:" in
        *":$toolbox_scripts:"*) ;;
        *) export PATH="$PATH:$toolbox_scripts" ;;
    esac
fi
unset toolbox_scripts

alias ec='emacsclient -c'
# Preserve the current terminal-color fallback.
alias e='TERM=$(infocmp xterm-direct >/dev/null 2>&1 && echo xterm-direct || echo "$TERM") emacsclient -nw'
alias ecn='emacsclient -cn'
alias pit='pi --append-system-prompt "$HOME/.pi/agent/team/TEAM.md" --append-system-prompt "$HOME/.pi/agent/orchestrator/SYSTEM.md" --model openai/gpt-6.1-sol --thinking low'
export EDITOR='emacsclient -nw'
export VISUAL="$EDITOR"
export GIT_EDITOR="$EDITOR"
export LIBRARY_PATH="$HOME/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
export CPATH="$HOME/include"
