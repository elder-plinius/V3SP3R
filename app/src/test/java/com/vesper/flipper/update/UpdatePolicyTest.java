package com.vesper.flipper.update;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;
import static com.vesper.flipper.update.UpdatePolicy.*;

public class UpdatePolicyTest {
    private static final String REPO = "example/device-app";
    private static final String PACKAGE = "com.example.device";
    private static final String SIGNER = "a".repeat(64);
    private static final String SECOND_SIGNER = "b".repeat(64);
    private static final String URL = "https://github.com/" + REPO + "/releases/tag/v3";
    private static final Set<String> SIGNERS = Set.of(SIGNER);
    private static final Set<String> CAPS = Set.of("provider.openai", "provider.local");

    private Installed installed(long code) {
        return new Installed(REPO, "stable", PACKAGE, code, 33, SIGNERS, CAPS);
    }

    private Release release(long code) {
        return new Release(REPO, "stable", PACKAGE, code, 26, SIGNERS, CAPS,
                "version-" + code, URL, false, false, true);
    }

    private void assertStatus(Status expected, Installed installed, Release... releases) {
        Decision decision = evaluate(installed, Arrays.asList(releases));
        assertEquals(expected, decision.status);
        assertNotNull(decision.message);
        assertFalse(decision.message.isBlank());
        if (expected != Status.AVAILABLE) assertNull(decision.releaseUrl);
    }

    @Test public void emptyListIsDistinctFromUnknownOrNullReleaseMetadata() {
        assertStatus(Status.NO_RELEASES, installed(2));
        assertEquals(Status.INCOMPATIBLE, evaluate(installed(2), null).status);
        assertStatus(Status.INCOMPATIBLE, installed(2), (Release) null);
        assertStatus(Status.AVAILABLE, installed(2), null, release(3));
        assertStatus(Status.INCOMPATIBLE, null, release(3));
        assertStatus(Status.INCOMPATIBLE, null);
    }

    @Test public void installedIdentityMustBeValidBeforeConsideringAnyRelease() {
        List<Installed> malformed = new ArrayList<>();
        for (String repo : Arrays.asList(null, "", " example/device-app", "example/device-app/", "example/..", "../device-app"))
            malformed.add(new Installed(repo, "stable", PACKAGE, 2, 33, SIGNERS, CAPS));
        for (String channel : Arrays.asList(null, "", " stable", "stable/other", "x".repeat(65)))
            malformed.add(new Installed(REPO, channel, PACKAGE, 2, 33, SIGNERS, CAPS));
        for (String pkg : Arrays.asList(null, "", "single", "com..app", "com.1app", "com.app "))
            malformed.add(new Installed(REPO, "stable", pkg, 2, 33, SIGNERS, CAPS));
        malformed.add(new Installed(REPO, "stable", PACKAGE, 0, 33, SIGNERS, CAPS));
        malformed.add(new Installed(REPO, "stable", PACKAGE, -1, 33, SIGNERS, CAPS));
        malformed.add(new Installed(REPO, "stable", PACKAGE, 2, 0, SIGNERS, CAPS));
        for (Set<String> signers : invalidSignerSets())
            malformed.add(new Installed(REPO, "stable", PACKAGE, 2, 33, signers, CAPS));
        for (Set<String> capabilities : invalidCapabilitySets())
            malformed.add(new Installed(REPO, "stable", PACKAGE, 2, 33, SIGNERS, capabilities));
        for (Installed bad : malformed) {
            // Mirror the malformed identity, proving that equality alone is insufficient.
            Release matching = new Release(bad.repository, bad.channel, bad.packageName, 3, 1,
                    bad.signerSha256, bad.requiredCapabilities, "3", URL, false, false, true);
            assertStatus(Status.INCOMPATIBLE, bad, matching);
            assertStatus(Status.INCOMPATIBLE, bad);
        }
    }

    private List<Set<String>> invalidSignerSets() {
        return Arrays.asList(null, Set.of(), Set.of("a".repeat(63)), Set.of("g".repeat(64)),
                Set.of(" " + SIGNER), new HashSet<>(Arrays.asList(SIGNER, null)));
    }

    private List<Set<String>> invalidCapabilitySets() {
        return Arrays.asList(null, Set.of(""), Set.of(" provider.local"), Set.of("x".repeat(129)),
                new HashSet<>(Arrays.asList("provider.local", null)));
    }

