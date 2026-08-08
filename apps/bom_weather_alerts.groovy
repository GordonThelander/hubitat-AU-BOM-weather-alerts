/*
 * BOM Weather Alerts
 * Namespace: Hubitat Integrations
 * Version: 1.3.1
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
        if (state.tempHighAlerted) sb << 'Above high threshold - alerting<br>'
        if (state.tempLowAlerted) sb << 'Below low threshold - alerting<br>'
    }
    return sb.toString()
}

def appButtonHandler(btn) {
    if (btn == 'btnCheckNow') {
        pollFeed()
    } else if (btn == 'btnTestNotify') {
        sendNotification('Test notification from BOM Weather Alerts.')
    } else if (btn == 'btnTestSpeaker') {
        speakAlert('This is a test of the BOM Weather Alerts speaker announcement.')
    } else if (btn == 'btnTestTemp') {
        checkTemperatureNow()
    } else if (btn == 'btnTestSummary') {
        sendQuietHoursSummary()
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
                raiseAlert("BOM Warning: ${w.title} - ${w.link}", "BOM weather warning. ${w.title}")
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

def raiseAlert(String notifyMsg, String speakMsg = null) {
    if (isQuietHours()) {
        recordQuietHoursEvent(notifyMsg)
        return
    }
    sendNotification(notifyMsg)
    speakAlert(speakMsg ?: notifyMsg)
}

boolean isQuietHours() {
    if (!settings.enableQuietHours || !settings.quietHoursStart || !settings.quietHoursEnd) return false
    return timeOfDayIsBetween(timeToday(settings.quietHoursStart), timeToday(settings.quietHoursEnd), new Date(), location.timeZone)
}

def recordQuietHoursEvent(String msg) {
    List<String> events = (state.quietHoursEvents ?: []) as List<String>
    events << msg
    state.quietHoursEvents = events
}

def sendQuietHoursSummary() {
    List<String> events = (state.quietHoursEvents ?: []) as List<String>
    if (events) {
        sendNotification("BOM Weather Alerts - quiet hours summary (${events.size()} item${events.size() == 1 ? '' : 's'}): " + events.join(' | '))
        speakAlert("BOM weather alerts summary. ${events.size()} item${events.size() == 1 ? '' : 's'} occurred during quiet hours.")
    }
    state.quietHoursEvents = []

    if (state.tempHighAlerted) {
        sendNotification("Temperature alert: still above the high threshold as quiet hours end (currently ${state.lastTemp}°C).")
        speakAlert("Temperature alert. Still above the high threshold as quiet hours end.")
    } else if (state.tempLowAlerted) {
        sendNotification("Temperature alert: still below the low threshold as quiet hours end (currently ${state.lastTemp}°C).")
        speakAlert("Temperature alert. Still below the low threshold as quiet hours end.")
    }
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
        if (above && !state.tempHighAlerted) {
            state.tempHighAlerted = true
            raiseAlert("Temperature alert: currently ${temp}°C, above the high threshold of ${high}°C.")
        } else if (!above && state.tempHighAlerted) {
            state.tempHighAlerted = false
        }
    }

    if (settings.tempLowThreshold != null) {
        Double low = settings.tempLowThreshold as Double
        boolean below = temp < low
        if (below && !state.tempLowAlerted) {
            state.tempLowAlerted = true
            raiseAlert("Temperature alert: currently ${temp}°C, below the low threshold of ${low}°C.")
        } else if (!below && state.tempLowAlerted) {
            state.tempLowAlerted = false
        }
    }
}

def sendNotification(String msg) {
    if (!settings.notifiers) {
        log.warn "BOM Weather Alerts: no notification devices configured - alert not sent: ${msg}"
        return
    }
    settings.notifiers.each { it.deviceNotification(msg) }
}

def speakAlert(String msg) {
    if (!settings.speechDevices) return
    settings.speechDevices.each { it.speak(msg) }
}
