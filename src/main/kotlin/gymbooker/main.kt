package gymbooker

import com.google.gson.Gson
import gymbooker.pushpress.BookingReq
import gymbooker.pushpress.BookingResponseCode
import gymbooker.pushpress.Client
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import java.io.File


private val gson = Gson()

fun main() {
    // load env
    val username = System.getenv("USERNAME") ?: throw Exception("missing USERNAME")
    val clientId = System.getenv("CLIENT_ID") ?: throw Exception("missing CLIENT_ID")
    val password = System.getenv("PASSWORD") ?: throw Exception("missing PASSWORD")
    val debugErr = System.getenv("DEBUG_ERR")?.toBoolean() ?: true
    val debugAll = System.getenv("DEBUG_ALL")?.toBoolean() ?: false
    val statePath = System.getenv("STATE_PATH") ?: "state.json"

    // create client
    val client = Client(username, clientId, password, debugErr, debugAll)

    // run
    try {
        run(statePath, client, debugAll)
    } catch (e: Exception) {
        throw Exception("err running : $e")
    }

    println("all done")
}

private fun run(statePath: String, client: Client, debugAll: Boolean) {
    while (true) {
        val state = try {
            loadState(statePath)
        } catch (e: Exception) {
            println("err loading state : $e")
            Thread.sleep(10000)
            continue
        }

        if (debugAll)
            println("loaded state with ${state.requests.size} from disc at ${now()}")

        (1..100).forEach { _ ->
            state.requests
                .filter { r ->
                    r.resCode != BookingResponseCode.BOOKED &&
                            r.resCode != BookingResponseCode.FULL
                }
                .forEachIndexed { i, r ->
                    try {
                        if (!shouldMakeReq(r, debugAll))
                            return@forEachIndexed
                    } catch (e: Exception) {
                        println("err checking if should make req : $e")
                        Thread.sleep(5000)
                        return@forEachIndexed
                    }

                    println("starting booking request $r")

                    state.requests[i].resCode = try {
                        client.Book(r)
                    } catch (e: Exception) {
                        println("err booking request $r : $e")
                        return@forEachIndexed
                    }

                    println("booking request $r result is ${r.resCode}")

                    writeState(statePath, state)

                    Thread.sleep(1000)
                }
        }

        Thread.sleep(500)
    }
}

private fun shouldMakeReq(r: BookingReq, debugAll: Boolean): Boolean {
    val formatter = DateTimeFormat.forPattern("yyyy-D HH:mma")
    val timeReqRaw = "${r.year}-${r.dayOfYear} ${r.time.replace(" ", "")}"
    val timeReq = formatter.parseDateTime(timeReqRaw)

    val now = now()

    if (debugAll) {
        println("requested (-1 day): $timeReq")
        println("now: $now")
    }

    return now.isAfter(timeReq)
}

private fun loadState(statePath: String): GymBookerState {
    return gson.fromJson(File(statePath).readText(), GymBookerState::class.java)
}

private fun writeState(statePath: String, state: GymBookerState) {
    File(statePath).writeText(gson.toJson(state))
}

private fun now(): DateTime {
    return DateTime(DateTimeZone.forID("Asia/Singapore"))
}

