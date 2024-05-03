set -e

# Verify pods are good
cd ios
pod lib lint --allow-warnings Capacitor.podspec
pod lib lint --allow-warnings CapacitorCordova.podspec


# Do the gradle
cd ../android
./gradlew clean build
