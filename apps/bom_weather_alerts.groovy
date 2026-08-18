/*
 * BOM Weather Alerts
 * Namespace: Hubitat Integrations
 * Version: 1.5.4
 *
 * v1.5.4: the quiet-hours-end speaker announcement said "N items from
 * quiet hours" instead of the actual alert content - the email had the
 * real text, the speaker didn't. Fixed by tracking notify and speak text
 * separately through quiet-hours accumulation (a warning's notify text
 * includes its BOM link, unsuitable to read aloud - that's what the
 * separate speak text was always for elsewhere in this app, quiet hours
 * just never preserved it). Speaker now says "Daily BOM Alert: " followed
 * by the real content of everything that happened.
 *
 * v1.5.3: quiet hours end now sends exactly one notification/speaker
 * alert instead of up to two - the still-alerted temperature escalation
 * is folded into the same summary message as its own list item, rather
 * than a separate `sendNotification()` call with its own subject.
 * Gordon was getting two separate emails every morning at 06:00.
 *
 * v1.5.2: fixes a likely duplicate-alert race confirmed live - the
 * quiet hours summary listed the same temperature alert twice with an
 * identical reading. `tempHighAlerted`/`tempLowAlerted` are written from
 * an event handler (the sensor's own temperature attribute), where two
 * readings arriving close together could each read the flag before
 * either had written it back, both deciding "not yet alerted" and both
 * firing - matching the identical race Critical Device Monitor already
 * hit and fixed (v1.6.2) for its own event-driven safety flags. Fixed
 * the same way: switched both flags from `state` to `atomicState`, which
 * reads/writes storage directly on every access instead of a per-
 * execution cached copy.
 *
 * v1.5.1: fixes a real bug confirmed live - quiet hours failed to hold
 * back a temperature alert at 23:04 inside a 21:30-06:00 window, letting
 * it through immediately instead of silencing it. Root cause was
 * Hubitat's timeOfDayIsBetween() not handling an overnight (start-time-
 * after-end-time) window correctly. Replaced with explicit minute-of-day
 * comparison, matching the same fix already proven live in Critical
 * Device Monitor.
 *
 * v1.5.0: adds an optional daily rain forecast, folded into the quiet
 * hours summary rather than its own schedule - fires once a day, at
 * quiet hours end. Not from the warnings feed: uses BOM's Precis
 * Forecast product (IDW14199, the WA regional forecast), which - like
 * the warnings feed - is nominally an Anonymous FTP product but is also
 * mirrored over plain HTTPS at the same www.bom.gov.au/fwo/ path, no FTP
 * client needed. Same general BOM copyright basis as the warnings feed
 * (personal/non-commercial use, no explicit prohibition like the
 * rejected JSON API). Requires Quiet Hours (below) to be enabled, since
 * that's what drives the once-daily schedule this hooks into.
 *
 * v1.4.0: routes alerts through Gmail Notification Gateway's Group:/
 * Subject: prefix when the selected notifier is that specific driver
 * (matched by typeName, not device ID) - so alerts go to a chosen
 * recipient group (user/family/critical) rather than whatever the
 * device's own default is. Other notifier types (e.g. Mobile Proxy)
 * still get the plain message, unprefixed.
 *
 * v1.3.1: temperature recovery ("back to normal") no longer sends a
 * notification/speaker alert - only three things ever alert: a new RSS
 * warning, temperature rising above the high threshold, temperature
 * falling below the low threshold. Recovery is still tracked internally
 * (so a threshold can re-alert on a later crossing), it's just silent.
 *
 * v1.3.0: replaces the ad-hoc manual snooze with a Critical Device
 * Monitor-style quiet hours window (start/end time, not a duration
 * button). During the window every alert (warnings and temperature)
 * stays silent but is still recorded; at the window's end a single
 * summary notification covers whatever happened, and if a temperature
 * threshold is still actively crossed right as the window ends, that
 * escalates immediately with a full alert - same shape as Critical
 * Device Monitor's internet-monitor quiet hours.
 *
 * v1.2.0: adds an optional temperature threshold alert (above/below,
 * configurable both ways) against a Hubitat capability.temperatureMeasurement
 * device - not BOM. BOM has no simple licensed feed for raw current
 * temperature (only warnings via RSS, same licensing wall documented
 * below), so this reads whatever temperature sensor is already on the
 * hub - the OpenWeatherMap device, an Averaging Master external-temperature
 * child, or any other. Event-driven (subscribes to the sensor's own
 * temperature attribute) plus one backstop check on install/save, not
 * polled separately. Alerts once per threshold crossing and again on
 * recovery, same shape as the RSS alerting, and goes through the same
 * snooze/notification/speaker paths.
 *
 * v1.1.0: adds optional speaker announcements (capability.speechSynthesis)
 * alongside notification devices, and a snooze - a configurable-duration
 * button that silences both notification and speaker alerts. Snoozed
 * warnings are still recorded as seen underneath, so nothing floods in
 * the moment the snooze ends; the status page still updates normally
 * while snoozed, only the alerting itself is suppressed.
 *
 * v1.0.1: sends a browser-like User-Agent header on the feed request.
 * BOM's servers return HTTP 403 to requests using Hubitat's default
 * (Java-identifying) User-Agent - confirmed live against the WA land
 * warnings feed.
 *
 * Polls the Australian Bureau of Meteorology's public RSS warning feed
 * and sends a notification the moment a new severe weather warning
 * appears (severe thunderstorm, flood, fire weather, etc).
 *
 * RSS is used deliberately, not BOM's JSON API or FTP feeds: BOM's JSON
 * API response explicitly states "You must not use, copy or share it",
 * and while the FTP/XML data service is properly licensed for
 * non-commercial use, it needs an FTP client. RSS is "a free service
 * offered by Bureau of Meteorology for personal and non-commercial use
 * only" - plain HTTPS GET, no auth, and every item already links back
 * to the full BOM warning page.
 *
 * Default feed is WA Land Warnings (IDZ00067). Other states, regions,
 * and the WA marine feed are listed at https://www.bom.gov.au/rss/ -
 * point "BOM RSS warning feed URL" at any of them, or install a second
 * instance of this app for a second feed.
 *
 * The first poll after install/save only establishes a baseline -
 * every warning already in the feed at that moment is recorded as
 * seen, silently - so turning this on doesn't flood you with alerts
 * for warnings that were already active. Every poll after that alerts
 * only on genuinely new items, matched by the feed's own <guid> (or
 * <link> if a particular item has no guid).
 */

