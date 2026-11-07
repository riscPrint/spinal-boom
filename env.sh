export JAVA_HOME="$HOME/opt/jdk21"  # Temurin 21.0.13+9 (verified); was Zulu 21.0.7, see risc-v-workstation TODO
_boom_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export PATH="$JAVA_HOME/bin:$HOME/opt/oss-cad-suite/bin:$_boom_root/../ref/sail/sail-riscv-Mac-arm64/bin:$PATH"
unset _boom_root
