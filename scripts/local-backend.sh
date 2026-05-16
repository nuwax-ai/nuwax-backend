#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOOTSTRAP_MODULE="app-platform-bootstrap/app-platform-web-bootstrap"
REPACK_MODULES=(
  "app-platform-modules/platform-system/system-api|system-api|1.0.0-SNAPSHOT"
  "app-platform-modules/platform-system/system-web|system-web|1.0.0-SNAPSHOT"
  "app-platform-modules/app-platform-log/app-platform-log-api|app-platform-log-api|1.0.0-SNAPSHOT"
  "app-platform-modules/app-platform-log/app-platform-log-web|app-platform-log-web|1.0.0-SNAPSHOT"
  "app-platform-modules/app-platform-eco-market/app-platform-eco-market-api|app-platform-eco-market-api|1.0.0-SNAPSHOT"
  "app-platform-modules/app-platform-eco-market/app-platform-eco-market-client-web|app-platform-eco-market-client-web|1.0.0-SNAPSHOT"
  "app-platform-modules/app-platform-memory/app-platform-memory-api|app-platform-memory-api|1.0.0-SNAPSHOT"
)

cd "${ROOT_DIR}"

if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if ! java -version >/dev/null 2>&1; then
  echo "JDK 17 is required. Install it with: brew install openjdk@17" >&2
  exit 127
fi

install_original_repack_modules() {
  local mvn_cmd="$1"
  local repo_dir="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}"
  local module_entry module_dir artifact_id version jar_file

  rm -rf "${repo_dir}/com/xspaceagi/app-platform-eco-market-web/1.0.0-SNAPSHOT"

  for module_entry in "${REPACK_MODULES[@]}"; do
    IFS="|" read -r module_dir artifact_id version <<< "${module_entry}"
    jar_file="${ROOT_DIR}/${module_dir}/target/${artifact_id}-${version}.jar"
    if [[ -f "${jar_file}" ]]; then
      (cd "${ROOT_DIR}" && "${mvn_cmd}" -q org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
        -Dfile="${jar_file}" \
        -DpomFile="${ROOT_DIR}/${module_dir}/pom.xml" \
        -DgroupId=com.xspaceagi \
        -DartifactId="${artifact_id}" \
        -Dversion="${version}" \
        -Dpackaging=jar)
    fi
  done
}

if command -v mvn >/dev/null 2>&1; then
  mvn -pl "fast-boot-dependencies,${BOOTSTRAP_MODULE}" -am -DskipTests -Plocal install
  install_original_repack_modules mvn
  exec mvn -pl "${BOOTSTRAP_MODULE}" -DskipTests -Plocal spring-boot:run "$@"
fi

if [[ -x ./mvnw ]]; then
  ./mvnw -pl "fast-boot-dependencies,${BOOTSTRAP_MODULE}" -am -DskipTests -Plocal install
  install_original_repack_modules "${ROOT_DIR}/mvnw"
  exec "${ROOT_DIR}/mvnw" -pl "${BOOTSTRAP_MODULE}" -DskipTests -Plocal spring-boot:run "$@"
fi

echo "Maven is required. Install it with: brew install maven" >&2
exit 127
