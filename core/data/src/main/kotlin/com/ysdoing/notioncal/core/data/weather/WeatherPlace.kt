package com.ysdoing.notioncal.core.data.weather

import java.math.BigDecimal
import java.math.RoundingMode

/** WX-02 위치 방식: 지역 직접 선택(기본, 권한 없음) 또는 대략 위치(ACCESS_COARSE_LOCATION, 앱 사용 중에만). */
enum class WeatherLocationMode { REGION, COARSE }

/** WX-02 예보 위치. 좌표는 소수 둘째 자리(약 1km)로 줄여 저장·전송한다. */
data class WeatherPlace(val name: String, val lat: Double, val lon: Double) {
    fun rounded(): WeatherPlace = copy(lat = round2(lat), lon = round2(lon))

    companion object {
        fun round2(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}
