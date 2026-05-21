# Screenshot capture checklist

Play Console requires at least **2** phone screenshots and accepts up to **8**.

## How to capture from your connected device

```powershell
$adb = 'C:\Users\migue\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell screencap -p /sdcard/shot.png
& $adb pull /sdcard/shot.png .\01-groups.png
& $adb shell rm /sdcard/shot.png
```

## Recommended screens to capture (in this order)

1. **01-groups.png** — Your groups list with the Split-bill card visible
2. **02-group-detail.png** — A group with balances + a few expenditures
3. **03-add-expense.png** — Add expenditure with Scan/Voice chips visible
4. **04-analytics.png** — Analytics screen with the Group/You toggle
5. **05-split-bill.png** — Split-bill screen with items + per-person totals
6. **06-reminders.png** — Reminders list with at least one overdue Pay button
7. **07-settings.png** — Settings showing the currency + voice language pickers
8. **08-receipt-scan.png** — Mid-scan with "Receipt scanned" message

## Specs

- Aspect ratio: 16:9 to 19.5:9 (standard phone)
- Min size: 320 px on the short side; 3840 px max on the long side
- Pixel 8 native (1080×2400) is fine — Play Console scales as needed

## Feature graphic

Required separately: a 1024 × 500 PNG/JPG banner used at the top of the
listing. Drop it as `play-store/feature-graphic.png`. Suggested content:
the wallet icon + "StrataSpent" wordmark on the dark-slate brand background.

## High-res icon

512 × 512 PNG (no transparency, alpha channel optional but discouraged).
The adaptive launcher icon at `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
can be exported via Android Studio: right-click → Show in Resource Manager
→ Density-independent preview → Export as 512×512 PNG to
`play-store/high-res-icon.png`.
