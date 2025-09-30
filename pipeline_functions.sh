#!/bin/sh

function add_gradle_credentials() {
  curl -Ss --fail --header "Private-Token: $SA_APPIAN_GUEST_ACCESS_TOKEN" "$STRATUS_CLI_LINUX_DOWNLOAD_URL" -o /usr/local/bin/stratus && chmod 755 /usr/local/bin/stratus
  curl -Ss --fail --header "Private-Token: $SA_APPIAN_GUEST_ACCESS_TOKEN" "$CI_CLI_LINUX_DOWNLOAD_URL" -o /usr/local/bin/ci && chmod 755 /usr/local/bin/ci

  eval $(stratus login)
  local apiKey=$(ci artifactory token)
  export GRADLE_DIR="/root/.gradle"
  mkdir $GRADLE_DIR
  properties="$GRADLE_DIR/gradle.properties"
  echo -e "appianArtifactsUsername: ${CI_PROJECT_PATH//\//_}\\nappianArtifactsApiKey: $apiKey" > "$GRADLE_DIR/gradle.properties"
}


add_maven_credentials() {
  STRATUS_FILE=/usr/local/bin/stratus
  if ! [ -f "$STRATUS_FILE" ]; then
    curl -Ss --fail --header "Private-Token: $SA_APPIAN_GUEST_ACCESS_TOKEN" "$STRATUS_CLI_LINUX_DOWNLOAD_URL" -o $STRATUS_FILE && chmod 755 $STRATUS_FILE
  fi
  mavenCentralUsername=$(stratus secrets read gitlab.appian-stratus.com/projects/appian/prod/connected-systems-client --field "mavenCentralAppianUserCredentialsName")
  mavenCentralPassword=$(stratus secrets read gitlab.appian-stratus.com/projects/appian/prod/connected-systems-client --field "mavenCentralAppianUserCredentialsPassword")
  mavenCentralKeyId=$(stratus secrets read gitlab.appian-stratus.com/projects/appian/prod/connected-systems-client --field "mavenCentralGpgKeyringKeyId")
  mavenCentralBase64PrivateKey=$(stratus secrets read gitlab.appian-stratus.com/projects/appian/prod/connected-systems-client --field "mavenCentralGpgKeyringBase64PrivateKey")
  mavenCentralGpgKeyringPassword=$(stratus secrets read gitlab.appian-stratus.com/projects/appian/prod/connected-systems-client --field "mavenCentralGpgKeyringPassword")
  properties="$gradle_dir/gradle.properties"
  echo "mavenCentralUsername: $mavenCentralUsername" >> $properties
  echo "mavenCentralPassword: $mavenCentralPassword" >> $properties
  echo "mavenCentralKeyId: $mavenCentralKeyId" >> $properties
  echo "mavenCentralBase64PrivateKey: $mavenCentralBase64PrivateKey" >> $properties
  echo "mavenCentralGpgKeyringPassword: $mavenCentralGpgKeyringPassword" >> $properties
}
