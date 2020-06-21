package gymbooker.pushpress

import com.google.gson.GsonBuilder
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


enum class BookingResponseCode {
    BOOKED,
    FULL,
    NOT_AVAILABLE
}

data class BookingReq(
    val year: Int,
    val dayOfYear: Int,
    val hour: Int,
    val minute: Int,

    var resCode: BookingResponseCode? = null
)

private data class ScheduleEntry(
    val subscriptionId: String? = null,
    val calenderId: String? = null,
    val csrf: String? = null,

    val notAvailable: Boolean
)

interface IClient {
    fun Book(bookingReq: BookingReq): BookingResponseCode
}

class Client(
    private val username: String, private val clientId: String, private val password: String,
    private val debugErr: Boolean = true, private val debugAll: Boolean = false
) : IClient {
    private val urlGetLogin = "https://members.pushpress.com/login"
    private val urlMembers = "https://mvrck.members.pushpress.com"
    private val urlPostAuth = "https://mvrck.members.pushpress.com/login/auth"
    private val urlGetSchedule = "https://mvrck.members.pushpress.com/schedule/index"
    private val urlPostRegister = "https://mvrck.members.pushpress.com/schedule/registerClass"

    private val client = OkHttpClient()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var phpSessionId: String? = null

    override fun Book(bookingReq: BookingReq): BookingResponseCode {
        // auth if required
        try {
            authIfRequired()
        } catch (e: Exception) {
            throw Exception("err doing auth if required : $e")
        }

        // get schedule
        val scheduleEntry = try {
            findInSchedule(
                bookingReq.year, bookingReq.dayOfYear,
                bookingReq.hour, bookingReq.minute
            )
        } catch (e: Exception) {
            throw Exception("err getting schedule entry : $e")
        }

        println("found schedule entry $scheduleEntry")

        if (scheduleEntry.notAvailable) return BookingResponseCode.NOT_AVAILABLE

        // register
        try {
            register(scheduleEntry)
        } catch (e: Exception) {
            throw Exception("err registering : $e")
        }

        return BookingResponseCode.BOOKED
    }

    private fun authIfRequired() {
        while (true) {
            // check login status
            try {
                println("checking auth")
                if (hasAuth()) {
                    println("auth still ok")
                    return
                }
            } catch (e: Exception) {
                println("err checking auth : $e")
                Thread.sleep(5000)
                continue
            }

            // auth
            try {
                println("doing auth")
                auth()
            } catch (e: Exception) {
                println("err doing auth : $e")
                Thread.sleep(5000)
                continue
            }

            // check if has auth now
            println("checking auth after successful auth")
            try {
                if (hasAuth()) {
                    println("auth ok")
                    return
                }
            } catch (e: Exception) {
                println("err checking auth after successful auth : $e")
                Thread.sleep(5000)
                continue
            }
        }
    }

    private fun hasAuth(): Boolean {
        if (phpSessionId == null) return false

        val req = Request.Builder()
            .withPhpSessionId()
            .url(urlMembers)
            .build()

        val res = client.newCall(req).execute()

        val body = checkAndDebugResAndUnwrapBody(req, res, false)

        return Jsoup.parse(body)
            .selectFirst("div.account.pull-right") != null
    }

    private fun auth() {
        // try to set php session id
        try {
            setNewPhpSessionId()
        } catch (e: Exception) {
            throw Exception("err getting php session id : $e")
        }

        val reqBody = FormBody.Builder()
            .add("username", username)
            .add("client_id", clientId)
            .add("password", password)
            .build()

        val req = Request.Builder()
            .url(urlPostAuth)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .withPhpSessionId()
            .post(reqBody)
            .build()

        val res = client.newCall(req).execute()

        val body = checkAndDebugResAndUnwrapBody(req, res, true)

        if (!body.contains("{\"status\":200,\"subdomain\":\"mvrck\"}"))
            throw Exception("unsuccessful login :\n$body")
    }

    private fun findInSchedule(
        year: Int, dayOfYear: Int,
        hour: Int, minute: Int
    ): ScheduleEntry {
        // e.g. https://mvrck.members.pushpress.com/schedule/index/172/2020
        val url = "${urlGetSchedule}/${dayOfYear}/${year}"

        val req = Request.Builder()
            .withPhpSessionId()
            .url(url)
            .build()

        val res = client.newCall(req).execute()

        val body = checkAndDebugResAndUnwrapBody(req, res, false)

        val doc = Jsoup.parse(body)

        val subscriptionId = doc.select("button[subscription-id]").find { elem ->
            !elem.attr("subscription-id").isNullOrBlank()
        }?.attr("subscription-id")
            ?: throw Exception("could not find subscription id")

        val csrf = doc
            .selectFirst("#csrf")
            .attr("value")

        val entries = doc
            .selectFirst("div.tbody")
            .children()

        entries.forEach { elem ->
            // check if valid elem
            if (!elem.hasAttr("data-target") ||
                elem.attr("data-toggle") != "modal"
            ) {
                return@forEach
            }

            // check if open gym
            val classType = elem.select("div.col-sm-2.td")[1].text().trim()
            if (classType != "Open Gym")
                return@forEach

            // check time
            val timeRaw = elem.selectFirst("span.hidden-xs")
                .text()
                .trim()
                .replace(" am", "")
                .replace(" pm", "")

            val hourElem = timeRaw.split(":").first().toInt()
            val minuteElem = timeRaw.split(":").last().toInt()

            if (hourElem != hour || minuteElem != minute) {
                return@forEach
            }

            // find calendar id
            val dataTargetIdRaw = elem.attr("data-target")
            if (dataTargetIdRaw == "#") return ScheduleEntry(notAvailable = true)
            val dataTargetId = dataTargetIdRaw.replace("#", "").trim()
            val elemModal = doc.selectFirst("button[modal-id=${dataTargetId}]")
            val calenderId = elemModal.attr("calendar-id")

            // return
            return ScheduleEntry(
                subscriptionId = subscriptionId,
                calenderId = calenderId,
                csrf = csrf,
                notAvailable = false
            )
        }

        throw Exception("could not find entry for this time")
    }

    private fun register(scheduleEntry: ScheduleEntry) {
        val reqBody = FormBody.Builder()
            .add("subscription-id", scheduleEntry.subscriptionId!!)
            .add("calendar-id", scheduleEntry.calenderId!!)
            .add("csrf", scheduleEntry.csrf!!)
            .build()

        val req = Request.Builder()
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .withPhpSessionId()
            .url(urlPostRegister)
            .post(reqBody)
            .build()

        val res = client.newCall(req).execute()

        checkAndDebugResAndUnwrapBody(req, res, false)
    }

    private fun setNewPhpSessionId() {
        val req = Request.Builder()
            .url(urlGetLogin)
            .build()

        val res = client.newCall(req).execute()

        checkAndDebugResAndUnwrapBody(req, res, false)

        // extract php session id from cookie
        phpSessionId = res.header("Set-Cookie")
            ?.split("; ")
            ?.find { cookiePart ->
                cookiePart.contains("PHPSESSID=")
            }
            ?.trim()
            ?: throw Exception("no cookie or no php session id in cookie found")

        println("new php session id is $phpSessionId")
    }

    private fun Request.Builder.withPhpSessionId(): Request.Builder {
        return this.addHeader("Cookie", phpSessionId)
    }

    private fun checkAndDebugResAndUnwrapBody(
        req: Request, res: Response, isJsonResBody: Boolean
    ): String {
        val body = try {
            res.body()!!.string()
        } catch (e: Exception) {
            throw Exception("err unwrapping body : $e")
        }

        if (debugAll || (debugErr && isResErr(res)))
            writeDebugFile(req, res, body, isJsonResBody)
        if (isResErr(res)) {
            throw Exception("got status code ${res.code()} in call to ${req.url().encodedPath()}")
        }

        return body
    }

    private fun isResErr(res: Response): Boolean {
        return res.code() != 200
    }

    private fun writeDebugFile(
        req: Request, res: Response,
        body: String, isJsonResBody: Boolean
    ) {
        // write debug file
        val filenameDate = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(Date())
        val filenamePath = req.url().encodedPath().replace("/", "")
        val filename = "debug/${filenameDate}_${filenamePath}"
        val filenameReqHeaders = "${filename}_req_headers.json"
        val filenameResHeaders = "${filename}_res_headers.json"
        val filenameBody = "${filename}.${if (isJsonResBody) "json" else "html"}"

        File(filenameReqHeaders).writeText(gson.toJson(req.headers()))
        File(filenameResHeaders).writeText(gson.toJson(res.headers()))
        File(filenameBody)
            .writeText(body)
    }
}