definition(
    name: 'BOM Weather Alerts',
    namespace: 'Hubitat Integrations',
    author: 'Gordon Thelander',
    description: 'Polls a BOM RSS warning feed and notifies when a new severe weather warning appears.',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: false
)

preferences {
    page(name: 'mainPage')
}

def mainPage() {
    return dynamicPage(name: 'mainPage', title: 'BOM Weather Alerts', install: true, uninstall: true) {
        section('Feed') {
            paragraph 'Uses the Bureau of Meteorology\'s public RSS warning feed - "free ... for personal and non-commercial use only." Default below is WA Land Warnings. Browse other states, regions, and the WA marine feed at https://www.bom.gov.au/rss/'
            input 'feedUrl', 'text', title: 'BOM RSS warning feed URL',
                  defaultValue: 'https://www.bom.gov.au/fwo/IDZ00067.warnings_land_wa.xml', required: true
        }
        section('Polling') {
            input 'pollMinutes', 'number', title: 'Poll interval (minutes)',
                  defaultValue: 10, range: '5..60', required: true
            paragraph 'The feed\'s own suggested refresh is about 10 minutes - polling faster won\'t get you newer data, it just hits BOM\'s server more than needed.'
        }
        section('Filtering (optional)') {
            input 'hazardFilter', 'text', required: false,
                  title: 'Only alert when the warning title contains (comma-separated, case-insensitive)'
            paragraph 'Leave blank to alert on every new warning. Example: Severe Weather, Flood, Fire Weather, Lower West'
        }
        section('Alerts') {
            input 'notifiers', 'capability.notification', title: 'Send alerts to', multiple: true, required: true
            input 'gmailGroup', 'enum', title: 'Gmail recipient group (Gmail Notification Gateway devices only)',
                  options: ['user', 'family', 'critical'], defaultValue: 'critical', required: false
            paragraph 'Only applies to a selected notifier that is actually a Gmail Notification Gateway device - sent as its Group:/Subject: prefix. Other notifier types (e.g. Mobile Proxy) get the plain message either way.'
            input 'btnCheckNow', 'button', title: 'Check feed now'
            input 'btnTestNotify', 'button', title: 'Send test notification'
        }
        section('Speaker alerts (optional)') {
            input 'speechDevices', 'capability.speechSynthesis', title: 'Speaker(s) to announce warnings',
                  multiple: true, required: false
            input 'btnTestSpeaker', 'button', title: 'Test speaker alert'
        }
        section('Temperature alerts (optional)') {
            paragraph 'Not from BOM - BOM has no simple licensed feed for raw current temperature, only warnings via RSS. Uses a temperature sensor already on your hub instead, e.g. your OpenWeatherMap device or an Averaging Master external-temperature child.'
            input 'tempSensor', 'capability.temperatureMeasurement', title: 'Temperature sensor to monitor',
                  required: false, submitOnChange: true
            if (settings.tempSensor) {
                input 'tempHighThreshold', 'number', title: 'Alert when temperature rises above (°C)',
                      defaultValue: 30, required: true
                input 'tempLowThreshold', 'number', title: 'Alert when temperature falls below (°C)',
                      defaultValue: 10, required: true
                input 'btnTestTemp', 'button', title: 'Check temperature now'
            }
        }
        section('Rain forecast (optional)') {
            paragraph 'Fires once a day, folded into the quiet hours summary below - requires Quiet Hours to be enabled and an end time set, since that\'s what drives the daily schedule. Not from the warnings feed: uses BOM\'s Precis Forecast product, also reachable over plain HTTPS like the warnings feed, same general personal-use licensing basis.'
            input 'enableRainForecast', 'bool', title: 'Include today\'s rain forecast in the daily quiet hours summary',
                  defaultValue: false, submitOnChange: true
            if (settings.enableRainForecast) {
                input 'rainLocation', 'text', title: 'BOM forecast location name', defaultValue: 'Yanchep', required: true
                paragraph 'Case-sensitive match against BOM\'s Precis Forecast area names (e.g. Yanchep, Perth, Lancelin) - Yanchep is the nearest named location to Two Rocks, and matches the city your OpenWeatherMap device already reports.'
                input 'btnTestRain', 'button', title: 'Check rain forecast now'
            }
        }
        section('Quiet hours (optional)') {
            paragraph 'During this window, alerts (warnings and temperature) stay completely silent - no notification, no speaker - but are still tracked underneath. At the end of the window, if anything happened, you get one summary. If a temperature threshold is still actively crossed right as the window ends, that escalates immediately with a full alert, same as Critical Device Monitor\'s internet-monitor quiet hours.'
            input 'enableQuietHours', 'bool', title: 'Suppress alerts during quiet hours', defaultValue: false, submitOnChange: true
            if (settings.enableQuietHours) {
                input 'quietHoursStart', 'time', title: 'Quiet hours start', required: true
                input 'quietHoursEnd', 'time', title: 'Quiet hours end', required: true
                input 'btnTestSummary', 'button', title: 'Send quiet hours summary now'
            }
        }
        section('Status') {
            paragraph statusText()
        }
    }
}

