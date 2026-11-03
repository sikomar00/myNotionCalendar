package com.ysdoing.notioncal.core.sync

import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 표지 주소는 노션 데이터(공유받은 페이지 포함)에 적힌 글자라, 그대로 부르면 기기가 사설망·로컬 주소로 요청을 보내게 만들 수 있다(SSRF).
 * 인터넷에 공개된 주소만 허용한다: 이름은 [PublicOnlyDns]가 풀린 주소로, IP 주소를 직접 적은 경우는 [isAllowed]가 요청 전에 막는다.
 */
object PublicHosts {
    /** 루프백·모든 주소(0.0.0.0)·링크 로컬·사설(RFC1918)·멀티캐스트·CGNAT(100.64/10)·IPv6 고유 로컬(fc00::/7)이면 false. */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> !(bytes[0].toInt() and 0xFF == 100 && (bytes[1].toInt() and 0xC0) == 0x40)
            is Inet6Address -> (bytes[0].toInt() and 0xFE) != 0xFC // fc00::/7
            else -> false
        }
    }

    /** IP 주소를 직접 적은 주소는 공개 주소일 때만 허용한다. 이름은 DNS 단계에서 걸러서 여기서는 통과시킨다. */
    fun isAllowed(url: HttpUrl): Boolean {
        val host = url.host
        val literal = host.contains(':') || host.all { it.isDigit() || it == '.' }
        if (!literal) return true
        return try {
            isPublic(InetAddress.getByName(host)) // 숫자 주소라 DNS 조회가 일어나지 않는다
        } catch (e: UnknownHostException) {
            false
        }
    }
}

/** 이름을 푼 주소 중 하나라도 공개 주소가 아니면 연결하지 않는다. 확인한 주소 목록으로만 연결하므로 확인과 연결 사이에 바뀌지 않는다. */
object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.any { !PublicHosts.isPublic(it) }) throw UnknownHostException("공개 주소가 아닌 곳으로는 표지를 받지 않습니다")
        return addresses
    }
}
