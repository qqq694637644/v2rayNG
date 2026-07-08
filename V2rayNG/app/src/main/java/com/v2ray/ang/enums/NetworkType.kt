package com.v2ray.ang.enums

enum class NetworkType(val type: String) {
    TCP("tcp"),
    KCP("mkcp"),
    WS("ws"),
    HTTP_UPGRADE("httpupgrade"),
    XHTTP("xhttp"),
    HTTP("http"),
    H2("h2"),

    //QUIC("quic"),
    GRPC("grpc"),
    HYSTERIA("hysteria");

    companion object {
        fun fromString(type: String?) = when (type) {
            "kcp" -> KCP
            else -> entries.find { it.type == type } ?: TCP
        }
    }
}