def statusText() {
    StringBuilder sb = new StringBuilder()
    if (isQuietHours()) {
        sb << '<b>Quiet hours active - alerts are being held for the end-of-window summary.</b><br><br>'
    }
    if (!state.lastPollAt) {
        sb << 'Not polled yet - save this page to run the first check.'
        return sb.toString()
    }
    sb << "Last checked: ${state.lastPollAt}<br>"
    sb << "Warnings currently in feed: ${state.lastItemCount ?: 0}<br>"
    if (state.lastError) {
        sb << "<br><b>Last error:</b> ${state.lastError}<br>"
    }
    if (state.lastWarnings) {
        sb << '<br><b>Most recent warnings:</b><br>'
        state.lastWarnings.each { w ->
            sb << "- ${w.title} <i>(${w.pubDate})</i><br>"
        }
    }
    if (settings.tempSensor) {
        sb << '<br><b>Temperature:</b> '
        sb << (state.lastTemp != null ? "${state.lastTemp}°C" : 'not yet read')
        if (state.lastTempAt) sb << " <i>(${state.lastTempAt})</i>"
        sb << '<br>'
        if (atomicState.tempHighAlerted) sb << 'Above high threshold - alerting<br>'
        if (atomicState.tempLowAlerted) sb << 'Below low threshold - alerting<br>'
    }
    if (settings.enableRainForecast) {
        sb << '<br><b>Rain forecast:</b> '
        if (state.rainError) {
            sb << "error - ${state.rainError}"
        } else if (rainSummaryText()) {
            sb << rainSummaryText()
            if (state.rainCheckedAt) sb << " <i>(${state.rainCheckedAt})</i>"
        } else {
            sb << 'not yet checked'
        }
        sb << '<br>'
    }
    return sb.toString()
}

