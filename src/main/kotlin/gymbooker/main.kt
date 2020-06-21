package gymbooker

import gymbooker.pushpress.BookingReq
import gymbooker.pushpress.BookingResponseCode
import gymbooker.pushpress.Client
import java.util.*


fun main() {
    val username = System.getenv("USERNAME")
    val clientId = System.getenv("CLIENT_ID")
    val password = System.getenv("PASSWORD")

    val bookingsReqs = listOf<BookingReq>()

    val client = Client(
        username, clientId, password,
        debugErr = true, debugAll = false
    )

    while (bookingsReqs.any { r ->
            r.resCode != BookingResponseCode.BOOKED &&
                    r.resCode != BookingResponseCode.FULL
        })
        bookingsReqs.filter { r -> r.resCode == null }
            .forEach { r ->
                // check if need to do res
                val now = Calendar.getInstance()
                val nowYear = now.get(Calendar.YEAR)
                val nowDay = now.get(Calendar.DAY_OF_YEAR)
                val nowHour = now.get(Calendar.HOUR_OF_DAY)
                val nowMinute = now.get(Calendar.MINUTE)

                if (r.year == nowYear && r.dayOfYear == nowDay &&
                    r.hour == nowHour && r.minute == nowMinute
                ) {
                    println("starting booking request $r")

                    r.resCode = try {
                        client.Book(r)
                    } catch (e: Exception) {
                        throw Exception("err booking request $r : $e")
                    }

                    println("booking request $r result is ${r.resCode}")
                }

                Thread.sleep(5000)
            }

    println("all done")
}