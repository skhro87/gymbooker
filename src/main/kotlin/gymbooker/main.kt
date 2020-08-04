package gymbooker

import com.google.gson.Gson
import gymbooker.pushpress.BookingReq
import gymbooker.pushpress.BookingResponseCode
import gymbooker.pushpress.Client
import java.io.File
import java.util.*

private val gson = Gson()

fun main() {
    // load env
    val username = System.getenv("USERNAME") ?: throw Exception("missing USERNAME")
    val clientId = System.getenv("CLIENT_ID") ?: throw Exception("missing CLIENT_ID")
    val password = System.getenv("PASSWORD") ?: throw Exception("missing PASSWORD")
    val debugErr = System.getenv("DEBUG_ERR")?.toBoolean() ?: true
    val debugAll = System.getenv("DEBUG_ALL")?.toBoolean() ?: false
    val configPath = System.getenv("CONFIG_PATH") ?: "config.json"

    // load config
    val config = try {
        loadConfig(configPath)
    } catch (e: Exception) {
        Thread.sleep(5000)
        throw Exception("err loading config file from $configPath : $e")
    }

    // create client
    val client = Client(username, clientId, password, debugErr, debugAll)

    // run
    try {
        run(config, client)
    } catch (e : Exception) {
        throw Exception("err running : $e")
    }

    println("all done")
}

private fun run(config: GymBookerConfig, client: Client) {
    while (config.requests.any { r ->
            r.resCode != BookingResponseCode.BOOKED &&
                    r.resCode != BookingResponseCode.FULL
        })
        config.requests.filter { r -> r.resCode == null }
            .forEachIndexed { i, r ->
                if (!shouldMakeReq(r)) return@forEachIndexed

                println("starting booking request $r")

                config.requests[i].resCode = try {
                    client.Book(r)
                } catch (e: Exception) {
                    println("err booking request $r : $e")
                    return@forEachIndexed
                }

                println("booking request $r result is ${r.resCode}")

                Thread.sleep(5000)
            }
}

private fun shouldMakeReq(r: BookingReq): Boolean {
    // check if need to do res
    val now = Calendar.getInstance()
    val nowYear = now.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val nowHour = now.get(Calendar.HOUR_OF_DAY)
    val nowMinute = now.get(Calendar.MINUTE)

    if (r.year == nowYear && r.dayOfYear == nowDay &&
        r.hour == nowHour && r.minute == nowMinute
    ) {
        return true
    }

    return false
}

private fun loadConfig(configPath: String): GymBookerConfig {
    return gson.fromJson(File(configPath).readText(), GymBookerConfig::class.java)
}