def appButtonHandler(btn) {
    if (btn == 'btnCheckNow') {
        pollFeed()
    } else if (btn == 'btnTestNotify') {
        sendNotification('Test notification from BOM Weather Alerts.', 'BOM Weather Alerts - Test')
    } else if (btn == 'btnTestSpeaker') {
        speakAlert('This is a test of the BOM Weather Alerts speaker announcement.')
    } else if (btn == 'btnTestTemp') {
        checkTemperatureNow()
    } else if (btn == 'btnTestSummary') {
        sendQuietHoursSummary()
    } else if (btn == 'btnTestRain') {
        fetchRainForecast()
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    unsubscribe()
    initialize()
}

def initialize() {
    unschedule()
    runIn(5, pollFeed)
    if (settings.tempSensor) {
        subscribe(settings.tempSensor, 'temperature', temperatureEventHandler)
        runIn(6, checkTemperatureNow)
    }
    if (settings.enableQuietHours && settings.quietHoursEnd) {
        schedule(settings.quietHoursEnd, sendQuietHoursSummary)
    }
}

def pollFeed() {
    String url = settings.feedUrl
    if (!url) {
        state.lastError = 'No feed URL configured.'
        scheduleNextPoll()
        return
    }

    Map params = [
        uri    : url,
        textParser: true,
        timeout: 15,
        headers: [
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
        ]
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status != 200) {
                state.lastError = "Feed returned HTTP ${resp.status}"
                return
            }

            def data = resp.data
            String body = (data instanceof String) ? data : (data?.text ?: data?.toString())
            def xml = new XmlSlurper().parseText(body)

            List items = xml.channel.item.collect { item ->
                [
                    title  : item.title.text(),
                    link   : item.link.text(),
                    pubDate: item.pubDate.text(),
                    guid   : (item.guid.text() ?: item.link.text())
                ]
            }

            boolean firstRun = (state.seenGuids == null)
            List<String> seenGuids = (state.seenGuids ?: []) as List<String>

            List newItems = firstRun ? [] : items.findAll { !seenGuids.contains(it.guid) }
            newItems = applyHazardFilter(newItems)

            newItems.each { w ->
                raiseAlert("BOM Warning: ${w.title} - ${w.link}", "BOM weather warning. ${w.title}", 'BOM Weather Warning')
            }

            List<String> updatedGuids = (items.collect { it.guid } + seenGuids).unique()
            if (updatedGuids.size() > 200) updatedGuids = updatedGuids[0..199]
            state.seenGuids = updatedGuids

            state.lastItemCount = items.size()
            state.lastWarnings = items.take(10).collect { [title: it.title, pubDate: it.pubDate] }
            state.lastError = null
        }
    } catch (Exception e) {
        state.lastError = e.message
        log.warn "BOM Weather Alerts: feed check failed - ${e.message}"
    }

    state.lastPollAt = new Date().format('yyyy-MM-dd HH:mm:ss')
    scheduleNextPoll()
}

