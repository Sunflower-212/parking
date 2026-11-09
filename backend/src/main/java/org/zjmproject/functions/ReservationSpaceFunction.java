package org.zjmproject.functions;
import lombok.Data;
/** Input contains parking coordinates only; actor identity is captured by the HTTP boundary. */
public final class ReservationSpaceFunction {
    @Data public static class ReservationSpaceRequest {
        private String parkingName;
        private Integer spaces_row;
        private Integer spaces_col;
        private Integer spaces_storey;
    }
}
