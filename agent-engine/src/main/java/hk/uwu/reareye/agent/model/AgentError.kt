package hk.uwu.reareye.agent.model

/**
 * Failures the agent surface must explain to the user. Every variant carries a message that is
 * safe to show verbatim in the UI.
 */
sealed class AgentError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No provider endpoint or model has been configured yet. */
    class NotConfigured(message: String) : AgentError(message)

    /** A missing or rejected credential. The UI should offer a shortcut to the key page. */
    class Auth(message: String, cause: Throwable? = null) : AgentError(message, cause)

    /** Network transport failure: DNS, TLS, connect/read timeout, socket reset. */
    class Network(message: String, cause: Throwable?) : AgentError(message, cause)

    /** Provider answered with a non-success status. */
    class Http(val statusCode: Int, message: String, cause: Throwable? = null) :
        AgentError(message, cause)

    /** The stream was not shaped as expected, or a tool call's arguments were unusable. */
    class Protocol(message: String, cause: Throwable? = null) : AgentError(message, cause)

    /** The caller cancelled the turn. */
    class Cancelled(message: String = "Cancelled") : AgentError(message)

    /** A tool failed in a way that should end the turn rather than be reported to the model. */
    class ToolFailure(val toolName: String, message: String) : AgentError(message)
}
