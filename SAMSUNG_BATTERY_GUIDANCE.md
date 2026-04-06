# Samsung Battery Management — Verification and Disable Guide

Samsung's OneUI applies several layers of battery management on top of standard Android.
Each layer can independently kill background services, including FamilyRules.
The standard Android battery-optimization exemption (handled by `PermissionsSetupActivity`)
is **not enough** on Samsung — all layers below must also be addressed.

> **Applies to:** Samsung Galaxy phones running OneUI (Android 8 / Oreo through Android 14).
> Steps differ slightly between OneUI versions; the closest matching section should be followed.

---

## How to check which Android version you have

**Settings → About phone → Software information → Android version**

---

## Android 14 (OneUI 6)

Samsung officially committed to respecting standard foreground-service guarantees from
OneUI 6 / Android 14 onward for apps targeting Android 14. In practice some restrictions
may still apply via add-on apps. Check the following.

### 1. Standard battery optimization (required on all versions)

**Settings → Apps → FamilyRules → Battery → Battery optimization**
Switch to "All apps" list → find FamilyRules → set to **Don't optimize**.

**Verify:** The entry shows "Not optimized".

### 2. Good Guardians — Battery Guardian

If "Good Guardians" is installed (Settings or Galaxy Store):

- Open **Good Guardians → Battery Guardian**
- Locate "App power saving"
- Either disable the feature globally or exclude FamilyRules from it

**Verify:** FamilyRules is not listed under monitored/restricted apps.

### 3. Good Lock — Long Live App module

If the Good Lock app is installed (Galaxy Store):

- Open **Good Lock → Long Live App**
- Add FamilyRules to the protected list

**Verify:** FamilyRules appears in the protected list.

### 4. Recent Apps — Keep Open

As a supplementary measure (not a substitute for the settings above):

- Open **Recent Apps**
- Long-press the FamilyRules icon
- Tap **Keep open** (lock icon)

**Verify:** A lock icon appears on the FamilyRules card in Recent Apps.

---

## Android 13 (OneUI 5)

### 1. Per-app battery optimization

**Settings → Apps → FamilyRules → Battery**
Set to **Unrestricted** (not "Optimized" or "Restricted").

**Verify:** Shows "Unrestricted".

### 2. Adaptive battery

**Settings → Battery and device care → Battery → More battery settings**
Turn off **Adaptive battery**.

**Verify:** Toggle is off.

### 3. Background usage limits — sleeping app lists

**Settings → Battery and device care → Battery → Background usage limits**

- Turn off **Put unused apps to sleep**
- Ensure FamilyRules does **not** appear in "Sleeping apps", "Deep sleeping apps",
  or "Unused apps" lists
- Ensure FamilyRules **does** appear in "Never sleeping apps" (add it if missing)

**Verify:** FamilyRules is in "Never sleeping apps" and absent from all restricted lists.

### 4. Remove permissions if app is unused

**Settings → Apps → FamilyRules → Permissions**
Turn off **Remove permissions if app is unused**.

**Verify:** Toggle is off for FamilyRules.

### 5. Alarms and Reminders

**Settings → Apps → (⋮ menu) → Special Access → Alarms and Reminders**
Ensure FamilyRules is **allowed**.

**Verify:** FamilyRules is toggled on in this list.

---

## Android 11 / 12 (OneUI 3 / 4)

### 1. Per-app battery optimization

**Settings → Apps → FamilyRules → Battery → Battery optimization**
Switch list to "All apps" → FamilyRules → **Don't optimize**.

**Verify:** Shows "Not optimized".

### 2. Optimize battery usage (Special Access)

**Settings → Apps → (⋮ menu) → Special Access → Optimize battery usage**
Expand to "All apps" → find FamilyRules → toggle **off** (not optimized).

**Verify:** FamilyRules toggle is off.

### 3. Adaptive battery

**Settings → Battery and device care → Battery → More battery settings**
Turn off **Adaptive battery**.

**Verify:** Toggle is off.

### 4. Auto-optimize daily + Adaptive power saving

Path A (most devices):
**Settings → Battery and device care → Battery → (⋮ menu) → Automation**
Turn off **Auto-optimize daily** and **Adaptive power saving**.

Path B (some devices):
**Settings → Battery and device care → (⋮ menu) → Advanced**
Turn off **Auto-optimization**.

**Verify:** Both toggles are off.

### 5. Background usage limits — sleeping app lists

**Settings → Battery and device care → Battery → Background usage limits**

- Turn off **Put unused apps to sleep**
- Remove FamilyRules from "Sleeping apps" and "Deep sleeping apps" lists
- Add FamilyRules to "Apps that won't be put to sleep" (Never sleeping apps)

**Verify:** FamilyRules is in "Never sleeping apps"; "Put unused apps to sleep" is off.

> **Warning:** Samsung can re-add apps to sleeping lists after a firmware update or if the
> system decides the app uses too many resources. Re-check these lists after any OS update.

---

## Android 9 / 10 (OneUI 1 / 2)

### 1. Battery optimization (standard)

**Settings → Apps → FamilyRules → Battery**
Set **Background restriction** to "App can use battery in background".

**Verify:** Shows "App can use battery in background".

### 2. Device care — sleeping apps and auto-disable

**Settings → Device care → Battery → (⋮ menu) → Settings**

- Turn off **Put unused apps to sleep**
- Turn off **Auto-disable unused apps**
- Open **Sleeping apps** and remove FamilyRules from the list
- Open **Deep sleeping apps** and remove FamilyRules if present
- Open **Apps that won't be put to sleep** and add FamilyRules

**Verify:** Both auto-sleep toggles are off; FamilyRules is in the "won't be put to sleep"
list and absent from the sleeping/deep-sleeping lists.

### 3. Optimize battery usage

**Settings → Apps → FamilyRules → Battery → Optimize battery usage**
Expand to "All apps" → FamilyRules → **toggle off**.

**Verify:** FamilyRules toggle is off.

---

## Android 8 / 7 (Oreo / Nougat)

### 1. App power monitor — Unmonitored apps

**Settings → Device maintenance → Battery**

- Scroll down to "Sleeping apps" — remove FamilyRules if present
- Scroll to "Unmonitored apps" → (⋮ menu) → **Add apps** → add FamilyRules

**Verify:** FamilyRules appears in "Unmonitored apps" and not in "Sleeping apps".

---

## Android 6 and below

**Settings → Applications → (⋮ menu) → Special Access → Optimize battery usage**
Find FamilyRules → ensure it is **not selected** (not optimized).

**Verify:** FamilyRules is unchecked/not selected.

---

## After completing all steps

1. Reboot the device.
2. Open FamilyRules and confirm the setup screen shows all checks as satisfied.
3. Lock the screen and wait 5 minutes.
4. Unlock and verify the service is still running (status shown in the app).

> **After any Samsung firmware or OneUI update:** re-check all lists under
> "Background usage limits" / "App power management". Samsung is known to reset
> these settings silently during updates.
