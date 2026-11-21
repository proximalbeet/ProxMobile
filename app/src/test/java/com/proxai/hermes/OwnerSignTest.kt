package com.proxai.hermes

import com.proxai.hermes.data.AppInstaller
import com.proxai.hermes.data.DumpsyncClient
import com.proxai.hermes.data.OwnerSign
import com.proxai.hermes.data.OwnerSign.Facts
import com.proxai.hermes.data.PcStatsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** OWNER-SIGN (0.9.16): the owner token goes only to Prox Hub / BoardRoom signed with the pinned owner key. */
class OwnerSignTest {
    private val ownerKey = "ab".repeat(32)
    private val appsKey = "cd".repeat(32)
    private val otherKey = "ef".repeat(32)
    private val hub = DumpsyncClient.HUB_APP
    private val board = DumpsyncClient.BOARDROOM_APP

    /** What Android reports for [pkg] signed only with [cert], no rotation, its own UID. */
    private fun signedWith(cert: String, pkg: String = hub) = Facts(hasPinnedCert = cert == ownerKey,
        current = listOf(cert), hasPast = false, multipleSigners = false, uidPackages = listOf(pkg))

    private fun facts(hasPinned: Boolean = true, current: List<String> = listOf(ownerKey), past: Boolean = false,
                      multiple: Boolean = false, uid: List<String> = listOf(hub)) =
        Facts(hasPinned, current, past, multiple, uid)

    @Test fun fingerprintsAsKeytoolPrintsThem() {
        val colons = ownerKey.chunked(2).joinToString(":").uppercase()
        assertEquals(ownerKey, OwnerSign.normalize(colons))
        assertEquals(ownerKey, OwnerSign.normalize("  ${ownerKey.uppercase()} \n"))
        assertEquals(ownerKey, OwnerSign.normalize(ownerKey.chunked(2).joinToString(" ")))
        for (bad in listOf("", "ab", ownerKey + "a", ownerKey.dropLast(1), "zz".repeat(32), "SHA256: $ownerKey"))
            assertNull(bad, OwnerSign.normalize(bad))
    }

    @Test fun pinMustBeAFingerprintAndNotTheAppsKey() {
        assertNull(OwnerSign.pinRefusal(ownerKey, appsKey))
        assertNotNull(OwnerSign.pinRefusal("not a key", appsKey))
        assertTrue(OwnerSign.pinRefusal(appsKey.uppercase(), appsKey)!!.contains("every PC-built app"))
        assertTrue(OwnerSign.pinRefusal(appsKey.chunked(2).joinToString(":"), appsKey)!!.contains("every PC-built app"))
    }

    // ---- review (2): the shared-key refusal never rests on a first-install record that isn't there ----

    @Test fun nothingCanBePinnedWhileTheAppsKeyIsUnknown() {
        for (pin in listOf(ownerKey, appsKey, otherKey)) assertEquals(OwnerSign.NO_APPS_KEY, OwnerSign.pinRefusal(pin, null))
        assertEquals(OwnerSign.NO_APPS_KEY, OwnerSign.pinRefusal(ownerKey, "not hex"))
    }

    @Test fun nobodyIsAnOwnerWhileTheAppsKeyIsUnknown() {
        assertFalse(OwnerSign.isOwner(hub, ownerKey, null, signedWith(ownerKey)))
        // the hole this closes: a pin that is really the shared key, saved before that key was known
        assertFalse(OwnerSign.isOwner(hub, appsKey, null, facts(current = listOf(appsKey))))
    }

    @Test fun ownerSignedHubAndBoardRoomAreOwners() {
        for (app in listOf(hub, board)) {
            assertTrue(app, OwnerSign.isOwner(app, ownerKey, appsKey, signedWith(ownerKey, app)))
            assertTrue(app, OwnerSign.isOwner(app, ownerKey.uppercase(), appsKey, signedWith(ownerKey, app)))
            assertTrue(app, OwnerSign.isOwner(app, ownerKey, appsKey.uppercase(), signedWith(ownerKey, app)))
            assertNull(OwnerSign.refusal(app, ownerKey, appsKey, signedWith(ownerKey, app)))
        }
    }

    @Test fun noPinNoOwner() {
        for (pin in listOf(null, "", "   ", "garbage")) {
            assertFalse("$pin", OwnerSign.isOwner(hub, pin, appsKey, signedWith(ownerKey)))
        }
        assertEquals(OwnerSign.NO_PIN, OwnerSign.refusal(hub, null, appsKey, signedWith(ownerKey)))
        assertEquals(OwnerSign.NO_PIN, OwnerSign.refusal(hub, "", appsKey, signedWith(ownerKey)))
    }

