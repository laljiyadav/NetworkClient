package com.devrev.networkclient

import org.json.JSONArray

interface JSONArrayListener {
    fun onResponse(res: JSONArray?)
    fun onFailure(e: Exception?)
}