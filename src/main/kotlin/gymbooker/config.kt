package gymbooker

import gymbooker.pushpress.BookingReq

data class GymBookerConfig(
    val requests: List<BookingReq>
)