package com.designprototype.workshop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BOTH BARRING DOORS ON THE ACCESS ROSTER SAY THE ACCESS ENDS NOW, BECAUSE SINCE 2026-09-03 IT DOES.
 *
 * ── WHAT THIS PINS, AND WHY IT IS A TEST RATHER THAN A COMMENT ───────────────────────────────────
 *
 * Until 2026-09-03 both doors wrote `AccessRoster.status` and nothing else, and that column is read
 * on the SIGN-IN path — so barring somebody stopped their next sign-in and left the browser and the
 * phone they were already signed in on working for the rest of `JWT_EXPIRES_MINUTES`, seven days by
 * default. `routes/access` now stamps `User.sessionsValidFrom` at BOTH doors, and
 * `deps._user_from_bearer` compares that against the token's `iat` on every authenticated request.
 *
 * The SUSPEND dialog was corrected that day. The REFUSE dialog was not, and the gap is the shape
 * this repository keeps paying for: a rule enforced at whichever door somebody happened to look at.
 * An administrator refusing a person who is signed in at that moment read a dialog that talked only
 * about what happens next time, and took from it that the session in front of them was safe until
 * the token expired.
 *
 * ── THE THREE THINGS THAT WOULD BREAK IT ─────────────────────────────────────────────────────────
 *
 * 1. The server dropping the stamp from the REJECT arm, or putting it behind the mirror's
 *    `was_barred` guard, which would make BOTH dialogs' sentence false without touching either.
 *    That is why the backend is read here and not taken on trust: no Kotlin string can check itself.
 * 2. Either dialog losing its timing sentence in a copy edit — the state this test was written in.
 * 3. The two clients drifting apart on one decision. The web's `admin/access/page.tsx` carries the
 *    same sentence in the same place, and a decision described in two vocabularies reads to somebody
 *    moving between the apps as two different rules.
 *
 * Asserted over SOURCE because there is no Robolectric here and this is composable state — the same
 * trade `RosterFilterWireTest` makes for the filter sheet, and for the same reason.
 */
class AccessBarringCopyTest {

    private val screen: String by lazy {
        repoFile("app/src/main/java/com/designprototype/workshop/ui/AccessRosterScreen.kt").readText()
    }

    /** The decide dialog: Approve on one arm, Refuse on the other. */
    private val decideDialog: String by lazy {
        screen.substringAfter("deciding?.let {").substringBefore("suspending?.let {")
    }

    /** The suspend dialog, which is everything after the decide one. */
    private val suspendDialog: String by lazy { screen.substringAfter("suspending?.let {") }

    @Test
    fun `the server really does end sessions on REJECT, and does not guard it`() {
        val access = repoFile(
            "backend/app/api/routes/access.py",
            "../backend/app/api/routes/access.py",
        ).readText()
        val rejectArm = access
            .substringAfter("if payload.decision == \"REJECT\":")
            .substringBefore("assert_role(payload.role, current_user)")
        assertTrue(
            "the REJECT arm must end live sessions, or both dialogs' sentence is a lie",
            rejectArm.contains("await end_live_sessions(updated.email)")
        )
        // UNGUARDED, ASSERTED BY INDENTATION, which is the only thing that separates the two calls
        // in this arm. The mirror sits inside `if not was_barred:` and is therefore indented one
        // level deeper; the stamp sits at the arm's own level. If somebody copies the mirror's guard
        // onto the stamp — copying a rule past the reason for it — a REJECTED row moved to REJECTED
        // again would stop ending the session an earlier, pre-2026-09-03 bar left running, and the
        // dialog would go on promising it.
        assertTrue(
            "the stamp must sit at the arm's own indentation, outside `if not was_barred:`",
            rejectArm.lines().any { it == "        await end_live_sessions(updated.email)" }
        )
    }

    @Test
    fun `refusing says the session ends now`() {
        assertTrue(
            "the Refuse dialog must say what happens to a session the person is already in",
            decideDialog.contains("contact. Any session they are in now ends with it.")
        )
        // The approve arm must NOT acquire it. Letting somebody in ends nothing, and `decide` stamps
        // nothing on APPROVE — a sentence about sessions there would describe an act that does not
        // happen.
        val approveArm = decideDialog.substringAfter("if (approving) {").substringBefore("} else {")
        assertFalse(
            "approving ends no session and must not claim to",
            approveArm.contains("ends with it")
        )
    }

    @Test
    fun `suspending still says the same thing in its own voice`() {
        assertTrue(
            "the Suspend dialog must keep the claim it gained on 2026-09-03",
            suspendDialog.contains("signs them out now")
        )
        // And it must not have slid back to the sentence it replaced, which implied "not before
        // then" — the whole defect, in five words.
        assertFalse(
            "no dialog may promise that barring takes effect only at the next sign-in",
            screen.contains("They will be refused at their next sign-in, and told")
        )
    }

    @Test
    fun `the web says it in the same words on the same arm`() {
        val page = repoFile(
            "frontend/app/(protected)/admin/access/page.tsx",
            "../frontend/app/(protected)/admin/access/page.tsx",
        ).readText()
        val webReject = page.substringAfter("async function reject(").substringBefore("async function suspend(")
        assertTrue(
            "the web's Refuse confirmation is where this sentence came from — keep them one sentence",
            webReject.contains("Any session they are in now ends with it.")
        )
        val webSuspend = page.substringAfter("async function suspend(")
        assertTrue(
            "and the web's Suspend confirmation carries its own",
            webSuspend.contains("Any session they are in now ends immediately.")
        )
    }

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. Same helper and same reasoning as `ArtisanAnswerPanelTest` — a
     * CLASS MEMBER rather than that file's top-level copy, because this test lives in the same
     * package and two private top-level functions of one name there is a needless thing to make the
     * compiler adjudicate. The dot-dot-prefixed candidates are what let it reach out of the android
     * module and into `backend/` and `frontend/`, which the two cross-client tests above need.
     */
    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }
}
