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
    /**
     * 공개 인터넷 주소일 때만 true. 막는 것: 루프백·모든 주소(0.0.0.0/8)·링크 로컬·사설(RFC1918)·멀티캐스트·CGNAT(100.64/10)·
     * IETF 예약(192.0.0.0/24)·벤치마크(198.18/15)·예약/브로드캐스트(240/4)·IPv6 고유 로컬(fc00::/7).
     * IPv6로 IPv4를 감춘 주소(IPv4 호환 ::/96, NAT64 64:ff9b::/96, 6to4 2002::/16)는 감춘 IPv4 주소로 같은 규칙을 적용한다.
     * (`::ffff:a.b.c.d`는 자바가 이미 IPv4로 바꿔 준다.)
     */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address
        return when (address) {
            is Inet4Address -> isPublicV4(b)
            is Inet6Address -> isPublicV6(b)
            else -> false
        }
    }

    private fun isPublicV4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val third = b[2].toInt() and 0xFF
        return when {
            a == 0 || a >= 240 -> false // 0.0.0.0/8, 240.0.0.0/4(브로드캐스트 포함)
            a == 100 && (c and 0xC0) == 0x40 -> false // 100.64.0.0/10
            a == 192 && c == 0 && third == 0 -> false // 192.0.0.0/24
            a == 198 && (c and 0xFE) == 18 -> false // 198.18.0.0/15
            else -> true
        }
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        if ((b[0].toInt() and 0xFE) == 0xFC) return false // fc00::/7
        // 감춘 IPv4 주소에는 사설·루프백·링크 로컬 검사까지 모두 다시 적용한다(그래서 isPublic으로 되돌아간다).
        fun embedded(from: Int) = isPublic(InetAddress.getByAddress(b.copyOfRange(from, from + 4)))
        val firstTenZero = (0 until 10).all { b[it].toInt() == 0 }
        return when {
            (0 until 12).all { b[it].toInt() == 0 } -> embedded(12) // ::/96 IPv4 호환(::1은 위에서 이미 루프백으로 걸러졌다)
            firstTenZero && b[10].toInt() == -1 && b[11].toInt() == -1 -> embedded(12) // ::ffff:0:0/96
            b[0].toInt() == 0x00 && b[1].toInt() == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                (4 until 12).all { b[it].toInt() == 0 } -> embedded(12) // 64:ff9b::/96 NAT64
            (b[0].toInt() and 0xFF) == 0x20 && b[1].toInt() == 0x02 -> embedded(2) // 2002::/16 6to4
            else -> true
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
