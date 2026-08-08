# BOM Weather Alerts for Hubitat

Polls the Australian Bureau of Meteorology's public RSS warning feed and sends a notification the moment a new severe weather warning appears for your area.

## Why RSS

BOM publishes warning data through three different channels, and only one of them is actually usable here:

- **BOM's JSON API** (`api.weather.bom.gov.au`) - technically the best option (clean JSON, an explicit severity field, no auth), but every response states *"You must not use, copy or share it."* It's undocumented internal plumbing for BOM's own site/app, never licensed for third-party use.
- **Anonymous FTP** - BOM's actual documented, properly licensed "free for non-commercial use" data service. Requires an FTP client rather than a plain HTTPS request.
- **RSS** - *"a free service offered by Bureau of Meteorology for personal and non-commercial use only."* Plain HTTPS GET, standard RSS 2.0, no auth. This is what the app uses.

## What it does

- Polls a BOM RSS warning feed on an interval you set (default 10 minutes, matching the feed's own suggested refresh).
- Diffs the feed against what it saw last time, by each item's `<guid>` (falling back to `<link>` if a warning has no guid).
- Sends a notification for every genuinely new warning, worded as `BOM Warning: <title> - <link>` - the link goes straight to the full BOM warning page.
- **First run only establishes a baseline** - every warning already in the feed when you save the app is recorded as seen, silently, so turning this on doesn't dump every currently-active warning on you as if it were new.
- Optional keyword filter (e.g. `Severe Weather, Flood, Fire Weather`) to only alert on specific hazard types - RSS titles don't carry a structured severity/category field, so this is a plain case-insensitive substring match against the title.
- A status section on the settings page showing last check time, warning count, and the most recent items seen.
- "Check feed now" button - runs the real poll/parse/alert logic on demand against the live feed, not a canned message.
- "Send test notification" button - separately verifies your notification device works.
- Not a singleton - you can install a second instance pointed at a different feed (e.g. a marine feed, or a different state) if you need more than one.

## What's included

- `apps/bom_weather_alerts.groovy` - the whole app, no drivers required.

## Installation

1. In the Hubitat admin UI, go to **Apps Code > New App**, paste in `apps/bom_weather_alerts.groovy`, and save.
2. Go to **Apps > Add User App**, select **BOM Weather Alerts**.

## Setup

1. **BOM RSS warning feed URL** - defaults to WA Land Warnings (`IDZ00067`). Browse other states, regions, and the WA marine feed at [bom.gov.au/rss](https://www.bom.gov.au/rss/).
2. **Poll interval** - defaults to 10 minutes, matching the feed's own `<ttl>`. There's little point going faster.
3. Optionally set a **hazard filter** to only alert on specific warning types.
4. **Send alerts to** - pick one or more devices that support Hubitat's Notification capability.
5. Tap **Check feed now** to run the first real check, and **Send test notification** to confirm delivery. **Done** to save.

## Notes

- Not yet tested on a live hub. Local `groovyc` only confirms the file compiles as Groovy - it doesn't confirm the Hubitat sandbox accepts every class/method used here (in particular `XmlSlurper` for parsing the feed, and `httpGet` with `textParser: true`). First real test is installing it and tapping **Check feed now**.
- Warning severity/category is inferred only from the title text - the RSS feed doesn't carry the structured field BOM's (unlicensed) JSON API has.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
