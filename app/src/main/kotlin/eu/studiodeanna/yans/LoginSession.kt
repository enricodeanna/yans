package eu.studiodeanna.yans

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.studiodeanna.yans.sync.LoginRefusedException
import eu.studiodeanna.yans.sync.Nextcloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logging in, kept apart from the screen so that it goes on while the user is in the browser, and when the
 * screen is made again.
 */
object LoginSession {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        /** Waiting for the user to approve the login at [loginUrl]. */
        data class Waiting(val loginUrl: String) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Logs in through the browser, with Nextcloud's login flow. */
    fun start(context: Context, server: String) = launch(context, server) {
        val api = Nextcloud(Syncer.client)
        val login = api.startLogin(server)
        _state.value = State.Waiting(login.loginUrl)
        withContext(Dispatchers.Main) { openBrowser(context, login.loginUrl) }
        // The server lets the app ask for 20 minutes.
        val until = System.currentTimeMillis() + 20 * 60_000
        while (System.currentTimeMillis() < until) {
            delay(2000)
            api.poll(login)?.let { return@launch it }
        }
        throw LoginTimedOut()
    }

    /** Logs in with an app password made by hand in the server's security settings. */
    fun withPassword(context: Context, server: String, loginName: String, password: String) = launch(context, server) {
        Nextcloud(Syncer.client).account(server, loginName.trim(), password.trim())
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun openBrowser(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launch(context: Context, server: String, login: suspend () -> eu.studiodeanna.yans.sync.Account) {
        val app = context.applicationContext
        job?.cancel()
        Store.get(app).lastServer = server.trim()
        _state.value = State.Busy
        job = scope.launch {
            try {
                Store.get(app).saveAccount(login())
                _state.value = State.Idle
            } catch (e: LoginRefusedException) {
                _state.value = State.Failed(app.getString(R.string.login_refused_password))
            } catch (e: LoginTimedOut) {
                _state.value = State.Failed(app.getString(R.string.login_timed_out))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private class LoginTimedOut : Exception()
}
