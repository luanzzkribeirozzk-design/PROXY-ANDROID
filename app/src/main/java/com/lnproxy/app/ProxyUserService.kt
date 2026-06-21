package com.lnproxy.app

class ProxyUserService : IProxyService.Stub() {
    override fun exec(cmd: String): Int = try {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
    } catch (e: Exception) { -1 }
}
