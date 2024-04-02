package com.devrev.networkclient

import org.json.JSONObject

interface JSONObjectListener {
 fun onResponse(res: JSONObject?)
 fun onFailure(e: Exception?)
}