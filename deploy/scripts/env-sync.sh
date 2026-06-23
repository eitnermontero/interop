#!/usr/bin/env bash
# Sync .env (o un .env.<variant>) con .env.example para el environment elegido,
# preservando valores que ya hayas overridado.
#
# Usage:
#   bash deploy/scripts/env-sync.sh <env>                    # default: .env
#   bash deploy/scripts/env-sync.sh <env> -f .env.alpha      # archivo custom
#   bash deploy/scripts/env-sync.sh <env> -y                 # non-interactive
#   bash deploy/scripts/env-sync.sh -h                       # help
#
# <env>  = development | production | tools
# -f|--file <name>  archivo destino (default: .env). El base sigue siendo .env.example.
#
# Behavior:
#   First run (no .env):   copies .env.example -> .env as-is.
#                          No confirmation needed.
#
#   Subsequent runs:       walks .env.example line-by-line. For each KEY=value
#                          it finds in the template:
#                            - if the user already has KEY in .env,
#                              keep the user's value
#                            - otherwise, copy the line from .env.example
#                          Orphaned keys (in your .env but NOT in .env.example)
#                          are preserved at the bottom with a warning header.
#                          A diff is shown; you must confirm [y/N] before apply.
#
# Backups: .env -> .env.bak.<timestamp> on every apply.
set -uo pipefail

ASSUME_YES=0
ENV_NAME=""
TARGET_FILE=".env"

usage() {
  sed -n '2,/^set/p' "$0" | sed 's/^# \?//' | sed '$d'
}

while [ $# -gt 0 ]; do
  case "$1" in
    -y|--yes) ASSUME_YES=1; shift ;;
    -f|--file)
      if [ -z "${2:-}" ]; then
        echo "Missing value for $1" >&2; exit 2
      fi
      TARGET_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    development|production|tools) ENV_NAME="$1"; shift ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 <development|production|tools> [-f|--file <name>] [-y|--yes]" >&2
      exit 2
      ;;
  esac
done

if [ -z "$ENV_NAME" ]; then
  echo "Missing required argument: <environment>" >&2
  echo "Usage: $0 <development|production|tools> [-f|--file <name>] [-y|--yes]" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/../$ENV_NAME"

if [ ! -d "$TARGET_DIR" ]; then
  echo "Environment dir not found: $TARGET_DIR" >&2
  exit 1
fi

cd "$TARGET_DIR"

EXAMPLE=".env.example"
TARGET="$TARGET_FILE"

# Safety: el target no puede ser el mismo template.
if [ "$TARGET" = "$EXAMPLE" ]; then
  echo "ERROR: target file no puede ser '$EXAMPLE' (base template)." >&2
  exit 2
fi

GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'
ok()    { printf "  ${GREEN}✓${NC} %s\n" "$1"; }
warn()  { printf "  ${YELLOW}!${NC} %s\n" "$1"; }
bad()   { printf "  ${RED}✗${NC} %s\n" "$1" >&2; }
info()  { printf "\n${BOLD}%s${NC}\n" "$1"; }

if [ ! -f "$EXAMPLE" ]; then
  bad "$EXAMPLE not found in $TARGET_DIR"
  exit 1
fi

info "Environment: $ENV_NAME ($TARGET_DIR)"

if [ ! -f "$TARGET" ]; then
  info "First-time setup"
  printf "  Va a crear ${BOLD}$TARGET_DIR/$TARGET${NC} a partir de ${BOLD}$EXAMPLE${NC}.\n"
  if [ "$ASSUME_YES" != "1" ]; then
    echo
    printf "${BOLD}Apply?${NC} [y/N] "
    read -r answer </dev/tty || answer=""
    case "$answer" in
      y|Y|yes|YES) ;;
      *)
        printf "\n${YELLOW}Aborted.${NC}\n"
        exit 0
        ;;
    esac
  fi
  cp "$EXAMPLE" "$TARGET"
  ok "created $TARGET from $EXAMPLE"
  echo
  printf "  Next: open ${BOLD}$TARGET_DIR/$TARGET${NC} y ajusta los valores.\n"
  exit 0
fi

info "Syncing $TARGET from $EXAMPLE"

user_keys=()
user_vals=()
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|\#*) continue ;;
  esac
  if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
    user_keys+=("${BASH_REMATCH[1]}")
    user_vals+=("${BASH_REMATCH[2]}")
  fi
