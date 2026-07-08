package com.v2ray.ang.dto

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String? = null,
    var host: String? = null,
    var path: String? = null,
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
    var insecure: String = "",
    var vcn: String = "",
    var pcs: String = ""
)