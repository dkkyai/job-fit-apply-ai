# Replace Max Days Ago Slider with Preset Chips

## Overview
Replace the current 1–365 day slider for the "Max Days Ago" filter with a row of clickable preset chips (1, 7, 30, 90, All time). This makes the time filter a single-click operation.

## Changes Required

### 1. Update state and filter logic (lines 55–69)
- Change `maxDaysAgo` state from `number` to `number | null` (where `null` = All time)
- Update the `useMemo` filter: skip date filtering entirely when `maxDaysAgo === null`
- Default remains 7 days

### 2. Replace the slider UI (lines 165–193)
- Remove the second `<Slider>` component for days
- Render a row of clickable chips for `[1, 7, 30, 90, null]`
- Active chip gets primary styling (filled background), inactive chips get muted/outline styling
- Chips display labels: "1", "7", "30", "90", "All time"
- Keep the Fit Score slider unchanged

### 3. No new imports needed
- Uses existing `Badge` component already imported
- Inline styling with Tailwind classes