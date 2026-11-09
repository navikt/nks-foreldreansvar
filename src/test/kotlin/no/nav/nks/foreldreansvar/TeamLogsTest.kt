package no.nav.nks.foreldreansvar

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.util.Duration
import net.logstash.logback.appender.LogstashTcpSocketAppender
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Loads the app's own logback.xml (not logback-test.xml) and checks that idents never reach stdout. */
class TeamLogsTest {
    @Test
    fun `locally TEAM_LOGS lines are dropped and there is no Team Logs appender`() {
        val context = configured(loggerContext())
        assertNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("TEAM_LOGS"))

        val stdout = captureStdout { logBoth(context) }

        assertTrue(stdout.contains("normal line"), stdout)
        assertFalse(stdout.contains("12345678901"), stdout)
    }

    @Test
    fun `with LOG_FORMAT=plain TEAM_LOGS lines are dropped too`() {
        val stdout = captureStdout { logBoth(configured(loggerContext("LOG_FORMAT" to "plain"))) }

        assertTrue(stdout.contains("INFO  [") && stdout.contains("normal line"), stdout)
        assertFalse(stdout.contains("12345678901"), stdout)
    }

    @Test
    fun `on Nais TEAM_LOGS lines go to the Team Logs appender, not stdout`() {
        val context = configured(loggerContext("NAIS_POD_NAME" to "nks-foreldreansvar-abc"))
        val teamLogs = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("TEAM_LOGS") as? LogstashTcpSocketAppender
        assertNotNull(teamLogs)
        // There is no team-logs host here, so don't wait for the buffered line on stop.
        teamLogs!!.shutdownGracePeriod = Duration.buildByMilliseconds(0.0)

        val stdout = captureStdout { logBoth(context) }

        assertTrue(stdout.contains("normal line"), stdout)
        assertFalse(stdout.contains("12345678901"), stdout)
    }

    private fun loggerContext(vararg properties: Pair<String, String>) =
        LoggerContext().apply {
            mdcAdapter = LogbackMDCAdapter()
            properties.forEach { (key, value) -> putProperty(key, value) }
        }

    private fun configured(context: LoggerContext) =
        context.apply { JoranConfigurator().also { it.context = this }.doConfigure(javaClass.getResource("/logback.xml")) }

    private fun logBoth(context: LoggerContext) {
        val log = context.getLogger("test")
        log.info("normal line")
        log.info(TEAM_LOGS, "team logs line ident={}", "12345678901")
        context.stop()
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }
}
