package gg.grounds.permissions.velocity

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger

class RecordingLogger : AbstractLogger() {
    val events: MutableList<RecordedLogEvent> = mutableListOf()

    init {
        name = "recording"
    }

    override fun isTraceEnabled(): Boolean = true

    override fun isTraceEnabled(marker: Marker?): Boolean = true

    override fun isDebugEnabled(): Boolean = true

    override fun isDebugEnabled(marker: Marker?): Boolean = true

    override fun isInfoEnabled(): Boolean = true

    override fun isInfoEnabled(marker: Marker?): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isWarnEnabled(marker: Marker?): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun isErrorEnabled(marker: Marker?): Boolean = true

    override fun getFullyQualifiedCallerName(): String = javaClass.name

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        events +=
            RecordedLogEvent(
                level = level,
                message = messagePattern,
                args = arguments?.toList().orEmpty(),
                throwable = throwable,
            )
    }
}

data class RecordedLogEvent(
    val level: Level,
    val message: String,
    val args: List<Any>,
    val throwable: Throwable?,
)
