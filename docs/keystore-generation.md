# Keepix Release Keystore Generation

Run once locally (never commit the keystore):

```bash
mkdir -p ~/keystores
keytool -genkeypair \
  -alias keepix \
  -keypass <KEY_PASSWORD> \
  -keystore ~/keystores/keepix-release.jks \
  -storepass <STORE_PASSWORD> \
  -keyalg RSA \
  -keysize 2048 \
  -validity 9125 \
  -dname "CN=Keepix, OU=Mobile, O=Mazin Kazi, L=City, ST=State, C=US"
```

- `KEY_PASSWORD` and `STORE_PASSWORD` can be the same or different — record both.
- Alias must be `keepix` (referenced in `signingConfigs`).
- Path must be `~/keystores/keepix-release.jks`.
- After generation, add the four secrets to `gradle.properties` (see Task 2).
- To verify: `keytool -list -v -keystore ~/keystores/keepix-release.jks -alias keepix`