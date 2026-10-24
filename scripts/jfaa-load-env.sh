# Sourced by doctor.sh (with ROOT set, working directory ROOT): loads the instance's env file into the environment and
# exports its resolved path as JFAA_ENV_FILE. ENV_FILE is relative to the repo root (.env.test) or absolute. A release
# worktree has no root .env, so a missing DEFAULT file is normal; one the caller named and that is missing is reported.
_jfaa_env_named="${ENV_FILE:+yes}"
ENV_FILE="${ENV_FILE:-.env}"
case "$ENV_FILE" in
  /*) JFAA_ENV_PATH="$ENV_FILE" ;;
  *) JFAA_ENV_PATH="$ROOT/$ENV_FILE" ;;
esac
if [ -f "$JFAA_ENV_PATH" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$JFAA_ENV_PATH"
  set +a
elif [ "$_jfaa_env_named" = yes ]; then
  echo "doctor: ENV_FILE=$ENV_FILE not found (looked for $JFAA_ENV_PATH); using the shell environment only" >&2
fi
export JFAA_ENV_FILE="$JFAA_ENV_PATH"
unset _jfaa_env_named