    @Test public void malformedReleaseMetadataIsNeverOffered() {
        for (long code : new long[] {0, -1, Long.MIN_VALUE}) assertRejected(release(code));
        for (int sdk : new int[] {0, -1})
            assertRejected(new Release(REPO, "stable", PACKAGE, 3, sdk, SIGNERS, CAPS, "3", URL, false, false, true));
        for (String name : Arrays.asList(null, "", "   ", "x".repeat(129), "3\nforged"))
            assertRejected(new Release(REPO, "stable", PACKAGE, 3, 26, SIGNERS, CAPS, name, URL, false, false, true));
        for (Set<String> signers : invalidSignerSets())
            assertRejected(new Release(REPO, "stable", PACKAGE, 3, 26, signers, CAPS, "3", URL, false, false, true));
        for (Set<String> capabilities : invalidCapabilitySets())
            assertRejected(new Release(REPO, "stable", PACKAGE, 3, 26, SIGNERS, capabilities, "3", URL, false, false, true));
        for (String repo : Arrays.asList(null, "", " " + REPO))
            assertRejected(new Release(repo, "stable", PACKAGE, 3, 26, SIGNERS, CAPS, "3", URL, false, false, true));
        assertRejected(new Release(REPO, null, PACKAGE, 3, 26, SIGNERS, CAPS, "3", URL, false, false, true));
        assertRejected(new Release(REPO, "stable", null, 3, 26, SIGNERS, CAPS, "3", URL, false, false, true));
        Installed noRequirements = new Installed(REPO, "stable", PACKAGE, 2, 33, SIGNERS, Set.of());
        assertStatus(Status.AVAILABLE, noRequirements, new Release(REPO, "stable", PACKAGE, 3, 26,
                SIGNERS, Set.of(), "x".repeat(128), URL, false, false, true));
    }

