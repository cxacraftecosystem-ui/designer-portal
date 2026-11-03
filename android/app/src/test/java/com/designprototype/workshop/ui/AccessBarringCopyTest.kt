package com.designprototype.workshop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccessBarringCopyTest {

    private val screen: String by lazy {
        repoFile("app/src/main/java/com/designprototype/workshop/ui/AccessRosterScreen.kt").readText()
    }

    private val decideDialog: String by lazy {
        screen.substringAfter("deciding?.let {").substringBefore("suspending?.let {")
    }

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
