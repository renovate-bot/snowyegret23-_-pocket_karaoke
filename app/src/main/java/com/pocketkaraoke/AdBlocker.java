package com.pocketkaraoke;

import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class AdBlocker {
    private static final long UPDATE_INTERVAL_MS = 3L * 24L * 60L * 60L * 1000L;
    private static final String FILTER_DIR = "adblock_filters";
    private static final String META_FILE = "last_update.txt";
    private static final String USER_AGENT = "PocketKaraoke/0.1";
    private static final FilterSource[] FILTER_SOURCES = {
            new FilterSource("adguard-base.txt", "https://filters.adtidy.org/extension/ublock/filters/2.txt"),
            new FilterSource("adguard-tracking.txt", "https://filters.adtidy.org/extension/ublock/filters/3.txt"),
            new FilterSource("ublock-filters.txt", "https://ublockorigin.github.io/uAssets/filters/filters.min.txt"),
            new FilterSource("ublock-privacy.txt", "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt"),
            new FilterSource("ublock-quick-fixes.txt", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt"),
            new FilterSource("list-kr.txt", "https://cdn.jsdelivr.net/npm/@list-kr/filterslists@latest/dist/filterslist-uBlockOrigin-classic.txt")
    };
    private static final String[] FALLBACK_RULES = {
            "||2mdn.net^",
            "||adnxs.com^",
            "||adsrvr.org^",
            "||amazon-adsystem.com^",
            "||criteo.com^",
            "||doubleclick.net^",
            "||google-analytics.com^",
            "||googleadservices.com^",
            "||googlesyndication.com^",
            "||googletagmanager.com^",
            "||googletagservices.com^",
            "||outbrain.com^",
            "||pubmatic.com^",
            "||taboola.com^",
            "/pagead/",
            "/prebid"
    };

    private final File filterDir;
    private volatile FilterSet filterSet = FilterSet.fallback();
    private volatile boolean refreshRunning;

    AdBlocker(File filesDir) {
        filterDir = new File(filesDir, FILTER_DIR);
    }

    void start(Listener listener) {
        runRefresh(false, listener);
    }

    void refresh(Listener listener) {
        runRefresh(true, listener);
    }

    boolean shouldBlock(Uri uri, String documentHost, boolean isMainFrame) {
        if (uri == null || isMainFrame) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = normalizeHost(uri.getHost());
        if (host.isEmpty()) {
            return false;
        }
        return filterSet.shouldBlock(uri.toString(), host, normalizeHost(documentHost));
    }

    int getRuleCount() {
        return filterSet.ruleCount;
    }

    private void runRefresh(boolean forceDownload, Listener listener) {
        if (refreshRunning) {
            return;
        }
        refreshRunning = true;
        Thread thread = new Thread(() -> {
            boolean changed = false;
            try {
                FilterSet cached = loadCachedFilters();
                if (cached.ruleCount > 0) {
                    filterSet = cached;
                    changed = true;
                    notifyListener(listener, false);
                }
                if (forceDownload || shouldUpdate()) {
                    if (downloadFilters()) {
                        FilterSet updated = loadCachedFilters();
                        if (updated.ruleCount > 0) {
                            filterSet = updated;
                            changed = true;
                            notifyListener(listener, true);
                        }
                    }
                }
                if (!changed) {
                    notifyListener(listener, false);
                }
            } finally {
                refreshRunning = false;
            }
        }, "PocketKaraokeAdBlocker");
        thread.start();
    }

    private void notifyListener(Listener listener, boolean updated) {
        if (listener != null) {
            listener.onFiltersChanged(updated);
        }
    }

    private FilterSet loadCachedFilters() {
        FilterBuilder builder = new FilterBuilder();
        for (String rule : FALLBACK_RULES) {
            builder.addLine(rule);
        }
        if (filterDir.isDirectory()) {
            for (FilterSource source : FILTER_SOURCES) {
                File file = new File(filterDir, source.fileName);
                if (file.isFile()) {
                    builder.addFile(file);
                }
            }
        }
        return builder.build();
    }

    private boolean shouldUpdate() {
        File meta = new File(filterDir, META_FILE);
        if (!meta.isFile()) {
            return true;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(meta), StandardCharsets.UTF_8))) {
            long lastUpdate = Long.parseLong(reader.readLine());
            return System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MS;
        } catch (IOException | NumberFormatException e) {
            return true;
        }
    }

    private boolean downloadFilters() {
        if (!filterDir.isDirectory() && !filterDir.mkdirs()) {
            return false;
        }
        boolean downloadedAny = false;
        for (FilterSource source : FILTER_SOURCES) {
            File target = new File(filterDir, source.fileName);
            File temp = new File(filterDir, source.fileName + ".tmp");
            try {
                downloadToFile(source.url, temp);
                if (target.exists() && !target.delete()) {
                    continue;
                }
                if (temp.renameTo(target)) {
                    downloadedAny = true;
                }
            } catch (IOException ignored) {
                if (temp.exists()) {
                    temp.delete();
                }
            }
        }
        if (downloadedAny) {
            writeLastUpdate();
        }
        return downloadedAny;
    }

    private void downloadToFile(String url, File file) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(true);
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP " + statusCode);
        }
        try (InputStream inputStream = connection.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void writeLastUpdate() {
        File meta = new File(filterDir, META_FILE);
        try (FileOutputStream outputStream = new FileOutputStream(meta)) {
            outputStream.write(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        return host.toLowerCase(Locale.US);
    }

    interface Listener {
        void onFiltersChanged(boolean updated);
    }

    private static final class FilterSource {
        final String fileName;
        final String url;

        FilterSource(String fileName, String url) {
            this.fileName = fileName;
            this.url = url;
        }
    }

    private static final class FilterBuilder {
        private final Set<String> blockedHosts = new HashSet<>();
        private final Set<String> allowedHosts = new HashSet<>();
        private final List<Rule> blockRules = new ArrayList<>();
        private final List<Rule> allowRules = new ArrayList<>();

        void addFile(File file) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addLine(line);
                }
            } catch (IOException ignored) {
            }
        }

        void addLine(String rawLine) {
            Rule rule = Rule.parse(rawLine);
            if (rule == null) {
                return;
            }
            if (rule.exception) {
                if (rule.isPlainHostRule()) {
                    allowedHosts.add(rule.hostSuffix);
                } else {
                    allowRules.add(rule);
                }
                return;
            }
            if (rule.isPlainHostRule()) {
                blockedHosts.add(rule.hostSuffix);
            } else {
                blockRules.add(rule);
            }
        }

        FilterSet build() {
            return new FilterSet(blockedHosts, allowedHosts, blockRules, allowRules);
        }
    }

    private static final class FilterSet {
        final Set<String> blockedHosts;
        final Set<String> allowedHosts;
        final List<Rule> blockRules;
        final List<Rule> allowRules;
        final int ruleCount;

        FilterSet(Set<String> blockedHosts, Set<String> allowedHosts, List<Rule> blockRules, List<Rule> allowRules) {
            this.blockedHosts = Collections.unmodifiableSet(new HashSet<>(blockedHosts));
            this.allowedHosts = Collections.unmodifiableSet(new HashSet<>(allowedHosts));
            this.blockRules = Collections.unmodifiableList(new ArrayList<>(blockRules));
            this.allowRules = Collections.unmodifiableList(new ArrayList<>(allowRules));
            this.ruleCount = blockedHosts.size() + allowedHosts.size() + blockRules.size() + allowRules.size();
        }

        static FilterSet fallback() {
            FilterBuilder builder = new FilterBuilder();
            for (String rule : FALLBACK_RULES) {
                builder.addLine(rule);
            }
            return builder.build();
        }

        boolean shouldBlock(String url, String requestHost, String documentHost) {
            String normalizedUrl = url.toLowerCase(Locale.US);
            if (matchesHost(allowedHosts, requestHost) || matchesAny(allowRules, normalizedUrl, requestHost, documentHost)) {
                return false;
            }
            return matchesHost(blockedHosts, requestHost)
                    || matchesAny(blockRules, normalizedUrl, requestHost, documentHost);
        }

        private boolean matchesAny(List<Rule> rules, String url, String requestHost, String documentHost) {
            for (Rule rule : rules) {
                if (rule.matches(url, requestHost, documentHost)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesHost(Set<String> hosts, String requestHost) {
            String host = requestHost;
            while (!host.isEmpty()) {
                if (hosts.contains(host)) {
                    return true;
                }
                int dotIndex = host.indexOf('.');
                if (dotIndex < 0 || dotIndex == host.length() - 1) {
                    return false;
                }
                host = host.substring(dotIndex + 1);
            }
            return false;
        }
    }

    private static final class Rule {
        final boolean exception;
        final String hostSuffix;
        final String substring;
        final Pattern pattern;
        final DomainConstraint domainConstraint;
        final Boolean thirdParty;

        Rule(
                boolean exception,
                String hostSuffix,
                String substring,
                Pattern pattern,
                DomainConstraint domainConstraint,
                Boolean thirdParty) {
            this.exception = exception;
            this.hostSuffix = hostSuffix;
            this.substring = substring;
            this.pattern = pattern;
            this.domainConstraint = domainConstraint;
            this.thirdParty = thirdParty;
        }

        static Rule parse(String rawLine) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("#")) {
                return null;
            }
            Rule hostsRule = parseHostsRule(line);
            if (hostsRule != null) {
                return hostsRule;
            }
            if (isCosmeticRule(line)) {
                return null;
            }
            boolean exception = line.startsWith("@@");
            if (exception) {
                line = line.substring(2);
            }
            int optionsIndex = line.indexOf('$');
            String optionsText = "";
            if (optionsIndex >= 0) {
                optionsText = line.substring(optionsIndex + 1);
                line = line.substring(0, optionsIndex);
            }
            Options options = Options.parse(optionsText);
            if (options == null || line.isEmpty()) {
                return null;
            }
            String hostSuffix = extractSimpleHost(line);
            String substring = null;
            Pattern pattern = null;
            if (hostSuffix == null) {
                if (isPlainSubstring(line)) {
                    substring = line.toLowerCase(Locale.US);
                } else {
                    pattern = compileFilterPattern(line);
                    if (pattern == null) {
                        return null;
                    }
                }
            }
            return new Rule(exception, hostSuffix, substring, pattern, options.domainConstraint, options.thirdParty);
        }

        boolean isPlainHostRule() {
            return hostSuffix != null && domainConstraint == null && thirdParty == null;
        }

        boolean matches(String url, String requestHost, String documentHost) {
            if (domainConstraint != null && !domainConstraint.matches(documentHost)) {
                return false;
            }
            if (thirdParty != null && thirdParty.booleanValue() != isThirdParty(requestHost, documentHost)) {
                return false;
            }
            if (hostSuffix != null) {
                return requestHost.equals(hostSuffix) || requestHost.endsWith("." + hostSuffix);
            }
            if (substring != null) {
                return url.contains(substring);
            }
            return pattern != null && pattern.matcher(url).find();
        }

        private static Rule parseHostsRule(String line) {
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                return null;
            }
            if (!"0.0.0.0".equals(parts[0]) && !"127.0.0.1".equals(parts[0])) {
                return null;
            }
            String host = parts[1].toLowerCase(Locale.US);
            if (!isValidHost(host)) {
                return null;
            }
            return new Rule(false, host, null, null, null, null);
        }

        private static boolean isCosmeticRule(String line) {
            return line.contains("##")
                    || line.contains("#@#")
                    || line.contains("#?#")
                    || line.contains("#$#")
                    || line.contains("#%#");
        }

        private static String extractSimpleHost(String line) {
            if (!line.startsWith("||")) {
                return null;
            }
            String body = line.substring(2);
            int index = 0;
            while (index < body.length()) {
                char c = body.charAt(index);
                if ((c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || c == '.'
                        || c == '-') {
                    index++;
                    continue;
                }
                break;
            }
            if (index == 0) {
                return null;
            }
            String host = body.substring(0, index).toLowerCase(Locale.US);
            String rest = body.substring(index);
            if (!rest.isEmpty() && !"^".equals(rest) && !"^|".equals(rest) && !"|".equals(rest)) {
                return null;
            }
            return isValidHost(host) ? host : null;
        }

        private static boolean isPlainSubstring(String line) {
            return line.indexOf('*') < 0
                    && line.indexOf('^') < 0
                    && line.indexOf('|') < 0
                    && !line.startsWith("/");
        }

        private static Pattern compileFilterPattern(String line) {
            boolean anchoredStart = false;
            boolean anchoredEnd = false;
            String prefixRegex = "";
            if (line.startsWith("||")) {
                prefixRegex = "^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?";
                line = line.substring(2);
            } else if (line.startsWith("|")) {
                anchoredStart = true;
                line = line.substring(1);
            }
            if (line.endsWith("|")) {
                anchoredEnd = true;
                line = line.substring(0, line.length() - 1);
            }
            if (line.length() > 1 && line.startsWith("/") && line.endsWith("/")) {
                try {
                    return Pattern.compile(line.substring(1, line.length() - 1), Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    return null;
                }
            }
            StringBuilder regex = new StringBuilder();
            regex.append(prefixRegex);
            if (anchoredStart) {
                regex.append('^');
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '^') {
                    regex.append("(?:[^A-Za-z0-9_.%-]|$)");
                } else if ("\\.[]{}()+-?,$".indexOf(c) >= 0) {
                    regex.append('\\').append(c);
                } else {
                    regex.append(c);
                }
            }
            if (anchoredEnd) {
                regex.append('$');
            }
            try {
                return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }

        private static boolean isThirdParty(String requestHost, String documentHost) {
            if (documentHost == null || documentHost.isEmpty()) {
                return true;
            }
            return !requestHost.equals(documentHost) && !requestHost.endsWith("." + documentHost);
        }
    }

    private static final class Options {
        final DomainConstraint domainConstraint;
        final Boolean thirdParty;

        Options(DomainConstraint domainConstraint, Boolean thirdParty) {
            this.domainConstraint = domainConstraint;
            this.thirdParty = thirdParty;
        }

        static Options parse(String text) {
            if (text == null || text.isEmpty()) {
                return new Options(null, null);
            }
            DomainConstraint domainConstraint = null;
            Boolean thirdParty = null;
            String[] options = text.split(",");
            for (String rawOption : options) {
                String option = rawOption.trim().toLowerCase(Locale.US);
                if (option.isEmpty() || "important".equals(option)) {
                    continue;
                }
                if ("badfilter".equals(option) || isUnsupportedOption(option)) {
                    return null;
                }
                if ("third-party".equals(option)) {
                    thirdParty = Boolean.TRUE;
                    continue;
                }
                if ("~third-party".equals(option)) {
                    thirdParty = Boolean.FALSE;
                    continue;
                }
                if (option.startsWith("domain=")) {
                    domainConstraint = DomainConstraint.parse(option.substring("domain=".length()));
                    continue;
                }
                if (isResourceTypeOption(option)) {
                    continue;
                }
                if (option.contains("=")) {
                    return null;
                }
            }
            return new Options(domainConstraint, thirdParty);
        }

        private static boolean isUnsupportedOption(String option) {
            return "document".equals(option)
                    || "elemhide".equals(option)
                    || "generichide".equals(option)
                    || "jsinject".equals(option)
                    || "popup".equals(option)
                    || "popunder".equals(option)
                    || "specifichide".equals(option)
                    || option.startsWith("csp")
                    || option.startsWith("denyallow")
                    || option.startsWith("header")
                    || option.startsWith("permissions")
                    || option.startsWith("redirect")
                    || option.startsWith("removeparam")
                    || option.startsWith("replace")
                    || option.startsWith("urlskip")
                    || option.startsWith("urltransform");
        }

        private static boolean isResourceTypeOption(String option) {
            String positive = option.startsWith("~") ? option.substring(1) : option;
            return "all".equals(positive)
                    || "css".equals(positive)
                    || "font".equals(positive)
                    || "image".equals(positive)
                    || "media".equals(positive)
                    || "object".equals(positive)
                    || "other".equals(positive)
                    || "ping".equals(positive)
                    || "script".equals(positive)
                    || "stylesheet".equals(positive)
                    || "subdocument".equals(positive)
                    || "websocket".equals(positive)
                    || "xmlhttprequest".equals(positive);
        }
    }

    private static final class DomainConstraint {
        final Set<String> includes;
        final Set<String> excludes;

        DomainConstraint(Set<String> includes, Set<String> excludes) {
            this.includes = includes;
            this.excludes = excludes;
        }

        static DomainConstraint parse(String value) {
            Set<String> includes = new HashSet<>();
            Set<String> excludes = new HashSet<>();
            String[] domains = value.split("\\|");
            for (String rawDomain : domains) {
                String domain = rawDomain.trim().toLowerCase(Locale.US);
                if (domain.isEmpty() || domain.contains("*")) {
                    continue;
                }
                if (domain.startsWith("~")) {
                    domain = domain.substring(1);
                    if (isValidHost(domain)) {
                        excludes.add(domain);
                    }
                } else if (isValidHost(domain)) {
                    includes.add(domain);
                }
            }
            return new DomainConstraint(includes, excludes);
        }

        boolean matches(String documentHost) {
            if (documentHost == null || documentHost.isEmpty()) {
                return includes.isEmpty();
            }
            for (String domain : excludes) {
                if (documentHost.equals(domain) || documentHost.endsWith("." + domain)) {
                    return false;
                }
            }
            if (includes.isEmpty()) {
                return true;
            }
            for (String domain : includes) {
                if (documentHost.equals(domain) || documentHost.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean isValidHost(String host) {
        return host.indexOf('.') > 0
                && host.indexOf('/') < 0
                && host.indexOf('*') < 0
                && host.indexOf(':') < 0;
    }
}