    @Test fun anyOtherPackageIsNeverAnOwner() {
        for (app in listOf("com.proxai.apps.prox_dump", "com.proxai.apps.prox_hub2", "com.proxai.apps.board_room",
                "com.proxai.hermes", "com.evil.prox_hub", "", null))
            assertFalse("$app", OwnerSign.isOwner(app, ownerKey, appsKey, signedWith(ownerKey, app ?: "")))
    }

    @Test fun wrongSignatureIsNotAnOwner() {
        for (cert in listOf(appsKey, otherKey)) {
            assertFalse(cert, OwnerSign.isOwner(hub, ownerKey, appsKey, signedWith(cert)))
            assertEquals(OwnerSign.NOT_OWNER_SIGNED, OwnerSign.refusal(hub, ownerKey, appsKey, signedWith(cert)))
        }
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, null))     // not installed, or Android can't say
    }

    @Test fun bothAndroidAnswersMustAgree() {
        // hasSigningCertificate yes but the current signer differs (the pin is only a past certificate)
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(current = listOf(otherKey), past = true)))
        // the current signer matches but hasSigningCertificate says no: refused (never trust one answer alone)
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(hasPinned = false)))
    }

    @Test fun anyPastCertificateIsRejected() {
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(past = true)))
    }

    @Test fun moreThanOneSignerIsRejected() {
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(current = listOf(ownerKey, otherKey), multiple = true)))
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(current = listOf(ownerKey, ownerKey))))
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(current = emptyList())))
    }

    @Test fun aPinEqualToTheAppsKeyIsNeverAccepted() {
        assertFalse(OwnerSign.isOwner(hub, appsKey, appsKey, facts(current = listOf(appsKey))))
        assertFalse(OwnerSign.isOwner(hub, appsKey.uppercase(), appsKey, facts(current = listOf(appsKey))))
    }

    // ---- review (3): the caller is the package Android vouches for; a shared UID is never an owner ----

    @Test fun theCallerMustBeOneOfTheCallingUidsPackages() {
        assertEquals(hub, OwnerSign.verifiedCaller(hub, listOf(hub)))
        assertNull(OwnerSign.verifiedCaller(hub, listOf("com.proxai.apps.prox_dump")))   // a name it doesn't own
        assertNull(OwnerSign.verifiedCaller(hub, emptyList()))
        assertNull(OwnerSign.verifiedCaller(hub, null))                                  // Android couldn't say
        assertNull(OwnerSign.verifiedCaller(null, listOf(hub)))
        // the door refuses a caller it couldn't verify like any unknown app
        assertTrue(PcStatsProvider.refuse(OwnerSign.verifiedCaller(hub, null), ownerKey, appsKey, true)!!
            .contains("Only ProxAI-built"))
    }

    @Test fun aSharedUidIsNeverAnOwner() {
        // another package in the same Linux UID could name itself Prox Hub: no owner for any of them
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(uid = listOf(hub, "com.proxai.apps.prox_dump"))))
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(uid = listOf("com.proxai.apps.prox_dump", hub))))
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(uid = listOf(hub, board))))
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(uid = emptyList())))
        assertFalse(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(uid = listOf(board))))     // not its own UID
        assertTrue(OwnerSign.isOwner(hub, ownerKey, appsKey, facts(uid = listOf(hub))))
    }

    // ---- the door: an owner-signed owner app passes the apps-key check; nothing else changes ----

    @Test fun theDoorLetsOwnerSignedOwnerAppsIn() {
        for (app in listOf(hub, board)) {
            assertNull(PcStatsProvider.refuse(app, ownerKey, appsKey, ownerSigned = true))
            assertTrue(PcStatsProvider.refuse(app, ownerKey, null, ownerSigned = true)!!.contains("Install a ProxAI app"))
            assertTrue(PcStatsProvider.refuse(app, ownerKey, appsKey, ownerSigned = false)!!.contains("isn't signed"))
            assertNull(PcStatsProvider.refuse(app, appsKey, appsKey, ownerSigned = false))   // old build: as other apps
        }
        // the flag never helps another package
        assertTrue(PcStatsProvider.refuse("com.proxai.apps.prox_dump", ownerKey, appsKey, ownerSigned = true)!!
            .contains("isn't signed"))
        assertTrue(PcStatsProvider.refuse("com.evil.prox_hub", ownerKey, appsKey, ownerSigned = true)!!
            .contains("Only ProxAI-built"))
    }

    // ---- the Apps tab's installer; review (1): no pin, no owner app at all ----

    @Test fun installerOnceAnOwnerKeyIsPinned() {
        for (app in listOf(hub, board)) {
            assertNull(AppInstaller.check(app, ownerKey, appsKey, ownerKey))
            assertNull(AppInstaller.check(app, ownerKey.uppercase(), appsKey, ownerKey))
            assertEquals(AppInstaller.OWNER_KEY_ONLY, AppInstaller.check(app, appsKey, appsKey, ownerKey))
            assertEquals(AppInstaller.OWNER_KEY_ONLY, AppInstaller.check(app, otherKey, appsKey, ownerKey))
        }
        assertEquals(AppInstaller.OWNER_KEY_IS_THEIRS, AppInstaller.check("com.proxai.apps.tip_calc", ownerKey, appsKey, ownerKey))
        assertEquals(AppInstaller.OWNER_KEY_IS_THEIRS, AppInstaller.check("com.proxai.apps.tip_calc", ownerKey, null, ownerKey))
        assertNull(AppInstaller.check("com.proxai.apps.tip_calc", appsKey, appsKey, ownerKey))
    }

    @Test fun installerRefusesOwnerAppsUntilAnOwnerKeyIsPinned() {
        for (app in listOf(hub, board)) for (cert in listOf(appsKey, ownerKey, otherKey)) {
            assertEquals("$app $cert", AppInstaller.PIN_OWNER_KEY_FIRST, AppInstaller.check(app, cert, appsKey, null))
            assertEquals("$app $cert", AppInstaller.PIN_OWNER_KEY_FIRST, AppInstaller.check(app, cert, null, null))
        }
        assertEquals("Couldn't read the app's signature", AppInstaller.check(hub, null, appsKey, null))
        // every other app is as before
        assertNull(AppInstaller.check("com.proxai.apps.tip_calc", appsKey, appsKey, null))
        assertTrue(AppInstaller.check("com.proxai.apps.tip_calc", ownerKey, appsKey, null)!!.contains("different key"))
    }

    @Test fun anOwnerAppsInstallNeverBecomesTheAppsKey() {
        assertFalse(AppInstaller.pinsAppsKey(hub, ownerKey, null))
        assertFalse(AppInstaller.pinsAppsKey(board, ownerKey, null))
        assertTrue(AppInstaller.pinsAppsKey("com.proxai.apps.tip_calc", appsKey, null))
        assertFalse(AppInstaller.pinsAppsKey("com.proxai.apps.tip_calc", appsKey, appsKey))   // already pinned
        assertFalse(AppInstaller.pinsAppsKey("com.proxai.apps.tip_calc", null, null))
    }

    @Test fun anOwnerAppsInstallSessionIsBoundToItsPackage() {
        assertEquals(hub, AppInstaller.sessionPackage(hub))
        assertEquals(board, AppInstaller.sessionPackage(board))
        assertNull(AppInstaller.sessionPackage("com.proxai.apps.tip_calc"))
        assertNull(AppInstaller.sessionPackage(null))
    }

    // ---- review (4): the pin and the owner token never go to a backup or a phone-to-phone transfer ----

    /** The app module's source folder: tests run from app/ under Gradle, from the repo root in some IDEs. */
    private fun srcMain(): File = listOf(File("src/main"), File("app/src/main")).first { File(it, "AndroidManifest.xml").isFile }

    @Test fun nothingOfProxMobilesGoesToABackup() {
        val manifest = File(srcMain(), "AndroidManifest.xml").readText()
        val app = Regex("<application[^>]*>", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value
        assertTrue(app.contains("android:allowBackup=\"false\""))
        assertTrue(app.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = File(srcMain(), "res/xml/data_extraction_rules.xml").readText()
        assertFalse(rules.contains("<include"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(rules)!!.groupValues[1]
            for (domain in listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file",
                    "device_database", "device_sharedpref"))
                assertTrue("$section $domain", body.contains("<exclude domain=\"$domain\" path=\".\" />"))
        }
    }

    // ---- review (5): the only writers of the pin and the owner token are the Settings screen's buttons ----

    @Test fun onlyTheSettingsButtonsWriteThePinAndTheOwnerToken() {
        val src = File(srcMain(), "java")
        val writers = mutableListOf<String>()
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                // a property assignment (x.ownerToken = ...), or a preference write under either key
                if (Regex("""\.(ownerCertPin|ownerToken)\s*=[^=]""").containsMatchIn(code) ||
                    Regex("""put\w*\(\s*("owner_token"|OwnerSign\.KEY|"owner_cert_sha256")""").containsMatchIn(code))
                    writers += "${f.name}:${code.trim()}"
            }
        }
        // Settings.kt: the two setters themselves; SettingsScreen.kt: Save / Forget Owner token, Pin / Forget owner key
        assertEquals(writers.joinToString("\n"), 6, writers.size)
        assertEquals(2, writers.count { it.startsWith("Settings.kt:") })
        val screen = writers.filter { it.startsWith("SettingsScreen.kt:") }
        assertEquals(writers.joinToString("\n"), 4, screen.size)
        assertEquals(2, screen.count { it.contains("s.ownerToken = ") })
        assertEquals(2, screen.count { it.contains("s.ownerCertPin = ") })
        // and nothing bulk-writes ProxMobile's preferences
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            assertFalse(f.name, Regex("""edit\(\)\s*\.\s*clear\(\)""").containsMatchIn(t))
        }
    }
}