List applyHazardFilter(List items) {
    String filter = settings.hazardFilter
    if (!filter?.trim()) return items
    List<String> terms = filter.split(',').collect { it.trim().toLowerCase() }.findAll { it }
    if (!terms) return items
    return items.findAll { item ->
        String t = item.title?.toLowerCase() ?: ''
        terms.any { t.contains(it) }
    }
}

def scheduleNextPoll() {
    Integer minutes = (settings.pollMinutes ?: 10) as Integer
    if (minutes < 1) minutes = 1
    runIn(minutes * 60, pollFeed)
}

def raiseAlert(String notifyMsg, String speakMsg = null, String subject = 'BOM Weather Alerts') {
    if (isQuietHours()) {
        recordQuietHoursEvent(notifyMsg, speakMsg ?: notifyMsg)
        return
    }
    sendNotification(notifyMsg, subject)
    speakAlert(speakMsg ?: notifyMsg)
}

// Fails open (treats as NOT quiet hours) on any error, so a bad time
// value can never accidentally suppress a real alert.
//
// Deliberately NOT using Hubitat's timeOfDayIsBetween() here - confirmed
// live (2026-08-09) that an overnight window (21:30-06:00) let a
// temperature alert through at 23:04 instead of holding it, matching a
// bug already found and fixed the same way in Critical Device Monitor.
// This reduces start/end/now to minutes-since-midnight in the hub's own
// timezone and compares them directly, so the overnight-wrap case is
// explicit, auditable logic rather than depending on unverified platform
// behavior.
boolean isQuietHours() {
    if (!settings.enableQuietHours || !settings.quietHoursStart || !settings.quietHoursEnd) return false
    try {
        def tz = location.timeZone
        def startCal = Calendar.getInstance(tz)
        startCal.setTime(toDateTime(settings.quietHoursStart))
        def endCal = Calendar.getInstance(tz)
        endCal.setTime(toDateTime(settings.quietHoursEnd))
        def nowCal = Calendar.getInstance(tz)
        nowCal.setTime(new Date())

        def startMin = startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
        def endMin   = endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)
        def nowMin   = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

        if (startMin == endMin) return false
        if (startMin < endMin) {
            return nowMin >= startMin && nowMin < endMin
        }
        return nowMin >= startMin || nowMin < endMin
    } catch (e) {
        log.warn "BOM Weather Alerts: could not evaluate quiet hours, treating as not-quiet: ${e.message}"
        return false
    }
}

def recordQuietHoursEvent(String notifyMsg, String speakMsg) {
    List<String> notifyEvents = (state.quietHoursNotifyEvents ?: []) as List<String>
    notifyEvents << notifyMsg
    state.quietHoursNotifyEvents = notifyEvents

    List<String> speakEvents = (state.quietHoursSpeakEvents ?: []) as List<String>
    speakEvents << speakMsg
    state.quietHoursSpeakEvents = speakEvents
}

def sendQuietHoursSummary() {
    if (settings.enableRainForecast) {
        fetchRainForecast()
    }

    List<String> notifyParts = (state.quietHoursNotifyEvents ?: []) as List<String>
    List<String> speakParts = (state.quietHoursSpeakEvents ?: []) as List<String>
    state.quietHoursNotifyEvents = []
    state.quietHoursSpeakEvents = []

    String rain = settings.enableRainForecast ? rainSummaryText() : null
    if (rain) {
        notifyParts << rain
        speakParts << rain
    }

    if (atomicState.tempHighAlerted) {
        String msg = "Temperature alert: still above the high threshold as quiet hours end (currently ${state.lastTemp}°C)."
        notifyParts << msg
        speakParts << msg
    } else if (atomicState.tempLowAlerted) {
        String msg = "Temperature alert: still below the low threshold as quiet hours end (currently ${state.lastTemp}°C)."
        notifyParts << msg
        speakParts << msg
    }

    if (!notifyParts) return

    String notify = "BOM Weather Alerts - quiet hours summary (${notifyParts.size()} item${notifyParts.size() == 1 ? '' : 's'}): " + notifyParts.join(' | ')
    String speak = "Daily BOM Alert: " + speakParts.join(' ')
    sendNotification(notify, 'BOM Weather Alerts - Quiet Hours Summary')
    speakAlert(speak)
}

