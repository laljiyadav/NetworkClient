
package com.devrev.networkclient

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*

object Http {
    internal const val TAG = "Http"

    const val GET = "GET"
    const val POST = "POST"
    const val DELETE = "DELETE"
    const val PUT = "PUT"

    internal var reqTimeStamp: Long = 0

    interface ProgressCb {
        fun progress(http: Request?, totalRead: Int, totalAvailable: Int, percent: Int)
    }

    class Request(internal val method: String) {
        private val query: MutableMap<String, String> = HashMap()
        internal val header: MutableMap<String, String> = HashMap()
        internal var uri: String? = null
        private var jsonObjReqListener: JSONObjectListener? = null
        private var jsonArrayRequestListener: JSONArrayListener? = null
        private var progressCb: ProgressCb? = null
        internal var body: ByteArray? = null
        internal var loggingEnabled = false
        private var threadExecutor: ThreadExecutor =
            ThreadExecutor().setPriority(ThreadExecutor.DEFAULT)

        fun enableLog(enableLogging: Boolean): Request {
            HttpLogger.isLogsRequired = enableLogging
            loggingEnabled = enableLogging
            return this
        }

        fun setPriority(priority: Int): Request {
            threadExecutor = ThreadExecutor().setPriority(priority)
            return this
        }

        fun url(uri: String?): Request {
            this.uri = uri
            return this
        }

        fun query(queryMap: Map<String, String>?): Request {
            query.putAll(queryMap!!)
            return this
        }

        fun body(json: JSONObject): Request {
            body(json.toString())
            header(CONTENT_TYPE, APPLICATION_JSON)
            return this
        }

        fun body(jsonObjectList: List<JSONObject?>): Request {
            body(jsonObjectList.toString())
            header(CONTENT_TYPE, APPLICATION_JSON)
            return this
        }

        fun body(textBody: String?): Request {
            if (textBody == null) {
                body = null
                return this
            }
            header(CONTENT_TYPE, TEXT_PLAIN)
            try {
                body = textBody.toByteArray(charset(UTF_8))
            } catch (e: UnsupportedEncodingException) { /* Should never happen */
            }
            return this
        }

        fun header(header: Map<String, String>?): Request {
            this.header.putAll(header!!)
            return this
        }

        fun header(key: String, value: String): Request {
            header[key] = value
            return this
        }

        fun body(rawBody: ByteArray?): Request {
            if (rawBody == null) {
                body = null
                return this
            }
            body = rawBody
            return this
        }

        fun onProgress(progressCb: ProgressCb?) {
            this.progressCb = progressCb
        }

        fun execute(cb: JSONObjectListener): Request {
            reqTimeStamp = System.currentTimeMillis()
            this.jsonObjReqListener = cb
            threadExecutor.execute(RequestTask(this))
            return this
        }

        fun execute(cb: JSONArrayListener): Request {
            reqTimeStamp = System.currentTimeMillis()
            this.jsonArrayRequestListener = cb
            threadExecutor.execute(RequestTask(this))
            return this
        }

        internal fun fireProgress(totalRead: Int, totalAvailable: Int) {
            if (progressCb == null) return
            val percent = (totalRead.toFloat() / totalAvailable.toFloat() * 100f).toInt()
            progressCb!!.progress(this@Request, totalRead, totalAvailable, percent)
        }

        fun sendResponse(resp: Response?, e: Exception?) {
            HttpLogger.d(
                TAG,
                "TIME TAKEN FOR API CALL(MILLIS) : " + (System.currentTimeMillis() - reqTimeStamp)
            )
            if (jsonObjReqListener != null) {
                if (e != null) jsonObjReqListener!!.onFailure(e)
                else jsonObjReqListener!!.onResponse(resp?.asJSONObject())
                return
            }
            if (jsonArrayRequestListener != null) {
                if (e != null) jsonArrayRequestListener!!.onFailure(e)
                else jsonArrayRequestListener!!.onResponse(resp?.asJSONArray())
                return
            } else e?.printStackTrace()
        }

