# OC Remote 1.8.1

This patch fixes Pi Stack connections configured with an origin URL.

## Pi Stack Connection Fix

- Automatically resolve an origin-only Pi Stack URL such as `https://pi.example.com` to its `/control` endpoint.
- Apply the correction at runtime so existing saved servers work after updating without being recreated.
- Preserve explicit custom Control paths and normalize new or edited server entries consistently.
- Rebuild legacy URL components safely so query strings, fragments, or embedded credentials cannot swallow Control API routes.

## Verification

- Passed the full debug unit test suite.
- Built the Android test and release APKs successfully.