    @Test public void releaseLinksMustBeExactTrustedGithubTagUrls() {
        for (String url : Arrays.asList(null, "", "http://github.com/" + REPO + "/releases/tag/v3",
                "https://github.com.evil.test/" + REPO + "/releases/tag/v3",
                "https://github.com@evil.test/" + REPO + "/releases/tag/v3",
                "https://user@github.com/" + REPO + "/releases/tag/v3",
                "https://github.com:443/" + REPO + "/releases/tag/v3",
                "https://github.com/other/device-app/releases/tag/v3",
                "https://github.com/" + REPO + "/releases/latest",
                "https://github.com/" + REPO + "/releases/tag/",
                URL + "?redirect=evil", URL + "#fragment", URL + "?", URL + "#",
                URL + "/../other", URL + "/./other", URL + "/%2e%2e/other",
                URL + "/%252e%252e/other", URL + "%2f..", URL + "/", URL + "//other",
                "https://github.com/" + REPO + "/../other/releases/tag/v3",
                " https://github.com/" + REPO + "/releases/tag/v3", URL + "\n",
                "https://github.com/" + REPO + "/releases/tag/v3\\other")) {
            assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                    SIGNERS, CAPS, "100", url, false, false, true));
        }
        assertStatus(Status.AVAILABLE, installed(2), new Release(REPO, "stable", PACKAGE, 3, 26,
                SIGNERS, CAPS, "3", URL + "/android-3.0", false, false, true));
    }

    @Test public void unicodeWhitespaceIsNotAVersionName() {
        for (String name : List.of("\u2003", "\u00a0"))
            assertRejected(new Release(REPO, "stable", PACKAGE, 3, 26,
                    SIGNERS, CAPS, name, URL, false, false, true));
    }

    @Test public void identityCollectionsAreDefensiveImmutableSnapshots() {
        Set<String> signers = new HashSet<>(SIGNERS);
        Set<String> caps = new HashSet<>(CAPS);
        Installed local = new Installed(REPO, "stable", PACKAGE, 2, 33, signers, caps);
        Release candidate = new Release(REPO, "stable", PACKAGE, 3, 26,
                signers, caps, "3", URL, false, false, true);
        signers.clear();
        caps.clear();
        assertEquals(SIGNERS, local.signerSha256);
        assertEquals(CAPS, local.requiredCapabilities);
        assertEquals(SIGNERS, candidate.signerSha256);
        assertEquals(CAPS, candidate.capabilities);
        assertThrows(UnsupportedOperationException.class, () -> local.signerSha256.clear());
        assertThrows(UnsupportedOperationException.class, () -> local.requiredCapabilities.clear());
        assertThrows(UnsupportedOperationException.class, () -> candidate.signerSha256.clear());
        assertThrows(UnsupportedOperationException.class, () -> candidate.capabilities.clear());
        assertStatus(Status.AVAILABLE, local, candidate);
    }

    private void assertRejected(Release candidate) {
        assertStatus(Status.INCOMPATIBLE, installed(2), candidate);
        assertStatus(Status.CURRENT, installed(2), candidate, release(2));
        assertStatus(Status.AHEAD, installed(2), release(1), candidate);
    }

    @Test public void higherCodeCannotSwitchRepositoryChannelOrPackage() {
        assertRejected(new Release("upstream/device-app", "stable", PACKAGE, 100, 26,
                SIGNERS, CAPS, "100", URL, false, false, true));
        assertRejected(new Release(REPO, "beta", PACKAGE, 100, 26,
                SIGNERS, CAPS, "100", URL, false, false, true));
        assertRejected(new Release(REPO, "stable", "com.other.device", 100, 26,
                SIGNERS, CAPS, "100", URL, false, false, true));
        assertRejected(new Release("Example/device-app", "stable", PACKAGE, 100, 26,
                SIGNERS, CAPS, "100", URL, false, false, true));
    }

    @Test public void higherCodeCannotRemoveProvidersOrRequireNewerSdk() {
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                SIGNERS, Set.of("provider.openai"), "100", URL, false, false, true));
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 34,
                SIGNERS, CAPS, "100", URL, false, false, true));
        assertStatus(Status.AVAILABLE, installed(2), new Release(REPO, "stable", PACKAGE, 3, 33,
                SIGNERS, Set.of("provider.openai", "provider.local", "provider.extra"),
                "3", URL, false, false, true));
    }

    @Test public void signerSetsMustMatchExactlyIncludingMultipleSigners() {
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                Set.of(SECOND_SIGNER), CAPS, "100", URL, false, false, true));
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                Set.of(SIGNER, SECOND_SIGNER), CAPS, "100", URL, false, false, true));
        Installed multi = new Installed(REPO, "stable", PACKAGE, 2, 33,
                Set.of(SIGNER, SECOND_SIGNER), CAPS);
        assertStatus(Status.INCOMPATIBLE, multi, release(100));
        assertStatus(Status.AVAILABLE, multi, new Release(REPO, "stable", PACKAGE, 3, 26,
                Set.of(SECOND_SIGNER, SIGNER), CAPS, "3", URL, false, false, true));
    }

    @Test public void draftsPrereleasesAndReleasesWithoutApkAreSkipped() {
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                SIGNERS, CAPS, "100", URL, true, false, true));
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                SIGNERS, CAPS, "100", URL, false, true, true));
        assertRejected(new Release(REPO, "stable", PACKAGE, 100, 26,
                SIGNERS, CAPS, "100", URL, false, false, false));
    }

    @Test public void olderAndEqualVersionCodesAreNeverOffered() {
        assertStatus(Status.AHEAD, installed(3), release(2));
        assertStatus(Status.CURRENT, installed(3), release(3));
    }

    @Test public void highestNumericCodeWinsRegardlessOfListOrderOrVersionName() {
        Release misleading = new Release(REPO, "stable", PACKAGE, 4, 26, SIGNERS, CAPS,
                "9999.0", URL + "-9999", false, false, true);
        Release highest = release(10);
        for (List<Release> releases : List.of(List.of(misleading, highest, release(3)),
                List.of(highest, release(3), misleading), List.of(release(3), misleading, highest))) {
            Decision decision = evaluate(installed(2), releases);
            assertEquals(Status.AVAILABLE, decision.status);
            assertEquals("version-10", decision.versionName);
        }
        assertStatus(Status.AVAILABLE, installed(2), release(Long.MAX_VALUE));
        assertStatus(Status.AHEAD, installed(Long.MAX_VALUE), release(3));
    }

    @Test public void newerCompatibleReleaseIsAvailable() {
        Decision decision = evaluate(installed(2), List.of(release(3)));
        assertEquals(Status.AVAILABLE, decision.status);
        assertEquals(URL, decision.releaseUrl);
        assertEquals("version-3", decision.versionName);
    }
}