def temperatureEventHandler(evt) {
    checkTemperature(evt.doubleValue)
}

def checkTemperatureNow() {
    if (!settings.tempSensor) return
    def val = settings.tempSensor.currentValue('temperature')
    if (val == null) return
    checkTemperature(val as Double)
}

def checkTemperature(Double temp) {
    if (temp == null) return
    state.lastTemp = temp
    state.lastTempAt = new Date().format('yyyy-MM-dd HH:mm:ss')

    if (settings.tempHighThreshold != null) {
        Double high = settings.tempHighThreshold as Double
        boolean above = temp > high
        if (above && !atomicState.tempHighAlerted) {
            atomicState.tempHighAlerted = true
            raiseAlert("Temperature alert: currently ${temp}°C, above the high threshold of ${high}°C.", null, 'Temperature Alert - High')
        } else if (!above && atomicState.tempHighAlerted) {
            atomicState.tempHighAlerted = false
        }
    }

    if (settings.tempLowThreshold != null) {
        Double low = settings.tempLowThreshold as Double
        boolean below = temp < low
        if (below && !atomicState.tempLowAlerted) {
            atomicState.tempLowAlerted = true
            raiseAlert("Temperature alert: currently ${temp}°C, below the low threshold of ${low}°C.", null, 'Temperature Alert - Low')
        } else if (!below && atomicState.tempLowAlerted) {
            atomicState.tempLowAlerted = false
        }
    }
}

def fetchRainForecast() {
    String location = settings.rainLocation
    if (!location) return

    Map params = [
        uri    : 'https://www.bom.gov.au/fwo/IDW14199.xml',
        textParser: true,
        timeout: 20,
        headers: [
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
        ]
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status != 200) {
                state.rainError = "Forecast product returned HTTP ${resp.status}"
                return
            }

            def data = resp.data
            String body = (data instanceof String) ? data : (data?.text ?: data?.toString())
            def xml = new XmlSlurper().parseText(body)

            def area = xml.forecast.area.find { it.@description.text() == location && it.@type.text() == 'location' }
            if (!area) {
                state.rainError = "No forecast area named '${location}' found."
                return
            }

            def today = area.'forecast-period'[0]
            String precis = today.text.find { it.@type.text() == 'precis' }?.text() ?: ''
            String pop = today.text.find { it.@type.text() == 'probability_of_precipitation' }?.text() ?: ''
            String range = today.element.find { it.@type.text() == 'precipitation_range' }?.text() ?: ''

            state.rainPrecis = precis
            state.rainProbability = pop
            state.rainRange = range
            state.rainCheckedAt = new Date().format('yyyy-MM-dd HH:mm:ss')
            state.rainError = null
        }
    } catch (Exception e) {
        state.rainError = e.message
        log.warn "BOM Weather Alerts: rain forecast check failed - ${e.message}"
    }
}

String rainSummaryText() {
    if (!state.rainPrecis && !state.rainProbability) return null
    StringBuilder sb = new StringBuilder("Today's forecast for ${settings.rainLocation}: ")
    if (state.rainPrecis) sb << state.rainPrecis
    if (state.rainProbability) sb << " ${state.rainProbability} chance of rain."
    if (state.rainRange) sb << " Possible rainfall ${state.rainRange}."
    return sb.toString().trim()
}

def sendNotification(String msg, String subject = 'BOM Weather Alerts') {
    if (!settings.notifiers) {
        log.warn "BOM Weather Alerts: no notification devices configured - alert not sent: ${msg}"
        return
    }
    settings.notifiers.each { dev ->
        if (settings.gmailGroup && dev.typeName == 'Gmail Notification Gateway') {
            dev.deviceNotification("Group: ${settings.gmailGroup},Subject: ${subject},${msg}")
        } else {
            dev.deviceNotification(msg)
        }
    }
}

def speakAlert(String msg) {
    if (!settings.speechDevices) return
    settings.speechDevices.each { it.speak(msg) }
}