done < "$TARGET"

lookup_user_value() {
  local key="$1" i
  for i in "${!user_keys[@]}"; do
    if [ "${user_keys[$i]}" = "$key" ]; then
      echo "${user_vals[$i]}"
      return 0
    fi
  done
  return 1
}

DECL_RE='^#? ?([A-Za-z_][A-Za-z0-9_]*)=(.*)$'

template_keys=()
while IFS= read -r line || [ -n "$line" ]; do
  if [[ "$line" =~ $DECL_RE ]]; then
    template_keys+=("${BASH_REMATCH[1]}")
  fi
done < "$EXAMPLE"

key_in_template() {
  local key="$1" k
  for k in "${template_keys[@]}"; do
    [ "$k" = "$key" ] && return 0
  done
  return 1
}

tmp=$(mktemp)
preserved=0
written_keys=()

key_already_written() {
  local key="$1" k
  for k in "${written_keys[@]}"; do
    [ "$k" = "$key" ] && return 0
  done
  return 1
}

while IFS= read -r line || [ -n "$line" ]; do
  if [[ "$line" =~ $DECL_RE ]]; then
    key="${BASH_REMATCH[1]}"
    if key_already_written "$key"; then
      continue
    fi
    written_keys+=("$key")
    if user_val=$(lookup_user_value "$key"); then
      echo "${key}=${user_val}" >> "$tmp"
      preserved=$((preserved + 1))
    else
      echo "$line" >> "$tmp"
    fi
  else
    echo "$line" >> "$tmp"
  fi
done < "$EXAMPLE"

orphans=()
for key in "${user_keys[@]}"; do
  if ! key_in_template "$key"; then
    orphans+=("$key")
  fi
done

if [ "${#orphans[@]}" -gt 0 ]; then
  {
    echo ""
    echo "# -----------------------------------------------------------------"
    echo "# ORPHANED keys — estan en tu .env pero ya no estan en .env.example."
    echo "# Probablemente sobraron de una version anterior. Revisalas y borra"
    echo "# si ya no las necesitas."
    echo "# -----------------------------------------------------------------"
    for key in "${orphans[@]}"; do
      val=$(lookup_user_value "$key")
      echo "${key}=${val}"
    done
  } >> "$tmp"
fi

new_keys=0
for k in "${template_keys[@]}"; do
  if ! lookup_user_value "$k" >/dev/null; then
    new_keys=$((new_keys + 1))
  fi
done

info "Proposed changes"
printf "  preserved:  ${BOLD}%d${NC} user override(s)\n" "$preserved"
printf "  new keys:   ${BOLD}%d${NC} template key(s) available\n" "$new_keys"
if [ "${#orphans[@]}" -gt 0 ]; then
  printf "  orphaned:   ${BOLD}${YELLOW}%d${NC} key(s) movidas al final — revisalas\n" "${#orphans[@]}"
  for key in "${orphans[@]}"; do
    printf "              - %s\n" "$key"
  done
fi

if cmp -s "$TARGET" "$tmp"; then
  echo
  ok "no changes — .env is in sync with .env.example"
  rm -f "$tmp"
  exit 0
fi

info "Diff (current .env → proposed .env)"
if [ -t 1 ] && command -v git >/dev/null 2>&1; then
  git --no-pager diff --no-index --color=always "$TARGET" "$tmp" 2>/dev/null | tail -n +5 || true
elif command -v diff >/dev/null 2>&1; then
  diff -u "$TARGET" "$tmp" | sed 's/^/  /' || true
else
  echo "  (diff command not available — skipping preview)"
fi

if [ "$ASSUME_YES" != "1" ]; then
  echo
  printf "${BOLD}Apply these changes?${NC} [y/N] "
  read -r answer </dev/tty || answer=""
  case "$answer" in
    y|Y|yes|YES) ;;
    *)
      printf "\n${YELLOW}Aborted — .env left untouched.${NC}\n"
      rm -f "$tmp"
      exit 0
      ;;
  esac
fi

backup="${TARGET}.bak.$(date +%Y%m%d-%H%M%S)"
cp "$TARGET" "$backup"
mv "$tmp" "$TARGET"

info "Applied"
ok "backup saved to $TARGET_DIR/$backup"
printf "\n${GREEN}${BOLD}Sync complete.${NC}\n"
