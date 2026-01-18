# Philips Hue OAuth2 Troubleshooting Guide

## "Something went wrong" Error

If you're seeing "Grant permission - Something went wrong - We couldn't continue the app linking process, please try again later" from Philips Hue, follow these troubleshooting steps:

### 1. Verify Your Credentials

Check your `.env` file and ensure the values **EXACTLY** match what's in your Philips Hue Developer Portal (https://developers.meethue.com/):

```bash
# These MUST exactly match the developer portal
HUE_CLIENT_ID=<your Client Id - note: this is called "Client Id" in the portal>
HUE_CLIENT_SECRET=<your Client secret>
HUE_APP_ID=<your AppId - note: this is called "AppId" in the portal>
HUE_REDIRECT_URI=<must EXACTLY match registered callback URL>
```

**Common mistakes:**
- Using "Application Id" instead of "AppId" (they're different fields!)
- Copying extra spaces before/after the values
- Using http when you registered https (or vice versa)
- Missing or incorrect port number in redirect URI

### 2. Check Redirect URI

The `HUE_REDIRECT_URI` must **EXACTLY** match what you configured in the developer portal:

- **Protocol**: `http://` vs `https://` must match
- **Host**: Domain or IP must match exactly
- **Port**: Include port if not default (80/443)
- **Path**: Must include `/api/hue/callback`

Examples:
```bash
# If deployed on VDS with domain
HUE_REDIRECT_URI=https://yourdomain.com/api/hue/callback

# If deployed on VDS with IP and custom port
HUE_REDIRECT_URI=http://168.119.100.24:8080/api/hue/callback

# If testing locally
HUE_REDIRECT_URI=http://localhost:8080/api/hue/callback
```

### 3. Verify App Status in Developer Portal

1. Log into https://developers.meethue.com/
2. Click your username (top right) → "Remote Hue API appids"
3. Find your application
4. Check that:
   - App status is **Active** (not pending/suspended)
   - The redirect URI is correctly registered
   - You're using the correct AppId and Client Id (not Application Id!)

### 4. Check for Field Confusion

In the Philips Hue Developer Portal, there are confusingly named fields:

- **AppId**: This is what you need for `HUE_APP_ID` (shorter, alphanumeric)
- **Application Id**: This is NOT what you need (longer, UUID-like)
- **Client Id**: This is what you need for `HUE_CLIENT_ID`
- **Client secret**: This is what you need for `HUE_CLIENT_SECRET`

Make sure you're copying the right values!

### 5. Try Minimal Parameters

The current implementation includes all optional parameters. Some users report success by using only required parameters.

To test with minimal parameters, temporarily edit `server/src/main/kotlin/.../hue/HueRemoteClient.kt` around line 56-77:

```kotlin
val params = buildMap {
    // REQUIRED only
    put("client_id", clientId)
    put("response_type", "code")
    put("state", state)

    // Try without redirect_uri first (Hue docs say it's optional)
    // put("redirect_uri", redirectUri)

    // Try without these optional params
    // put("deviceid", "hue_manager_server")
    // put("devicename", "Hue Manager Server")
    // put("appid", appId)
}
```

Then rebuild and test again.

### 6. Check Server Logs

The server now provides detailed logging for OAuth2. Check your server logs for:

```
=== Hue OAuth2 Authorization URL ===
Client ID: xxxxxxxxxx...xxxx
App ID: xxxxxxxxxx...xxxx
Redirect URI: http://...
Full URL: https://api.meethue.com/v2/oauth2/authorize?...
```

Verify that all these values look correct.

### 7. Test the URL Manually

Copy the "Full URL" from the server logs and paste it into your browser. This will show you the exact OAuth2 request being made to Philips Hue.

### 8. Common Solutions

Based on community reports, these have worked for others:

1. **Remove appid parameter**: Some users report success without the `appid` parameter
2. **Remove redirect_uri**: Since Hue only supports one redirect URI per app, try without this parameter
3. **Add deviceid**: Some users report that `deviceid` is required (current implementation includes it)
4. **Check app approval**: Your app might need manual approval from Philips - check developer portal
5. **Wait and retry**: Sometimes Hue's systems have temporary issues - try again in a few hours

### 9. Still Not Working?

If you've tried everything above:

1. Create a NEW app in the Philips Hue Developer Portal
2. Use the new credentials in your `.env`
3. Make sure to register the callback URL
4. Try again with the new app

### 10. Contact Philips Support

If nothing works, contact Philips Hue Developer Support:
- Forum: https://developers.meethue.com/forum/
- Email: See developer portal for current support contact

## Successful Authorization Flow

When everything is working correctly, you should see:

1. Visit `/api/hue/authorize` endpoint
2. Redirected to Philips Hue login page
3. Log in with your Hue account
4. See "App wants to access your lights" or similar
5. Click "Authorize" or "Grant Access"
6. Redirected back to your callback URL
7. See "Authorization Successful!" message
8. Press link button on bridge
9. Click "Complete Setup"
10. Done!

## Still Getting "Something Went Wrong"?

This error happens **before** you even see the Hue login page, which means Hue is rejecting the authorization request itself. This is almost always due to:

1. Incorrect client_id
2. Incorrect appid
3. Redirect URI mismatch
4. App not approved/active in developer portal

Double-check all credentials character by character!
