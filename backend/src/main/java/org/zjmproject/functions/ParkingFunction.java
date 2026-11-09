package org.zjmproject.functions;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zjmproject.pojo.PageBean;
import org.zjmproject.pojo.Parking;
import org.zjmproject.service.ParkingService;


import java.util.function.Function;

@Component
public class ParkingFunction implements Function<ParkingFunction.ParkingRequest,PageBean<Parking>> {
    @Autowired
    private ParkingService parkingService;

    @Override
    public PageBean<Parking> apply(ParkingRequest request) {
        return parkingService.findAllParking(1,1000,null,null,null,null);
    }

    public static class ParkingRequest{
        private String parkName;
        private String parkAddress;
        private String adminName;
        private String ruleName;
    }
}