        internal fun getQueryString(): String {
            if (query.isEmpty()) return ""
            val result = StringBuilder("?")
            for ((key, value) in query) {
                try {
                    result.append(URLEncoder.encode(key, UTF_8))
                    result.append("=")
                    result.append(URLEncoder.encode(value, UTF_8))
                } catch (e: Exception) { /* This should never happen */
                    e.printStackTrace()
                }
            }
            return result.toString()
        }
    }

    internal class RequestTask(private val req: Request) : Runnable {
        override fun run() {
            try {
                val conn = request()
                parseResponse(conn)
            } catch (e: IOException) {
                req.sendResponse(null, e)
                e.printStackTrace()
            }
        }

        @Throws(IOException::class)
        private fun request(): HttpURLConnection {
            val url = URL(req.uri + req.getQueryString())
            val conn = url.openConnection() as HttpURLConnection
            val method = req.method
            conn.requestMethod = method
            // conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.doInput = true
            if (req.loggingEnabled) {
                HttpLogger.d(TAG, "Http : URL : $url")
                HttpLogger.d(TAG, "Http : Method : $method")
                HttpLogger.d(TAG, "Http : Headers : " + req.header.toString())
                if (req.body != null) HttpLogger.d(
                    TAG, "Http : Request Body : " + HttpUtils.asString(
                        req.body
                    )
                )
            }
            for ((key, value) in req.header) {
                conn.setRequestProperty(key, value)
            }
            if (req.body != null) {
                conn.doOutput = true
                val os = conn.outputStream
                os.write(req.body)
            }
            conn.connect()
            return conn
        }

        @Throws(IOException::class)
        private fun parseResponse(conn: HttpURLConnection) {
            try {
                val bos = ByteArrayOutputStream()
                val status = conn.responseCode
                if (req.loggingEnabled) HttpLogger.d(
                    TAG,
                    "Http : Response Status Code : " + status + " for URL: " + conn.url
                )
                val message = conn.responseMessage
                val respHeaders =
                    TreeMap<String?, List<String>>(java.lang.String.CASE_INSENSITIVE_ORDER)
                val headerFields: MutableMap<String?, List<String>> = HashMap(conn.headerFields)
                headerFields.remove(null) // null values are not allowed in TreeMap
                respHeaders.putAll(headerFields)
                val validStatus = status in 200..399
                val inpStream = if (validStatus) conn.inputStream else conn.errorStream

                val totalAvailable =
                    if (respHeaders.containsKey(CONTENT_LENGTH)) respHeaders[CONTENT_LENGTH]!![0].toInt() else -1
                if (totalAvailable != -1) req.fireProgress(0, totalAvailable)
                var read: Int
                var totalRead = 0
                val buf = ByteArray(bufferSize)
                while (inpStream.read(buf).also { read = it } != -1) {
                    bos.write(buf, 0, read)
                    totalRead += read
                    if (totalAvailable != -1) req.fireProgress(totalRead, totalAvailable)
                }
                if (totalAvailable != -1) req.fireProgress(totalAvailable, totalAvailable)
                val resp = Response(bos.toByteArray(), status, message, respHeaders)
                if (req.loggingEnabled && !validStatus) HttpLogger.d(
                    TAG,
                    "Http : Response Body : " + resp.asString()
                )
                req.sendResponse(resp, null)
            } finally {
                conn.disconnect()
            }
        }
    }

    class Response(
        private val data: ByteArray,
        val status: Int,
        val message: String,
        val respHeaders: Map<String?, List<String>>
    ) {
        @Throws(JSONException::class)
        fun asJSONObject(): JSONObject {
            val str = asString()
            return if (str.isEmpty()) JSONObject() else JSONObject(str)
        }

        @Throws(JSONException::class)
        fun asJSONArray(): JSONArray {
            val str = asString()
            return if (str.isEmpty()) JSONArray() else JSONArray(str)
        }

        fun asString(): String {
            return HttpUtils.asString(data)
        }
    }
}