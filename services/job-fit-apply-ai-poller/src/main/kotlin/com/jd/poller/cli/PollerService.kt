package com.jd.poller.cli

import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.config.PollerConfig
import com.jd.poller.gmail.GmailAuth
import com.jd.poller.gmail.GmailClient
import com.jd.poller.health.Heartbeat
import com.jd.poller.intake.IntakeLoop
import com.jd.poller.writeback.WritebackLoop

/**
 * The long-running Poller: runs the intake loop (Gmail → bridge) and the write-back loop
 * (bridge completed-feed → Gmail labels + drafts) on two threads. Owns all Gmail interaction.
 */
class PollerService(
    private val gmail: GmailClient = GmailClient(),
    private val bridge: PollerBridgeClient = PollerBridgeClient(),
) {
    fun run() {
        println("[poller] starting — bridge=${PollerConfig.JD_BRIDGE_URL}")
        val status = GmailAuth.checkTokenStatus()
        if (status.status != GmailAuth.TokenStatus.VALID) {
            System.err.println("[poller] Gmail token ${status.status}: ${status.message} — run --reauth")
        } else {
            status.emailAddress?.let { println("[poller] Gmail account: $it") }
        }

        val heartbeat = Heartbeat.fromConfig(PollerConfig.HEARTBEAT_FILE)
        val intake = Thread({ IntakeLoop(gmail, bridge, heartbeat).runForever() }, "poller-intake")
        val writeback = Thread({ WritebackLoop(gmail, bridge, heartbeat).runForever() }, "poller-writeback")
        intake.start()
        writeback.start()
        intake.join()
        writeback.join()
    }
}
