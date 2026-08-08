/*
 * BOM Weather Alerts
 * Namespace: Hubitat Integrations
 * Version: 1.1.0
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
        section('Snooze') {
            paragraph 'Silences both notification and speaker alerts for a set time. Warnings that arrive while snoozed are still recorded as seen, so nothing floods in once the snooze ends - only the alert itself is held back.'
            input 'snoozeHours', 'number', title: 'Snooze duration (hours)', defaultValue: 1, range: '1..48', required: true
            input 'btnSnooze', 'button', title: 'Snooze alerts'
            input 'btnUnsnooze', 'button', title: 'Cancel snooze'
        }
        section('Status') {
            paragraph statusText()
        }
    }
}

def statusText() {
    StringBuilder sb = new StringBuilder()
    if (isSnoozed()) {
        sb << "<b>Alerts snoozed until: ${new Date(state.snoozedUntil).format('yyyy-MM-dd HH:mm:ss')}</b><br><br>"
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
    return sb.toString()
}

def appButtonHandler(btn) {
    if (btn == 'btnCheckNow') {
        pollFeed()
    } else if (btn == 'btnTestNotify') {
        sendNotification('Test notification from BOM Weather Alerts.')
    } else if (btn == 'btnTestSpeaker') {
        speakAlert('This is a test of the BOM Weather Alerts speaker announcement.')
    } else if (btn == 'btnSnooze') {
        snoozeAlerts()
    } else if (btn == 'btnUnsnooze') {
        state.snoozedUntil = null
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    unschedule()
    runIn(5, pollFeed)
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
                raiseAlert(w)
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

def raiseAlert(Map warning) {
    if (isSnoozed()) return
    sendNotification("BOM Warning: ${warning.title} - ${warning.link}")
    speakAlert("BOM weather warning. ${warning.title}")
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

boolean isSnoozed() {
    return state.snoozedUntil && now() < state.snoozedUntil
}

def snoozeAlerts() {
    Integer hours = (settings.snoozeHours ?: 1) as Integer
    if (hours < 1) hours = 1
    state.snoozedUntil = now() + (hours * 60 * 60 * 1000L)
}
