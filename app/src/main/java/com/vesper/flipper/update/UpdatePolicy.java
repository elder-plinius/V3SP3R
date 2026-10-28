package com.vesper.flipper.update;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline selection of declared release metadata; performs no network or APK operations.
 * Signer metadata is only a declaration, not verification of the binary: the Android
 * package installer must still enforce the actual APK signing identity on installation.
 * Identifiers are matched exactly, and an offer can never lower the installed versionCode.
 */
public final class UpdatePolicy {
    private UpdatePolicy() {}

    public enum Status { AVAILABLE, CURRENT, AHEAD, NO_RELEASES, INCOMPATIBLE }

    public static final class Installed {
        public final String repository, channel, packageName;
        public final long versionCode;
        public final int sdkInt;
        public final Set<String> signerSha256, requiredCapabilities;

        public Installed(String repository, String channel, String packageName, long versionCode,
                         int sdkInt, Set<String> signerSha256, Set<String> requiredCapabilities) {
            this.repository = repository;
            this.channel = channel;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.sdkInt = sdkInt;
            this.signerSha256 = snapshot(signerSha256);
            this.requiredCapabilities = snapshot(requiredCapabilities);
        }
    }

    public static final class Release {
        public final String repository, channel, packageName;
        public final long versionCode;
        public final int minSdk;
        public final Set<String> signerSha256, capabilities;
        public final String versionName, htmlUrl;
        public final boolean draft, prerelease, hasApkAsset;

        public Release(String repository, String channel, String packageName, long versionCode,
                       int minSdk, Set<String> signerSha256, Set<String> capabilities,
                       String versionName, String htmlUrl, boolean draft, boolean prerelease,
                       boolean hasApkAsset) {
            this.repository = repository;
            this.channel = channel;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.minSdk = minSdk;
            this.signerSha256 = snapshot(signerSha256);
            this.capabilities = snapshot(capabilities);
            this.versionName = versionName;
            this.htmlUrl = htmlUrl;
            this.draft = draft;
            this.prerelease = prerelease;
            this.hasApkAsset = hasApkAsset;
        }
    }

    public static final class Decision {
        public final Status status;
        public final String message, releaseUrl, versionName;

        private Decision(Status status, String message, String releaseUrl, String versionName) {
            this.status = status;
            this.message = message;
            this.releaseUrl = releaseUrl;
            this.versionName = versionName;
        }
    }

    private static Set<String> snapshot(Set<String> values) {
        return values == null ? null : Collections.unmodifiableSet(new HashSet<>(values));
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static boolean validIdentity(String repository, String channel, String packageName,
                                         long code, int sdk, Set<String> signers, Set<String> capabilities) {
        return matches(repository, "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}")
                && matches(channel, "[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                && matches(packageName, "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                && packageName.length() <= 255 && code > 0 && sdk > 0
                && validSet(signers, "[A-Fa-f0-9]{64}", true)
                && validSet(capabilities, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", false);
    }

    private static boolean validSet(Set<String> values, String pattern, boolean requireNonempty) {
        if (values == null || (requireNonempty && values.isEmpty())) return false;
        for (String value : values) if (!matches(value, pattern)) return false;
        return true;
    }

    private static boolean validVersionName(String name) {
        if (name == null || name.length() > 128) return false;
        boolean visible = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c)) return false;
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) visible = true;
        }
        return visible;
    }

    private static boolean validReleaseUrl(String url, String repository) {
        if (url == null || url.length() > 2048) return false;
        try {
            java.net.URI uri = new java.net.URI(url);
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getRawAuthority())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            String path = uri.getRawPath();
            String prefix = "/" + repository + "/releases/tag/";
            if (path == null || !path.startsWith(prefix)) return false;
            // Fail closed on encoded or ambiguous paths; never decode or normalize a repository.
            String tag = path.substring(prefix.length());
            if (!tag.matches("[A-Za-z0-9._~/-]+")) return false;
            for (String segment : tag.split("/", -1))
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
            return true;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    public static Decision evaluate(Installed installed, List<Release> releases) {
        if (installed == null || !validIdentity(installed.repository, installed.channel,
                installed.packageName, installed.versionCode, installed.sdkInt,
                installed.signerSha256, installed.requiredCapabilities) || releases == null)
            return new Decision(Status.INCOMPATIBLE, "Installed identity or release metadata is missing or invalid; keep this build.", null, null);
        if (releases.isEmpty())
            return new Decision(Status.NO_RELEASES, "No published releases are available; keep this build.", null, null);
        Release best = null;
        for (Release release : releases) {
            if (release != null
                    && validIdentity(release.repository, release.channel, release.packageName,
                            release.versionCode, release.minSdk, release.signerSha256, release.capabilities)
                    && validVersionName(release.versionName)
                    && validReleaseUrl(release.htmlUrl, installed.repository)
                    && installed.repository.equals(release.repository)
                    && installed.channel.equals(release.channel)
                    && installed.packageName.equals(release.packageName)
                    && installed.signerSha256.equals(release.signerSha256)
                    && release.capabilities.containsAll(installed.requiredCapabilities)
                    && release.minSdk <= installed.sdkInt
                    && !release.draft && !release.prerelease && release.hasApkAsset
                    && (best == null || release.versionCode > best.versionCode)) best = release;
        }
        if (best == null)
            return new Decision(Status.INCOMPATIBLE, "No safely compatible release metadata; keep this build.", null, null);
        if (best.versionCode == installed.versionCode)
            return new Decision(Status.CURRENT, "Installed version matches the newest compatible release.", null, best.versionName);
        if (best.versionCode < installed.versionCode)
            return new Decision(Status.AHEAD, "Installed version is newer than compatible releases; keep this build.", null, best.versionName);
        return new Decision(Status.AVAILABLE, "Newer compatible release metadata is available.",
                best.htmlUrl, best.versionName);
    }
}
