# Family Rules Android - App Protection Guide

## Overview

This document explains the comprehensive protection mechanisms implemented to prevent your parental control app from being uninstalled by children.

## Protection Mechanisms Implemented

### 1. Device Administrator Rights (Primary Protection)

**What it does:**
- Prevents app uninstallation without entering device admin password
- Provides device control capabilities (lock device, set password policies)
- Requires explicit user action to disable

**Implementation:**
- `DeviceAdminReceiver.kt` - Handles device admin events
- `DeviceAdminManager.kt` - Manages device admin permissions
- `device_admin.xml` - Defines admin policies

**User Experience:**
- When child tries to uninstall, system requires device admin password
- App cannot be uninstalled through normal means
- Parent receives notification if admin rights are removed

### 2. Stealth Mode (Secondary Protection)

**What it does:**
- Hides app icon from launcher/home screen
- Makes app less discoverable by children
- App still runs in background with full functionality

**Implementation:**
- `StealthModeManager.kt` - Controls app icon visibility
- Uses `PackageManager.setComponentEnabledSetting()` to hide launcher activity

**User Experience:**
- App icon disappears from home screen
- App continues monitoring in background
- Can be toggled on/off by parent through settings

### 3. Tamper Detection (Monitoring)

**What it does:**
- Continuously monitors for tampering attempts
- Detects if device admin rights are removed
- Monitors app installation status
- Sends alerts to parent if tampering detected

**Implementation:**
- `TamperDetector.kt` - Runs background monitoring
- Checks every 30 seconds for tampering signs
- Integrates with existing notification system

**User Experience:**
- Parent receives immediate alerts if child attempts to bypass protection
- Automatic device lock if tampering detected
- Comprehensive logging of all protection events

### 4. Battery Optimization Bypass

**What it does:**
- Prevents Android from killing the app to save battery
- Ensures app continues running even during low battery
- Maintains protection even when device is in power saving mode

**Implementation:**
- Requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission
- Guides user through system settings to disable battery optimization
- Monitors battery optimization status

### 5. Enhanced Service Protection

**What it does:**
- Multiple layers of service protection
- Boot receiver to restart on device reboot
- Periodic keep-alive workers
- Foreground service with persistent notification

**Implementation:**
- `FamilyRulesCoreService` with `START_STICKY`
- `BootReceiver` for automatic restart
- `KeepAliveWorker` for periodic service checks
- Multiple monitoring systems

## Setup Process

### For Parents:

1. **Install the app** on child's device
2. **Complete initial setup** (permissions, server connection)
3. **Enable Device Administrator Rights**:
   - App will prompt for device admin permission
   - Enter device password when prompted
   - This is the most important step for protection
4. **Enable Stealth Mode** (optional but recommended):
   - Hides app icon from child
   - App continues working in background
5. **Disable Battery Optimization**:
   - Prevents system from killing the app
   - Ensures continuous protection

### Protection Setup Activity

The app includes a dedicated `ProtectionSetupActivity` that:
- Guides parents through each protection step
- Shows real-time status of each protection feature
- Provides one-click access to system settings
- Validates that all protections are properly enabled

## Technical Details

### Device Admin Policies

The app requests these device admin policies:
- `limit-password` - Control password requirements
- `watch-login` - Monitor login attempts
- `reset-password` - Reset device password
- `force-lock` - Lock device remotely
- `wipe-data` - Factory reset capability
- `expire-password` - Set password expiration
- `encrypted-storage` - Control storage encryption
- `disable-camera` - Disable camera access

### Permissions Required

```xml
<!-- Device Admin Permissions -->
<uses-permission android:name="android.permission.BIND_DEVICE_ADMIN" />
<uses-permission android:name="android.permission.DEVICE_POWER" />
<uses-permission android:name="android.permission.DISABLE_KEYGUARD" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- Additional Protection Permissions -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

## Limitations and Considerations

### Android Version Compatibility
- Device admin features work on all Android versions
- Some advanced features require Android 6.0+
- Battery optimization bypass requires Android 6.0+

### User Education
- Parents must understand the importance of device admin rights
- Stealth mode should be explained to avoid confusion
- Regular monitoring of protection status is recommended

### Legal and Ethical Considerations
- Ensure compliance with local laws regarding device monitoring
- Consider child's privacy rights
- Use protection features responsibly

## Troubleshooting

### If App Gets Uninstalled
1. Check if device admin rights were properly enabled
2. Verify that battery optimization was disabled
3. Review tamper detection logs for clues
4. Ensure child doesn't have device admin password

### If Protection Stops Working
1. Check device admin status in system settings
2. Verify battery optimization settings
3. Restart the app and re-enable protections
4. Check for Android system updates that might affect permissions

### Recovery Options
- App can be reinstalled and protections re-enabled
- Device admin rights can be re-granted
- Stealth mode can be toggled as needed
- All settings are preserved in app data

## Best Practices

1. **Regular Monitoring**: Check protection status weekly
2. **Password Security**: Use strong device passwords
3. **System Updates**: Test protection after Android updates
4. **Backup Strategy**: Keep app installation files ready
5. **Communication**: Explain to child why the app is necessary

## Conclusion

This multi-layered protection system provides robust defense against app uninstallation while maintaining usability for parents. The combination of device admin rights, stealth mode, tamper detection, and battery optimization bypass creates a comprehensive shield that is very difficult for children to bypass.

The protection is designed to be transparent to the child while providing parents with full control and monitoring capabilities.
