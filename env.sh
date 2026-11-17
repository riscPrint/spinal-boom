export JAVA_HOME="$HOME/opt/jdk21"  # Temurin 21.0.13+9 (verified); was Zulu 21.0.7, see risc-v-workstation TODO
export PATH="$JAVA_HOME/bin:$HOME/opt/oss-cad-suite/bin:$PATH"
# Use a cached Sail installation when available. Do not acquire on shell setup.
if command -v "${RVW:-rvw}" >/dev/null 2>&1; then
  if _boom_sail="$("${RVW:-rvw}" doctor --resolve sail 2>/dev/null)"; then
    export PATH="$(dirname "$_boom_sail"):$PATH"
  fi
  unset _boom_sail
fi
