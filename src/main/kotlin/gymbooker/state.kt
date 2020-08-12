package gymbooker

import gymbooker.pushpress.BookingReq

data class GymBookerState(
    val requests: List<BookingReq>
)